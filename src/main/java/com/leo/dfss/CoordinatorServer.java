package com.leo.dfss;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server that accepts multiple connections and gives each one a Connection thread.
 */
public class CoordinatorServer {

    public final int port;

    // Global state tracker: fileId -> FileMetadata
    private final Map<String, FileMetadata> files = new ConcurrentHashMap<>();

    private final CopyOnWriteArrayList<CoordinatorConnection> connections = new CopyOnWriteArrayList<>();

    private volatile boolean running = true;

    public CoordinatorServer(int port) {
        this.port = port;
    }

        public static void main (String[]args){
            new CoordinatorServer(9000).start();
        }

        public void start () {
            System.out.println("Starting CoordinatorServer...");

            // Try-with resources to ensure automatic closure of connection
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                System.out.println("CoordinatorServer listening on port: " + port);

                int nextConnectionId = 1;

                while (running) {
                    // Blocks until client connects
                    Socket socket = serverSocket.accept();
                    System.out.println("Accepted connected from " + socket.getRemoteSocketAddress());

                    CoordinatorConnection connection =
                            new CoordinatorConnection(socket, nextConnectionId++, this);

                    connections.add(connection);
                    connection.start();
                }

            } catch (IOException e) {
                if (running) {
                    e.printStackTrace();
                } else {
                    System.out.println("CoordinatorServer stopped.");
                }
            } finally {
                shutdownAllConnections();
            }
        }

        public void shutdownAllConnections () {
            System.out.println("Shutting down all connections...");
            for (CoordinatorConnection connection : connections) {
                connection.shutdown();
            }
            connections.clear();
        }

        /**
         * Handles the FILES_INIT_REQUEST command. Creates a new file record and returns the metadata.
         *
         * @param filename name of the file
         * @param totalSizeBytes total size of the file
         * @param chunkSizeBytes size of each chunk
         * @return the metadata of the file
         */
        public FileMetadata initFileUpload (String filename, long totalSizeBytes, int chunkSizeBytes) {
            String fileId = UUID.randomUUID().toString();

            FileMetadata metadata = new FileMetadata(fileId, filename, totalSizeBytes, chunkSizeBytes);
            metadata.setStatus(FileMetadata.Status.UPLOADING);

            files.put(fileId, metadata);

            System.out.println("New file record created: " + metadata);
            return metadata;
        }

        /**
         * Handles FILES_COMMIT. Marks file COMPLETE.
         *
         * @param fileId identifier for the file being committed
         * @return true if successfully committed, else false.
         */
        public boolean commitFile (String fileId){
            FileMetadata metadata = files.get(fileId);
            if (metadata == null) {
                return false;
            }

            metadata.setStatus(FileMetadata.Status.COMPLETE);
            System.out.println("Commited file record: " + metadata);
            return true;
        }

        /** Method to lookup metadata of file by fileId.
         *
         * @param fileId identifier of file being retrieved
         * @return the metadata of specified file
         */
        public FileMetadata getFile (String fileId){
            return files.get(fileId);
        }
    }