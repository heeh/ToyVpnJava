package com.example.toyvpnjava;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.util.Log;

import java.nio.ByteBuffer;

public class L4Packet {

    public String TAG = "L3Packet";
    private ByteBuffer packet;
    private String hostname;
    private String sourceIP;
    private String destIP;
    private int version;
    private int protocol;
    //private int port;

    private int srcPort;
    private int destPort;

    public String data;

    public L4Packet(ByteBuffer pack) {
        this.packet = pack;

        int buffer = packet.get();
        int headerlength;
        int temp;

        version = buffer >> 4;
        headerlength = buffer & 0x0F;
        headerlength *= 4;
//        System.out.println("IP Version:"+version);
//        System.out.println("Header Length:"+headerlength);
        String status = "";
        status += "Header Length:" + headerlength;

        buffer = packet.get();      //DSCP + EN
        buffer = packet.getChar();  //Total Length

//        System.out.println( "Total Length:"+buffer);

        buffer = packet.getChar();  //Identification
        buffer = packet.getChar();  //Flags + Fragment Offset
        buffer = packet.get();      //Time to Live
        buffer = packet.get();      //Protocol

        protocol = buffer;
//        System.out.println( "Protocol:"+buffer);

        status += "  Protocol:" + buffer;

        buffer = packet.getChar();  //Header checksum


        byte buff = (byte) buffer;

        sourceIP = "";
        buff = packet.get();  //Source IP 1st Octet
        temp = ((int) buff) & 0xFF;
        sourceIP += temp;
        sourceIP += ".";

        buff = packet.get();  //Source IP 2nd Octet
        temp = ((int) buff) & 0xFF;
        sourceIP += temp;
        sourceIP += ".";

        buff = packet.get();  //Source IP 3rd Octet
        temp = ((int) buff) & 0xFF;
        sourceIP += temp;
        sourceIP += ".";

        buff = packet.get();  //Source IP 4th Octet
        temp = ((int) buff) & 0xFF;
        sourceIP += temp;

//        System.out.println( "Source IP:"+sourceIP);

        status += "   Source IP:" + sourceIP;


        destIP = "";


        buff = packet.get();  //Destination IP 1st Octet
        temp = ((int) buff) & 0xFF;
        destIP += temp;
        destIP += ".";

        buff = packet.get();  //Destination IP 2nd Octet
        temp = ((int) buff) & 0xFF;
        destIP += temp;
        destIP += ".";

        buff = packet.get();  //Destination IP 3rd Octet
        temp = ((int) buff) & 0xFF;
        destIP += temp;
        destIP += ".";

        buff = packet.get();  //Destination IP 4th Octet
        temp = ((int) buff) & 0xFF;
        destIP += temp;

//        System.out.println( "Destination IP:" + destIP);
        status += "   Destination IP:" + destIP;

        buff = packet.get();
        int first = ((int) buff) & 0xFF;
        buff = packet.get();
        int second = ((int) buff) & 0xFF;
        srcPort = first * 256 + second;
        sourceIP += ":";
        sourceIP += srcPort;

        buff = packet.get();
        first = ((int) buff) & 0xFF;
        buff = packet.get();
        second = ((int) buff) & 0xFF;
        destPort = first * 256 + second;
        destIP += ":";
        destIP += destPort;

        // length for UDP
        buff = packet.get();
        buff = packet.get();

        // checksum for UDP
        buff = packet.get();
        buff = packet.get();


        byte[] bytes = new byte[packet.remaining()];
        packet.get(bytes);
        data = new String(bytes, UTF_8);
    }

    public String getSourceIP() {
        return sourceIP;
    }

    public String getDestIP() {
        return destIP;
    }

    public int getSourcePort() {
        return srcPort;
    }

    public int getDestPort() {
        return destPort;
    }


    public int getProtocol() {
        return protocol;
    }

    // 6 for TCP, 17 for UDP
    public String getProtocolStr() {
        if (protocol == 6) return "TCP";
        else if (protocol == 17) return "UDP";
        else return String.valueOf(protocol);
    }

    public String getHostname() {
        return hostname;
    }

    public int getIPversion() {
        return version;
    }


    public void print() {
        //if (networkPacket.getDestPort() == 53) {
        Log.e(TAG, "[Protocol]: " + getProtocolStr()
                + "\t [srcIP]: <" + sourceIP + ">"
                + "\t [destIP]: <" + destIP + ">"
                + "\t [data]: " + data);
    }
}