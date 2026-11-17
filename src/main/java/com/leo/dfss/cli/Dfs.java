package com.leo.dfss.cli;

import com.leo.dfss.client.DownloadOrchestratorClient;
import com.leo.dfss.client.UploadOrchestratorClient;
import com.leo.dfss.config.ClientConfig;
import com.leo.dfss.config.CoordinatorConfig;
import com.leo.dfss.config.NodeConfig;
import com.leo.dfss.coordinator.CoordinatorServer;
import com.leo.dfss.node.NodeServer;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * The single {@code dfs} command-line entry point.
 *
 * Every option can also be set with an environment variable (shown in --help); a flag on the
 * command line wins over the environment variable, which wins over the built-in default.
 */
@Command(name = "dfs",
        mixinStandardHelpOptions = true,
        version = "dfs 1.1-SNAPSHOT",
        description = "Distributed, replicated file storage.",
        subcommands = {
                Dfs.Coordinator.class,
                Dfs.Node.class,
                Dfs.Put.class,
                Dfs.Get.class,
                CommandLine.HelpCommand.class
        })
public final class Dfs {

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** Runs the CLI and returns the exit code (separate from main so it can be tested). */
    public static int run(String... args) {
        return new CommandLine(new Dfs())
                .setExecutionExceptionHandler((e, cmd, parsed) -> {
                    cmd.getErr().println("error: " + rootMessage(e));
                    return 1;
                })
                .execute(args);
    }

    private static String rootMessage(Throwable e) {
        String message = e.getMessage();
        for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
            if (t.getMessage() != null) message = t.getMessage();
        }
        return message;
    }

    /** "host:port" parsing shared by the commands that talk to a Coordinator. */
    static final class CoordinatorAddress {
        final String host;
        final int port;

        CoordinatorAddress(String value) {
            int colon = value.lastIndexOf(':');
            if (colon <= 0 || colon == value.length() - 1) {
                throw new CommandLine.TypeConversionException("expected HOST:PORT but was '" + value + "'");
            }
            this.host = value.substring(0, colon);
            try {
                this.port = Integer.parseInt(value.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new CommandLine.TypeConversionException("invalid port in '" + value + "'");
            }
        }
    }

    @Command(name = "coordinator", mixinStandardHelpOptions = true,
            description = "Run the Coordinator (tracks files and nodes; never stores file data).")
    static final class Coordinator implements Callable<Integer> {

        @Option(names = "--port", description = "Port to listen on (env DFS_PORT, default ${DEFAULT-VALUE}).",
                defaultValue = "${env:DFS_PORT:-9000}")
        int port;

        @Option(names = "--data-dir",
                description = "Where metadata is stored (env DFS_DATA_DIR, default ${DEFAULT-VALUE}).",
                defaultValue = "${env:DFS_DATA_DIR:-coordinator-data}")
        Path dataDir;

        @Option(names = "--replication-factor",
                description = "Copies of each file (env DFS_REPLICATION_FACTOR, default ${DEFAULT-VALUE}).",
                defaultValue = "${env:DFS_REPLICATION_FACTOR:-3}")
        int replicationFactor;

        @Override
        public Integer call() {
            new CoordinatorServer(new CoordinatorConfig(port, dataDir, replicationFactor,
                    CoordinatorConfig.DEFAULT_HEARTBEAT_TIMEOUT_MS,
                    CoordinatorConfig.DEFAULT_SWEEP_INTERVAL_MS)).start();
            return 0;
        }
    }

    @Command(name = "node", mixinStandardHelpOptions = true,
            description = "Run a storage node.")
    static final class Node implements Callable<Integer> {

        @Option(names = "--port", description = "Port for chunk traffic (env DFS_PORT, default ${DEFAULT-VALUE}).",
                defaultValue = "${env:DFS_PORT:-9100}")
        int port;

        @Option(names = "--data-dir", description = "Where chunks are stored (env DFS_DATA_DIR, default ${DEFAULT-VALUE}).",
                defaultValue = "${env:DFS_DATA_DIR:-node-data}")
        Path dataDir;

        @Option(names = "--coordinator", paramLabel = "HOST:PORT",
                description = "Coordinator address (env DFS_COORDINATOR, default ${DEFAULT-VALUE}).",
                defaultValue = "${env:DFS_COORDINATOR:-localhost:9000}")
        String coordinator;

        @Option(names = "--advertise-host",
                description = "Address clients should use to reach this node (env DFS_ADVERTISE_HOST). "
                        + "Default: the address this node connects to the Coordinator from.",
                defaultValue = "${env:DFS_ADVERTISE_HOST}")
        String advertiseHost;

        @Option(names = "--failure-domain",
                description = "What this node fails together with, e.g. the machine name "
                        + "(env DFS_FAILURE_DOMAIN). Default: this machine's host name.",
                defaultValue = "${env:DFS_FAILURE_DOMAIN}")
        String failureDomain;

        @Override
        public Integer call() {
            CoordinatorAddress c = new CoordinatorAddress(coordinator);
            new NodeServer(new NodeConfig(port, dataDir, advertiseHost, c.host, c.port, failureDomain,
                    NodeConfig.DEFAULT_HEARTBEAT_INTERVAL_MS)).start();
            return 0;
        }
    }

    /** Options shared by the client commands. */
    static class ClientOptions {
        @Option(names = "--coordinator", paramLabel = "HOST:PORT",
                description = "Coordinator address (env DFS_COORDINATOR, default ${DEFAULT-VALUE}).",
                defaultValue = "${env:DFS_COORDINATOR:-localhost:9000}")
        String coordinator;

        @Option(names = "--chunk-size-kib",
                description = "Chunk size in KiB for uploads (env DFS_CHUNK_SIZE_KIB, default ${DEFAULT-VALUE}).",
                defaultValue = "${env:DFS_CHUNK_SIZE_KIB:-4096}")
        int chunkSizeKib;

        ClientConfig toConfig() {
            CoordinatorAddress c = new CoordinatorAddress(coordinator);
            return new ClientConfig(c.host, c.port, Math.multiplyExact(chunkSizeKib, 1024));
        }
    }

    @Command(name = "put", mixinStandardHelpOptions = true,
            description = "Upload files. Prints one 'FILE_ID  NAME' line per file.")
    static final class Put extends ClientOptions implements Callable<Integer> {

        @Parameters(paramLabel = "FILE", arity = "1..*", description = "Files to upload.")
        List<Path> files;

        @Override
        public Integer call() {
            UploadOrchestratorClient client = new UploadOrchestratorClient(toConfig());
            for (Path file : files) {
                String fileId = client.uploadFile(file);
                System.out.println(fileId + "  " + file.getFileName());
            }
            return 0;
        }
    }

    @Command(name = "get", mixinStandardHelpOptions = true,
            description = "Download a file by its ID.")
    static final class Get extends ClientOptions implements Callable<Integer> {

        @Parameters(paramLabel = "FILE_ID", description = "ID printed by 'dfs put'.")
        String fileId;

        @Option(names = {"-o", "--output-dir"}, description = "Directory to write into (default: current directory).",
                defaultValue = ".")
        Path outputDir;

        @Override
        public Integer call() throws Exception {
            new DownloadOrchestratorClient(toConfig()).downloadFile(fileId, outputDir);
            return 0;
        }
    }
}
