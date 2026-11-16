package com.leo.dfss.client;

import com.google.gson.Gson;
import com.leo.dfss.config.ClientConfig;
import com.leo.dfss.protocol.Message;
import com.leo.dfss.transport.ReceivedMessage;
import com.leo.dfss.transport.TcpMessageReader;
import com.leo.dfss.transport.TcpMessageWriter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * One request/response connection to the Coordinator, shared by the upload and download clients.
 */
final class CoordinatorClient implements AutoCloseable {

    private static final Gson gson = new Gson();
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private final Socket socket;
    private final TcpMessageReader reader;
    private final TcpMessageWriter writer;
    private ReceivedMessage lastResponse;

    private CoordinatorClient(Socket socket) throws IOException {
        this.socket = socket;
        this.reader = new TcpMessageReader(socket.getInputStream());
        this.writer = new TcpMessageWriter(socket.getOutputStream());
    }

    /** Connects and consumes the Coordinator's WELCOME message. */
    static CoordinatorClient connect(ClientConfig config) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(config.coordinatorHost(), config.coordinatorPort()), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            CoordinatorClient client = new CoordinatorClient(socket);
            ReceivedMessage welcome = client.reader.read();
            if (welcome == null || !"WELCOME".equals(welcome.getHeader().getType())) {
                throw new IOException("Coordinator did not send WELCOME");
            }
            return client;
        } catch (IOException e) {
            socket.close();
            throw new IOException("Cannot reach Coordinator at " + config.coordinatorHost() + ":"
                    + config.coordinatorPort() + " (" + e.getMessage() + ")", e);
        }
    }

    /**
     * Sends a request and parses the reply.
     *
     * @throws IOException if the connection fails or the reply isn't {@code expectedType}
     *                     (an ERROR reply's text becomes the exception message)
     */
    <T> T request(String type, Object payload, byte[] body, String expectedType, Class<T> responseClass)
            throws IOException {
        writer.send(new Message(type, gson.toJson(payload)), body);
        ReceivedMessage resp = reader.read();
        if (resp == null) {
            throw new IOException("Coordinator closed the connection");
        }
        Message header = resp.getHeader();
        if (!expectedType.equals(header.getType())) {
            throw new IOException("Coordinator error: " + header.getData());
        }
        lastResponse = resp;
        return gson.fromJson(header.getData(), responseClass);
    }

    /** @return the binary body of the last successful response (may be null) */
    byte[] lastBody() {
        return lastResponse == null ? null : lastResponse.getBody();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
