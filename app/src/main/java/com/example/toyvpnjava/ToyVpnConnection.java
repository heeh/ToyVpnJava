/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.toyvpnjava;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.app.PendingIntent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.nio.channels.DatagramChannel;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class ToyVpnConnection implements Runnable {
    boolean isDebugging = true;
    //boolean isDebugging = false;
    private String TAG = "ToyVpnConnection";
    String L4_SOCKET_ADDR = "10.0.0.2";

    //String GOOGLE_DNS_SERVER = "8.8.8.8";
    String CF_DNS_SERVER = "1.1.1.1";

    /**
     * Callback interface to let the {@link ToyVpnService} know about new connections
     * and update the foreground notification with connection status.
     */
    public interface OnEstablishListener {
        void onEstablish(ParcelFileDescriptor tunInterface);
    }

    /**
     * Maximum packet size is constrained by the MTU, which is given as a signed short.
     */
    private static final int MAX_PACKET_SIZE = Short.MAX_VALUE;
    /**
     * Time to wait in between losing the connection and retrying.
     */
    private static final long RECONNECT_WAIT_MS = TimeUnit.SECONDS.toMillis(3);
    /**
     * Time between keepalives if there is no traffic at the moment.
     * <p>
     * TODO: don't do this; it's much better to let the connection die and then reconnect when
     *       necessary instead of keeping the network hardware up for hours on end in between.
     **/
    private static final long KEEPALIVE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(15);
    /**
     * Time to wait without receiving any response before assuming the server is gone.
     */
    private static final long RECEIVE_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(20);
    /**
     * Time between polling the VPN interface for new traffic, since it's non-blocking.
     * <p>
     * TODO: really don't do this; a blocking read on another thread is much cleaner.
     */
    private static final long IDLE_INTERVAL_MS = TimeUnit.MILLISECONDS.toMillis(100);
    /**
     * Number of periods of length {@IDLE_INTERVAL_MS} to wait before declaring the handshake a
     * complete and abject failure.
     * <p>
     * TODO: use a higher-level protocol; hand-rolling is a fun but pointless exercise.
     */
    private static final int MAX_HANDSHAKE_ATTEMPTS = 50;
    private final VpnService mService;
    private final int mConnectionId;
    private final String mServerName;
    private final int mServerPort;
    private final byte[] mSharedSecret;
    private PendingIntent mConfigureIntent;
    private OnEstablishListener mOnEstablishListener;
    // Proxy settings
    private String mProxyHostName;
    private int mProxyHostPort;
    // Allowed/Disallowed packages for VPN usage
    private final boolean mAllow;
    private final Set<String> mPackages;

    public ToyVpnConnection(final VpnService service, final int connectionId,
                            final String serverName, final int serverPort, final byte[] sharedSecret,
                            final String proxyHostName, final int proxyHostPort, boolean allow,
                            final Set<String> packages) {
        mService = service;
        mConnectionId = connectionId;
        mServerName = serverName;
        mServerPort = serverPort;
        mSharedSecret = sharedSecret;
        if (!TextUtils.isEmpty(proxyHostName)) {
            mProxyHostName = proxyHostName;
        }
        if (proxyHostPort > 0) {
            // The port value is always an integer due to the configured inputType.
            mProxyHostPort = proxyHostPort;
        }
        mAllow = allow;
        mPackages = packages;
    }

    /**
     * Optionally, set an intent to configure the VPN. This is {@code null} by default.
     */
    public void setConfigureIntent(PendingIntent intent) {
        mConfigureIntent = intent;
    }

    public void setOnEstablishListener(OnEstablishListener listener) {
        mOnEstablishListener = listener;
    }

    @Override
    public void run() {
        try {
            Log.i(getTag(), "Starting");
            // If anything needs to be obtained using the network, get it now.
            // This greatly reduces the complexity of seamless handover, which
            // tries to recreate the tunnel without shutting down everything.
            // In this demo, all we need to know is the server address.
            final SocketAddress serverAddress = new InetSocketAddress(mServerName, mServerPort);
            // We try to create the tunnel several times.
            // TODO: The better way is to work with ConnectivityManager, trying only when the
            // network is available.
            // Here we just use a counter to keep things simple.
            for (int attempt = 0; attempt < 10; ++attempt) {
                // Reset the counter if we were connected.
                if (run(serverAddress)) {
                    attempt = 0;
                }
                // Sleep for a while. This also checks if we got interrupted.
                Thread.sleep(3000);
            }
            Log.i(getTag(), "Giving up");
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            if (isDebugging) Log.e(getTag(), "Connection failed, exiting", e);
        }
    }

    private boolean run(SocketAddress server)
            throws IOException, InterruptedException, IllegalArgumentException {
        ParcelFileDescriptor iface = null;
        boolean connected = false;
        // Create a DatagramChannel as the VPN tunnel.

        iface = configure();
        // Now we are connected. Set the flag.
        connected = true;
        // Packets to be sent are queued in this input stream.
        FileInputStream in = new FileInputStream(iface.getFileDescriptor());
        // Packets received need to be written to this output stream.
        FileOutputStream out = new FileOutputStream(iface.getFileDescriptor());
        // Allocate the buffer for a single packet.
        ByteBuffer reqBuf = ByteBuffer.allocate(MAX_PACKET_SIZE);
        // Timeouts:
        //   - when data has not been sent in a while, send empty keepalive messages.
        //   - when data has not been received in a while, assume the connection is broken.
        long lastSendTime = System.currentTimeMillis();
        long lastReceiveTime = System.currentTimeMillis();
        // We keep forwarding packets till something goes wrong.
        while (true) {
            // Assume that we did not make any progress in this iteration.
            boolean idle = true;


            // (1) Read the outgoing packet from the input stream.
            int length = in.read(reqBuf.array());


            if (length > 0) {
                reqBuf.limit(length);

                // (2) L3 Packet deserialization

                ByteBuffer reqTemp = reqBuf.asReadOnlyBuffer();
                L3Packet reqPacket = new L3Packet(reqTemp);

                // Accepts only UDP with port 53
                if (!(reqPacket.protocol == 17 && reqPacket.destPort == 53)) {
                    reqBuf.clear();
                    reqTemp.clear();
                    continue;
                }

                idle = false;
                lastReceiveTime = System.currentTimeMillis();

                // (3) L4 Packet Forwarding (Device <-> DNS Server)


                if (isDebugging) Log.e(TAG, "REQUEST========================================================================================================================");
                if (isDebugging) reqPacket.print();
                DatagramPacket respDatagramPacket = forwardL4Packet(reqPacket);

                // TODO: (4) Packet Conversion (L3 <- L4)
                // TODO: (5) Write the L3 Buffer to output stream.


                ByteBuffer respBuf = ByteBuffer.allocate(MAX_PACKET_SIZE);

                respBuf.put(reqBuf.array(), 0, 12);

                // SRC IP (4 bytes)
//                respBuf.put((byte)1);
//                respBuf.put((byte)1);
//                respBuf.put((byte)1);
//                respBuf.put((byte)1);
                String[] destIpStr = reqPacket.destIP.split("\\.");
                respBuf.put(Integer.valueOf(destIpStr[0]).byteValue());
                respBuf.put(Integer.valueOf(destIpStr[1]).byteValue());
                respBuf.put(Integer.valueOf(destIpStr[2]).byteValue());
                respBuf.put(Integer.valueOf(destIpStr[3]).byteValue());


                // DEST IP (4 bytes)
                String[] srcIpStr = reqPacket.srcIP.split("\\.");
                respBuf.put(Integer.valueOf(srcIpStr[0]).byteValue());
                respBuf.put(Integer.valueOf(srcIpStr[1]).byteValue());
                respBuf.put(Integer.valueOf(srcIpStr[2]).byteValue());
                respBuf.put(Integer.valueOf(srcIpStr[3]).byteValue());

                // UDP Header (8 bytes)
                respBuf.putShort((short) reqPacket.destPort); // SRC PORT
                respBuf.putShort((short) reqPacket.srcPort);  // DEST PORT
                respBuf.putShort((short) (8 + respDatagramPacket.getLength()));  // UDP length
                respBuf.putShort((short) 0);  // Optional checksum; 0 for unused

                // L7 DNS Response Data
                respBuf.put(respDatagramPacket.getData(), 0, respDatagramPacket.getLength());
                respBuf.flip();  // limit = pos; pos = 0;
                out.write(respBuf.array(), 0, respBuf.limit());


                ByteBuffer respTemp = respBuf.asReadOnlyBuffer();
                L3Packet respPacket = new L3Packet(respTemp);
                if (isDebugging) Log.e(TAG, "RESPONSE========================================================================================================================");
                if (isDebugging) respPacket.print();
                reqBuf.clear();
                respBuf.clear();

                // There might be more incoming packets.
                idle = false;
                lastSendTime = System.currentTimeMillis();

            }
            // If we are idle or waiting for the network, sleep for a
            // fraction of time to avoid busy looping.
            if (idle) {
                Thread.sleep(IDLE_INTERVAL_MS);
                final long timeNow = System.currentTimeMillis();
                if (lastSendTime + KEEPALIVE_INTERVAL_MS <= timeNow) {
                    // We are receiving for a long time but not sending.
                    // Send empty control messages.
                    reqBuf.put((byte) 0).limit(1);
                    for (int i = 0; i < 3; ++i) {
                        reqBuf.position(0);
                        out.write(reqBuf.array(), 0, length);
                    }
                    reqBuf.clear();
                    lastSendTime = timeNow;
                } else if (lastReceiveTime + RECEIVE_TIMEOUT_MS <= timeNow) {
                    // We are sending for a long time but not receiving.
                    throw new IllegalStateException("Timed out");
                }
            }
        }
    }

    private ParcelFileDescriptor configure() throws IllegalArgumentException {
        // Configure a builder while parsing the parameters.
        VpnService.Builder builder = mService.new Builder();
        // Create a new interface using the builder and save the parameters.
        final ParcelFileDescriptor vpnInterface;
//        for (String packageName : mPackages) {
//            try {
//                if (mAllow) {
//                    builder.addAllowedApplication(packageName);
//                } else {
//                    builder.addDisallowedApplication(packageName);
//                }
//            } catch (PackageManager.NameNotFoundException e) {
//                Log.w(getTag(), "Package not available: " + packageName, e);
//            }
//        }
//        builder.setSession(mServerName).setConfigureIntent(mConfigureIntent);

        synchronized (mService) {

            String VPN_IP_ADDRESS = "10.0.0.2";
            String VPN_VIRTUAL_DNS_SERVER = "10.0.0.99";


            builder
                    .addAddress(VPN_IP_ADDRESS, 32)
//                    .addRoute("0.0.0.0", 1)
//                    .addRoute("128.0.0.0", 1)
                    .addRoute(VPN_VIRTUAL_DNS_SERVER, 32)
                    .addDnsServer(VPN_VIRTUAL_DNS_SERVER);

            vpnInterface = builder.establish();
            if (mOnEstablishListener != null) {
                mOnEstablishListener.onEstablish(vpnInterface);
            }
        }
        Log.i(getTag(), "New interface: " + vpnInterface);
        return vpnInterface;
    }

    private final String getTag() {
        return ToyVpnConnection.class.getSimpleName() + "[" + mConnectionId + "]";
    }

    private DatagramPacket forwardL4Packet(L3Packet l3Packet) throws IOException {

        DatagramChannel channel = DatagramChannel.open();
        mService.protect(channel.socket());
        channel.connect(new InetSocketAddress(CF_DNS_SERVER, 53));
        channel.write(ByteBuffer.wrap(l3Packet.data));
        byte[] response = new byte[MAX_PACKET_SIZE];
        DatagramPacket respDatagramPacket = new DatagramPacket(response, response.length);
        channel.socket().receive(respDatagramPacket);

        if (isDebugging) Log.e(TAG, "[address]: " + respDatagramPacket.getAddress()
                + "\n[port]: " + respDatagramPacket.getPort()
                + "\n[socket addr]: " + respDatagramPacket.getSocketAddress()
                + "\n[length]: " + respDatagramPacket.getLength()
                + "\n[offset]: " + respDatagramPacket.getOffset()
                + "\n[L4 data]: " + new String(respDatagramPacket.getData(), UTF_8).substring(0, respDatagramPacket.getLength()));

        short QDCOUNT = 1;
        short ANCOUNT = 0;
        short NSCOUNT = 0;
        short ARCOUNT = 0;

        if (isDebugging) Log.e(TAG, "\n\nResponse Received: " + respDatagramPacket.getLength() + " bytes");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < respDatagramPacket.getLength(); i++) {
            sb.append(response[i]);
            sb.append(" ");
        }
        if (isDebugging) Log.e(TAG, sb.toString());

        DataInputStream dataInputStream = new DataInputStream(new ByteArrayInputStream(response));
        if (isDebugging) Log.e(TAG, "\n\nStart response decode");
        if (isDebugging) Log.e(TAG, "Transaction ID: " + dataInputStream.readShort()); // ID
        short flags = dataInputStream.readByte();
        int QR = (flags & 0b10000000) >>> 7;
        int opCode = (flags & 0b01111000) >>> 3;
        int AA = (flags & 0b00000100) >>> 2;
        int TC = (flags & 0b00000010) >>> 1;
        int RD = flags & 0b00000001;
        if (isDebugging) Log.e(TAG, "QR " + QR);
        if (isDebugging) Log.e(TAG, "Opcode " + opCode);
        if (isDebugging) Log.e(TAG, "AA " + AA);
        if (isDebugging) Log.e(TAG, "TC " + TC);
        if (isDebugging) Log.e(TAG, "RD " + RD);
        flags = dataInputStream.readByte();
        int RA = (flags & 0b10000000) >>> 7;
        int Z = (flags & 0b01110000) >>> 4;
        int RCODE = flags & 0b00001111;
        if (isDebugging) Log.e(TAG, "RA " + RA);
        if (isDebugging) Log.e(TAG, "Z " + Z);
        if (isDebugging) Log.e(TAG, "RCODE " + RCODE);

        QDCOUNT = dataInputStream.readShort();
        ANCOUNT = dataInputStream.readShort();
        NSCOUNT = dataInputStream.readShort();
        ARCOUNT = dataInputStream.readShort();

        if (isDebugging) Log.e(TAG, "Questions: " + String.format("%s", QDCOUNT));
        if (isDebugging) Log.e(TAG, "Answers RRs: " + String.format("%s", ANCOUNT));
        if (isDebugging) Log.e(TAG, "Authority RRs: " + String.format("%s", NSCOUNT));
        if (isDebugging) Log.e(TAG, "Additional RRs: " + String.format("%s", ARCOUNT));

        String QNAME = "";
        int recLen;
        while ((recLen = dataInputStream.readByte()) > 0) {
            byte[] record = new byte[recLen];
            for (int i = 0; i < recLen; i++) {
                record[i] = dataInputStream.readByte();
            }
            QNAME = new String(record, UTF_8);
        }
        short QTYPE = dataInputStream.readShort();
        short QCLASS = dataInputStream.readShort();
        if (isDebugging) Log.e(TAG, "Record: " + QNAME);
        if (isDebugging) Log.e(TAG, "Record Type: " + String.format("%s", QTYPE));
        if (isDebugging) Log.e(TAG, "Class: " + String.format("%s", QCLASS));

        if (isDebugging) Log.e(TAG, "\n\nstart answer, authority, and additional sections\n");

        byte firstBytes = dataInputStream.readByte();
        int firstTwoBits = (firstBytes & 0b11000000) >>> 6;

        ByteArrayOutputStream label = new ByteArrayOutputStream();
        Map<String, String> domainToIp = new HashMap<>();

        for (int i = 0; i < ANCOUNT; i++) {
            if (firstTwoBits == 3) {
                byte currentByte = dataInputStream.readByte();
                boolean stop = false;
                byte[] newArray = Arrays.copyOfRange(response, currentByte, response.length);
                DataInputStream sectionDataInputStream = new DataInputStream(new ByteArrayInputStream(newArray));
                ArrayList<Integer> RDATA = new ArrayList<>();
                ArrayList<String> DOMAINS = new ArrayList<>();
                while (!stop) {
                    byte nextByte = sectionDataInputStream.readByte();
                    if (nextByte > 0) {
                        byte[] currentLabel = new byte[nextByte];
                        for (int j = 0; j < nextByte; j++) {
                            currentLabel[j] = sectionDataInputStream.readByte();
                        }
                        label.write(currentLabel);
                    } else {
                        stop = true;
                        short TYPE = dataInputStream.readShort();
                        short CLASS = dataInputStream.readShort();
                        int TTL = dataInputStream.readInt();
                        int RDLENGTH = dataInputStream.readShort();
                        for (int s = 0; s < RDLENGTH; s++) {
                            int nx = dataInputStream.readByte() & 255;// and with 255 to
                            RDATA.add(nx);
                        }

                        if (isDebugging) Log.e(TAG, "Type: " + TYPE);
                        if (isDebugging) Log.e(TAG, "Class: " + CLASS);
                        if (isDebugging) Log.e(TAG, "Time to live: " + TTL);
                        if (isDebugging) Log.e(TAG, "Rd Length: " + RDLENGTH);
                    }

                    DOMAINS.add(label.toString(UTF_8));
                    label.reset();
                }

                StringBuilder ip = new StringBuilder();
                StringBuilder domainSb = new StringBuilder();
                for (Integer ipPart : RDATA) {
                    ip.append(ipPart).append(".");
                }

                for (String domainPart : DOMAINS) {
                    if (!domainPart.equals("")) {
                        domainSb.append(domainPart).append(".");
                    }
                }
                String domainFinal = domainSb.toString();
                String ipFinal = ip.toString();
                domainToIp.put(ipFinal.substring(0, ipFinal.length() - 1), domainFinal.substring(0, domainFinal.length() - 1));

            } else if (firstTwoBits == 0) {
                if (isDebugging) Log.e(TAG, "It's a label");
            }

            firstBytes = dataInputStream.readByte();
            firstTwoBits = (firstBytes & 0b11000000) >>> 6;
        }

        domainToIp.forEach((key, value) -> {
            if (isDebugging) Log.e(TAG, key + " : " + value);
        });


        channel.close();

        return respDatagramPacket;
    }

}