package com.example.toyvpnjava;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.util.Log;

import java.nio.ByteBuffer;

public class L3Packet {

    public String TAG = "L3Packet";
    public ByteBuffer packet;
    public String hostname;
    public String srcIP;
    public String destIP;
    public int version;
    public int protocol;
    //public int port;

    public int srcPort;
    public int destPort;

    public byte[] data;

    public L3Packet(byte[] data) {
        data = data;
    }


    public L3Packet(ByteBuffer pack) {
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

        srcIP = "";
        buff = packet.get();  //Source IP 1st Octet
        temp = ((int) buff) & 0xFF;
        srcIP += temp;
        srcIP += ".";

        buff = packet.get();  //Source IP 2nd Octet
        temp = ((int) buff) & 0xFF;
        srcIP += temp;
        srcIP += ".";

        buff = packet.get();  //Source IP 3rd Octet
        temp = ((int) buff) & 0xFF;
        srcIP += temp;
        srcIP += ".";

        buff = packet.get();  //Source IP 4th Octet
        temp = ((int) buff) & 0xFF;
        srcIP += temp;

//        System.out.println( "Source IP:"+sourceIP);

        status += "   Source IP:" + srcIP;


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


        buff = packet.get();
        first = ((int) buff) & 0xFF;
        buff = packet.get();
        second = ((int) buff) & 0xFF;
        destPort = first * 256 + second;

        // length for UDP
        buff = packet.get();
        buff = packet.get();

        // checksum for UDP
        buff = packet.get();
        buff = packet.get();


        data = new byte[packet.remaining()];
        packet.get(data);
    }

    // 6 for TCP, 17 for UDP
    public String getProtocolStr() {
        if (protocol == 6) return "TCP";
        else if (protocol == 17) return "UDP";
        else return String.valueOf(protocol);
    }

    public String getDataStr() {
        return new String(data, UTF_8);
    }


    public void print() {
        Log.e(TAG, "\n[Protocol]: " + getProtocolStr()
                + "\t [srcIP]: <" + srcIP + ":" + srcPort + ">"
                + "\t [destIP]: <" + destIP + ":" + destPort + ">"
                + "\t [L3 data]: " + getDataStr());
    }
}