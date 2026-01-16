package com.leo.dfss;

import com.google.gson.Gson;
import com.leo.dfss.protocol.Message;
import com.leo.dfss.protocol.NodeHeartbeat;
import com.leo.dfss.protocol.NodeRegisterRequest;
import com.leo.dfss.transport.ReceivedMessage;
import com.leo.dfss.transport.TcpMessageReader;
import com.leo.dfss.transport.TcpMessageWriter;

import java.net.Socket;
import java.util.UUID;

public class NodeTestClient {

    private static final Gson gson = new Gson();

    private static final String HOST = "localhost";
    private static final int COORDINATOR_PORT = 9000;

    // This is the port your NodeServer will later listen on for chunk uploads/downloads
    private static final int NODE_DATA_PORT = 9100;

    public static void main(String[] args) {
        String nodeId = "node-" + UUID.randomUUID();

        try (Socket socket = new Socket(HOST, COORDINATOR_PORT)) {
            TcpMessageReader reader = new TcpMessageReader(socket.getInputStream());
            TcpMessageWriter writer = new TcpMessageWriter(socket.getOutputStream());

            // 1) Read WELCOME (server-initiated)
            printResponse(reader);

            // 2) Send NODE_REGISTER
            NodeRegisterRequest reg = new NodeRegisterRequest();
            reg.setNodeId(nodeId);
            reg.setHost(HOST);
            reg.setPort(NODE_DATA_PORT);
            reg.setCapacityBytes(50_000_000_000L); // 50 GB (example)

            writer.send(new Message("NODE_REGISTER", gson.toJson(reg)), null);
            printResponse(reader);

            // 3) Heartbeat loop (every 5 seconds)
            while (true) {
                NodeHeartbeat hb = new NodeHeartbeat();
                hb.setNodeId(nodeId);
                hb.setTimestampEpochMs(System.currentTimeMillis());
                hb.setFreeBytes(40_000_000_000L); // example remaining

                writer.send(new Message("NODE_HEARTBEAT", gson.toJson(hb)), null);
                printResponse(reader);

                Thread.sleep(5000);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void printResponse(TcpMessageReader reader) throws Exception {
        ReceivedMessage rm = reader.read();
        if (rm == null) {
            System.out.println("Coordinator closed connection.");
            return;
        }

        Message h = rm.getHeader();
        System.out.println("Response: type=" + h.getType() + ", data=" + h.getData());
    }
}