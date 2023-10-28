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

import android.app.PendingIntent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.nio.channels.DatagramChannel;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class ToyVpnConnection implements Runnable {
    private String TAG = "ToyVpnConnection";
    String L4_SOCKET_ADDR = "10.215.173.3";
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
            Log.e(getTag(), "Connection failed, exiting", e);
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
        ByteBuffer packet = ByteBuffer.allocate(MAX_PACKET_SIZE);
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
            int length = in.read(packet.array());



            if (length > 0) {
                packet.limit(length);

                // (2) Packet Conversion (L3 -> L4)
                L4Packet l4Packet = getL4Packet(packet);
                l4Packet.print();

                idle = false;
                lastReceiveTime = System.currentTimeMillis();

                // (3) L4 Packet Forwarding (Device <-> DNS Server)
                String datagramPacketStr = forwardL4Packet(l4Packet);
                Log.e(TAG, datagramPacketStr);

                // TODO:(4) Packet Conversion (L3 <- L4)

                // TODO: (5) Write the L3 Buffer to output stream.
//                out.write(packet.array(), 0, length);
                packet.clear();
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
                    packet.put((byte) 0).limit(1);
                    for (int i = 0; i < 3; ++i) {
                        packet.position(0);
                        out.write(packet.array(), 0, length);
                    }
                    packet.clear();
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

            String VPN_IP_ADDRESS = "10.215.173.1";
            String VPN_VIRTUAL_DNS_SERVER = "10.215.173.2";




//            String VPN_VIRTUAL_DNS_SERVER = "8.8.8.8";

            builder
                    .addAddress(VPN_IP_ADDRESS, 30)
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


    L4Packet getL4Packet(ByteBuffer packet) {
        // Extract Destination IP
        ByteBuffer temp = packet.asReadOnlyBuffer();
        return new L4Packet(temp);
    }

    private String forwardL4Packet(L4Packet l4Packet) throws IOException {
        DatagramChannel channel = DatagramChannel.open();
        channel.configureBlocking(false);
        mService.protect(channel.socket());
        channel.socket().bind(new InetSocketAddress(InetAddress.getByName(L4_SOCKET_ADDR), 7777));

        // Send
        InetSocketAddress destAddress = new InetSocketAddress(l4Packet.destIP, l4Packet.destPort);
        channel.send(ByteBuffer.wrap(l4Packet.data), destAddress);

        // Receive
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        channel.receive(buffer);

        // ByteBuffer -> String
        buffer.flip();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        String msg = new String(bytes);
        return msg;
    }

}