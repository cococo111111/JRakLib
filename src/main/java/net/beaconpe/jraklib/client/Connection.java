package net.beaconpe.jraklib.client;

import net.beaconpe.jraklib.Binary;
import net.beaconpe.jraklib.JRakLib;
import net.beaconpe.jraklib.protocol.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a Connection to a server.
 *
 * @author jython234
 */
public class Connection {

    public final static int STATE_UNCONNECTED = 0;
    public final static int STATE_CONNECTING_1 = 1;
    public final static int STATE_CONNECTING_2 = 2;
    public final static int STATE_CONNECTING_3 = 3;
    public final static int STATE_CONNECTED = 4;

    public static long sessionID = new Random().nextLong();
    public static int WINDOW_SIZE = 2048;

    protected ConnectionManager manager;

    private int state = STATE_UNCONNECTED;
    private List<EncapsulatedPacket> preJoinQueue = new ArrayList<>();
    private int mtuSize = 548; //Min size
    private long id = 0;
    private int splitID = 0;

    private int messageIndex = 0;
    private Map<Byte, Integer> channelIndex = new ConcurrentHashMap<>();

    private int sendSeqNumber = 0;
    private int lastSeqNumber = -1;

    private long lastUpdate;
    private long startTime;

    private List<DataPacket> packetToSend = new ArrayList<>();

    private boolean isActive;

    private List<Integer> ACKQueue = new ArrayList<>();
    private List<Integer> NACKQueue = new ArrayList<>();

    private Map<Integer, DataPacket> recoveryQueue = new ConcurrentHashMap<>();

    private Map<Short, Map<Integer, EncapsulatedPacket>> splitPackets = new ConcurrentHashMap<>();

    private Map<Integer, Map<Integer, Integer>> needACK = new ConcurrentHashMap<>();


    private DataPacket sendQueue;

    private int windowStart;
    private Map<Integer, Integer> receivedWindow = new ConcurrentHashMap<>();
    private int windowEnd;

    private int reliableWindowStart;
    private int reliableWindowEnd;
    private Map<Integer, EncapsulatedPacket> reliableWindow = new ConcurrentHashMap<>();
    private int lastReliableIndex = -1;

    private long lastPing;
    private long lastPong = Instant.now().toEpochMilli();

    public Connection(ConnectionManager manager, int mtuSize){
        this.manager = manager;
        this.mtuSize = mtuSize;
        sendQueue = new DataPackets.DATA_PACKET_4();
        lastUpdate = Instant.now().toEpochMilli();
        startTime = Instant.now().toEpochMilli();
        isActive = false;
        windowStart = -1;
        windowEnd = WINDOW_SIZE;

        reliableWindowStart = 0;
        reliableWindowEnd = WINDOW_SIZE;

        for(byte i = 0; i < 32; i++){
            channelIndex.put(i, 0);
        }
    }

