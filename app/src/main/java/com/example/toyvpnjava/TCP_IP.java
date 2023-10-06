package com.example.toyvpnjava;

import java.nio.ByteBuffer;

public class TCP_IP {

    private ByteBuffer packet;
    private String hostname;
    private String destIP;
    private String sourceIP;
    private int version;
    private int protocol;
    private int port;


    public TCP_IP(ByteBuffer pack) {
        this.packet = pack;
    }

    public void debug() {


        int buffer = packet.get();
        int headerlength;
        int temp;

        version = buffer >> 4;
        headerlength = buffer & 0x0F;
        headerlength *= 4;
        System.out.println("IP Version:"+version);
        System.out.println("Header Length:"+headerlength);
        String status = "";
        status += "Header Length:"+headerlength;

        buffer = packet.get();      //DSCP + EN
        buffer = packet.getChar();  //Total Length

        System.out.println( "Total Length:"+buffer);

        buffer = packet.getChar();  //Identification
        buffer = packet.getChar();  //Flags + Fragment Offset
        buffer = packet.get();      //Time to Live
        buffer = packet.get();      //Protocol

        protocol = buffer;
        System.out.println( "Protocol:"+buffer);

        status += "  Protocol:"+buffer;

        buffer = packet.getChar();  //Header checksum


        byte buff = (byte)buffer;

        sourceIP  = "";
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

        System.out.println( "Source IP:"+sourceIP);

        status += "   Source IP:"+sourceIP;


        destIP  = "";


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

        System.out.println( "Destination IP:" + destIP);
        status += "   Destination IP:"+destIP;




    }

    public String getDestination() {
        return destIP;
    }

    public int getProtocol() {
        return protocol;
    }

    public int getPort() {
        return port;
    }

    public String getHostname() {
        return hostname;
    }

    public int getIPversion() { return version; }

}