    public void update(long time) throws IOException {
        isActive = false;
        if((Instant.now().toEpochMilli() - lastPong) >= 8000){
            disconnect("connection timed out.");
        }
        if((Instant.now().toEpochMilli() - lastPing) >= 5000){
            sendPing();
        }
        if(!ACKQueue.isEmpty()){
            ACK pk = new ACK();
            pk.packets = ACKQueue.stream().toArray(Integer[]::new);
            sendPacket(pk);
            ACKQueue.clear();
        }

        if(!NACKQueue.isEmpty()){
            NACK pk = new NACK();
            pk.packets = NACKQueue.stream().toArray(Integer[]::new);
            sendPacket(pk);
            NACKQueue.clear();
        }

        if(!packetToSend.isEmpty()){
            int limit = 16;
            for(int i = 0; i < packetToSend.size(); i++){
                DataPacket pk = packetToSend.get(i);
                pk.sendTime = time;
                pk.encode();
                recoveryQueue.put(pk.seqNumber, pk);
                packetToSend.remove(pk);
                sendPacket(pk);
                if(limit-- <= 0){
                    break;
                }
            }
        }

        if(packetToSend.size() > WINDOW_SIZE){
            packetToSend.clear();
        }

        if(needACK.values().size() > 0){
            for(Integer i : needACK.keySet()){
                Map<Integer, Integer> indexes = needACK.get(i);
                if(indexes.values().size() == 0){
                    needACK.remove(indexes);
                    //sessionManager.notifyACK(this, i);
                }
            }
        }

        for(Integer seq : recoveryQueue.keySet()){
            DataPacket pk = recoveryQueue.get(seq);
            if(pk.sendTime < Instant.now().toEpochMilli() - 6000){ //If no ACK in 6 seconds, resend :)
                packetToSend.add(pk);
                recoveryQueue.remove(seq);
            } else {
                break;
            }
        }

        for(Integer seq : receivedWindow.keySet()){
            if(seq < windowStart){
                receivedWindow.remove(seq);
            } else {
                break;
            }
        }

        try {
            sendQueue();
        } catch (IOException e) {
            manager.getLogger().notice("Failed to send queue: IOException: "+e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private void sendPing() throws IOException {
        PING_DataPacket ping = new PING_DataPacket();
        ping.pingID = Instant.now().toEpochMilli();
        ping.encode();

        EncapsulatedPacket pk = new EncapsulatedPacket();
        pk.reliability = 0;
        pk.buffer = ping.buffer;
        addToQueue(pk);
        lastPing = Instant.now().toEpochMilli();
    }

    public void disconnect(String reason){
        manager.streamClose("0.0.0.0:"+manager.getSocket().getSocket().getLocalPort(), reason);
        manager.shutdown = true;
    }

    public void disconnect() {
        disconnect("disconnected by client.");
    }

    private void sendPacket(Packet pk) throws IOException {
        manager.sendPacket(pk, manager.getClient().getServerIP(), manager.getClient().getServerPort());
    }

    public void sendQueue() throws IOException {
        if(!sendQueue.packets.isEmpty()){
            sendQueue.seqNumber = sendSeqNumber++;
            sendPacket(sendQueue);
            sendQueue.sendTime = Instant.now().toEpochMilli();
            recoveryQueue.put(sendQueue.seqNumber, sendQueue);
            sendQueue = new DataPackets.DATA_PACKET_4();
        }
    }

    private void addToQueue(EncapsulatedPacket pk) throws IOException {
        addToQueue(pk, JRakLib.PRIORITY_NORMAL);
    }

    private void addToQueue(EncapsulatedPacket pk, int flags) throws IOException {
        int priority = flags & 0b0000111;
        if(pk.needACK && pk.messageIndex != -1){
            Map<Integer, Integer> map;
            if(needACK.get(pk.needACK) != null){
                map = needACK.get(pk.needACK);
                map.put(pk.messageIndex, pk.messageIndex);
            } else {
                map = new ConcurrentHashMap<>();
                map.put(pk.messageIndex, pk.messageIndex);
            }
            needACK.put(pk.identifierACK, map);
        }

        if(priority == JRakLib.PRIORITY_IMMEDIATE){ //Skip queues
            DataPacket packet = new DataPackets.DATA_PACKET_0();
            packet.seqNumber = sendSeqNumber++;
            if(pk.needACK){
                packet.packets.add(pk);
                pk.needACK = false;
            } else {
                packet.packets.add(pk.toBinary());
            }

            sendPacket(packet);
            packet.sendTime = Instant.now().toEpochMilli();
            recoveryQueue.put(packet.seqNumber, packet);
            return;
        }
        int length = sendQueue.length();
        if(length + pk.getTotalLength() > mtuSize){
            sendQueue();
        }

        if(pk.needACK){
            sendQueue.packets.add(pk);
            pk.needACK = false;
        } else {
            sendQueue.packets.add(pk.toBinary());
        }
    }

    public void addEncapsulatedToQueue(EncapsulatedPacket packet) throws IOException {
        addEncapsulatedToQueue(packet, JRakLib.PRIORITY_NORMAL);
    }

    public void addEncapsulatedToQueue(EncapsulatedPacket packet, byte flags) throws IOException {
        if((packet.needACK = (flags & JRakLib.FLAG_NEED_ACK) > 0) == true){
            needACK.put(packet.identifierACK, new ConcurrentHashMap<>());
        }

        if(packet.reliability == 2 || packet.reliability == 3 || packet.reliability == 4 || packet.reliability == 6 || packet.reliability == 7){
            packet.messageIndex = messageIndex++;

            if(packet.reliability == 3){
                channelIndex.put(packet.orderChannel, channelIndex.get(packet.orderChannel) + 1);
                packet.orderIndex = channelIndex.get(packet.orderChannel);
            }
        }

        if(packet.getTotalLength() + 4 > mtuSize){
            byte[][] buffers = Binary.splitbytes(packet.buffer, mtuSize - 34);
            int splitID = this.splitID++;
            splitID = splitID % 65536;
            for(int count = 0; count < buffers.length; count++){
                byte[] buffer = buffers[count];
                EncapsulatedPacket pk = new EncapsulatedPacket();
                pk.splitID = (short) splitID;
                pk.hasSplit = true;
                pk.splitCount = buffers.length;
                pk.reliability = packet.reliability;
                pk.splitIndex = count;
                pk.buffer = buffer;
                if(count > 0){
                    pk.messageIndex = messageIndex++;
                } else {
                    pk.messageIndex = packet.messageIndex;
                }
                if(pk.reliability == 3){
                    pk.orderChannel = packet.orderChannel;
                    pk.orderIndex = packet.orderIndex;
                }
                addToQueue(pk, flags | JRakLib.PRIORITY_IMMEDIATE);
            }
        } else {
            addToQueue(packet, flags);
        }
    }

    private void handleSplit(EncapsulatedPacket packet) throws IOException {
        if(packet.splitCount >= 128){
            return;
        }

        if(!splitPackets.containsKey(packet.splitID)){
            Map<Integer, EncapsulatedPacket> map = new ConcurrentHashMap<>();
            map.put(packet.splitIndex, packet);
            splitPackets.put(packet.splitID, map);
        } else {
            Map<Integer, EncapsulatedPacket> map = splitPackets.get(packet.splitID);
            map.put(packet.splitIndex, packet);
            splitPackets.put(packet.splitID, map);
        }

        if(splitPackets.get(packet.splitID).values().size() == packet.splitCount){
            EncapsulatedPacket pk = new EncapsulatedPacket();
            ByteBuffer bb = ByteBuffer.allocate(64 * 64 * 64);
            for(int i = 0; i < packet.splitCount; i++){
                bb.put(splitPackets.get(packet.splitID).get(i).buffer);
            }
            pk.buffer = Arrays.copyOf(bb.array(), bb.position());
            bb = null;

            pk.length = pk.buffer.length;
            splitPackets.remove(packet.splitID);

            handleEncapsulatedPacketRoute(pk);
        }
    }

    private void handleEncapsulatedPacket(EncapsulatedPacket packet) throws IOException {
        if(packet.messageIndex == -1){
            handleEncapsulatedPacketRoute(packet);
        } else {
            if(packet.messageIndex < reliableWindowStart || packet.messageIndex > reliableWindowEnd){
                return;
            }

            if((packet.messageIndex - lastReliableIndex) == 1){
                lastReliableIndex++;
                reliableWindowStart++;
                reliableWindowEnd++;
                handleEncapsulatedPacketRoute(packet);

                if(!reliableWindow.values().isEmpty()){
                    //TODO: Implement ksort() ?
                    //ksort(reliableWindow.values());

                    for(Integer index : reliableWindow.keySet()){
                        EncapsulatedPacket pk = reliableWindow.get(index);

                        if((index - lastReliableIndex) != 1){
                            break;
                        }
                        lastReliableIndex++;
                        reliableWindowStart++;
                        reliableWindowEnd++;
                        handleEncapsulatedPacketRoute(packet);
                        reliableWindow.remove(index);
                    }
                }
            } else {
                reliableWindow.put(packet.messageIndex, packet);
            }
        }
    }

    private void handleEncapsulatedPacketRoute(EncapsulatedPacket packet) throws IOException {
        if(manager == null){
            return;
        }

        if(packet.hasSplit){
            if(state == STATE_CONNECTED){
                handleSplit(packet);
            }
            return;
        }

        byte id = packet.buffer[0];
        if(id < 0x80) { //internal data packet
            if (state == STATE_CONNECTING_3) {
                if (id == SERVER_HANDSHAKE_DataPacket.ID) {
                    SERVER_HANDSHAKE_DataPacket pk = new SERVER_HANDSHAKE_DataPacket();
                    pk.buffer = packet.buffer;
                    pk.decode();

                    CLIENT_HANDSHAKE_DataPacket response = new CLIENT_HANDSHAKE_DataPacket();
                    response.address = new InetSocketAddress("0.0.0.0", 0);
                    response.systemAddresses = new InetSocketAddress[] {
                            new InetSocketAddress("0.0.0.0", 0),
                            new InetSocketAddress("0.0.0.0", 0),
                            new InetSocketAddress("0.0.0.0", 0),
                            new InetSocketAddress("0.0.0.0", 0),
                            new InetSocketAddress("0.0.0.0", 0),
                            new InetSocketAddress("0.0.0.0", manager.getSocket().getSocket().getLocalPort()),
                            new InetSocketAddress("0.0.0.0", 0),
                            new InetSocketAddress("0.0.0.0", manager.getSocket().getSocket().getLocalPort()),
                            new InetSocketAddress("0.0.0.0", 0),
                            new InetSocketAddress("0.0.0.0", manager.getSocket().getSocket().getLocalPort()),
                    };
                    response.sendPing = Instant.now().toEpochMilli();
                    response.sendPong = Instant.now().toEpochMilli();
                    response.encode();

                    EncapsulatedPacket sendPacket = new EncapsulatedPacket();
                    sendPacket.reliability = 0;
                    sendPacket.buffer = response.buffer;
                    addToQueue(sendPacket, JRakLib.PRIORITY_IMMEDIATE);

                    if(!manager.portChecking){
                        state = STATE_CONNECTED;
                        manager.streamOpen(this.id);
                        for(EncapsulatedPacket p : preJoinQueue){
                            manager.streamEncapsulated(p);
                        }
                        preJoinQueue.clear();
                    }

                }
            } else if (id == CLIENT_DISCONNECT_DataPacket.ID) {
                disconnect("disconnected by server.");
            } else if (id == PING_DataPacket.ID) {
                PING_DataPacket dataPacket = new PING_DataPacket();
                dataPacket.buffer = packet.buffer;
                dataPacket.decode();

                PONG_DataPacket pk = new PONG_DataPacket();
                pk.pingID = dataPacket.pingID;
                pk.encode();

                EncapsulatedPacket sendPacket = new EncapsulatedPacket();
                sendPacket.reliability = 0;
                sendPacket.buffer = pk.buffer;
                addToQueue(sendPacket);
                //TODO: add PING/PONG (0x00/0x03) automatic latency measure
            } else if (id == PONG_DataPacket.ID) {
                lastPong = Instant.now().toEpochMilli();
            } else if(state  == STATE_CONNECTED) {
                manager.streamEncapsulated(packet);
                //TODO: stream channels
            }
        } else {
            preJoinQueue.add(packet);
        }
    }

    public void handlePacket(Packet packet) throws IOException{
        isActive = true;
        lastUpdate = Instant.now().toEpochMilli();
        if(state == STATE_CONNECTED || state == STATE_CONNECTING_3){
            if(packet.buffer[0] >= 0x80 || packet.buffer[0] <= 0x8f && packet instanceof DataPacket){
                packet.decode();

                DataPacket dp = (DataPacket) packet;
                if(dp.seqNumber < windowStart || dp.seqNumber > windowEnd || receivedWindow.containsKey(dp.seqNumber)){
                    return;
                }

                int diff = dp.seqNumber - lastSeqNumber;

                NACKQueue.remove(Integer.valueOf(dp.seqNumber));
                ACKQueue.add(dp.seqNumber);
                receivedWindow.put(dp.seqNumber, dp.seqNumber);

                if(diff != 1){
                    for(int i = lastSeqNumber + 1; i < dp.seqNumber; i++){
                        if(!receivedWindow.containsKey(i)){
                            NACKQueue.add(i);
                        }
                    }
                }

                if(diff >= 1){
                    lastSeqNumber = dp.seqNumber;
                    windowStart += diff;
                    windowEnd += diff;
                }

                for(Object pk : dp.packets){
                    if(pk instanceof EncapsulatedPacket) {
                        handleEncapsulatedPacket((EncapsulatedPacket) pk);
                    }
                }
            } else {
                if(packet instanceof ACK){
                    packet.decode();
                    for(int seq : ((ACK) packet).packets){
                        if(recoveryQueue.containsKey(seq)){
                            for(Object pk : recoveryQueue.get(seq).packets){
                                if(pk instanceof EncapsulatedPacket && ((EncapsulatedPacket) pk).needACK && ((EncapsulatedPacket) pk).messageIndex != -1){
                                    if(needACK.containsKey(((EncapsulatedPacket) pk).identifierACK)){
                                        Map<Integer, Integer> map = needACK.get(((EncapsulatedPacket) pk).identifierACK);
                                        map.remove(((EncapsulatedPacket) pk).messageIndex);
                                        needACK.put(((EncapsulatedPacket) pk).identifierACK, map);
                                    }
                                }
                                recoveryQueue.remove(seq);
                            }
                        }
                    }
                } else if(packet instanceof NACK){
                    packet.decode();
                    for(Integer seq : ((NACK) packet).packets){
                        if(recoveryQueue.containsKey(seq)){
                            DataPacket pk = recoveryQueue.get(seq);
                            pk.seqNumber = sendSeqNumber++;
                            packetToSend.add(pk);
                            recoveryQueue.remove(seq);
                        }
                    }
                }
            }
        } else if(packet.buffer[0] > 0x00 || packet.buffer[0] < 0x80){ //Not Data packet :)
            packet.decode();
            if(packet instanceof OPEN_CONNECTION_REPLY_1){
                OPEN_CONNECTION_REPLY_1 reply1 = (OPEN_CONNECTION_REPLY_1) packet;
                this.id = reply1.serverID;
                OPEN_CONNECTION_REQUEST_2 request2 = new OPEN_CONNECTION_REQUEST_2();
                request2.mtuSize = (short) this.mtuSize;
                request2.clientID = JRakLibClient.getClientID();
                request2.serverAddress = manager.getClient().getServerEndpoint();
                request2.encode();
                sendPacket(request2);
                if(reply1.mtuSize == this.mtuSize){
                    state = STATE_CONNECTING_2;
                }
            } else if(state == STATE_CONNECTING_2 && packet instanceof OPEN_CONNECTION_REPLY_2){
                OPEN_CONNECTION_REPLY_2 reply2 = (OPEN_CONNECTION_REPLY_2) packet;
                if(((OPEN_CONNECTION_REPLY_2) packet).clientAddress.getPort() == manager.getSocket().getSocket().getLocalPort() || !manager.portChecking){
                    CLIENT_CONNECT_DataPacket connect = new CLIENT_CONNECT_DataPacket();
                    connect.clientID = JRakLibClient.getClientID();
                    connect.sendPing = sessionID;
                    connect.encode();

                    EncapsulatedPacket pk = new EncapsulatedPacket();
                    pk.reliability = 0;
                    pk.buffer = connect.buffer;
                    addToQueue(pk, JRakLib.PRIORITY_IMMEDIATE);
                    sendPing();
                    state = STATE_CONNECTING_3;
                }
            }
        }
    }

    protected void onShutdown() {
        try {
            close();
        } catch (IOException e) {
            manager.getClient().getLogger().critical("Could not close connection to server, IOException: "+e.getMessage());
            e.printStackTrace();
        }
    }

    public void close() throws IOException {
        byte[] data = new byte[] {0x00, 0x00, 0x08, 0x15};
        addEncapsulatedToQueue(EncapsulatedPacket.fromBinary(data), JRakLib.PRIORITY_IMMEDIATE);
        sendQueue();
        manager = null;
    }
}
