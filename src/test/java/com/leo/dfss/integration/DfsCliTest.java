package com.leo.dfss.integration;

import com.leo.dfss.cli.Dfs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DfsCliTest {

    @TempDir
    Path tempDir;

    @Test
    void putThenGet_throughTheCli() throws Exception {
        try (TestCluster cluster = TestCluster.start(tempDir.resolve("cluster"), 2)) {
            String coordinator = "localhost:" + cluster.coordinator.getPort();

            byte[] bytes = new byte[300_000];
            new Random(1).nextBytes(bytes);
            Path input = Files.write(tempDir.resolve("notes.bin"), bytes);

            String out = captureStdout(() -> assertEquals(0,
                    Dfs.run("put", "--coordinator", coordinator, "--chunk-size-kib", "64", input.toString())));
            // The in-process servers also log to stdout here, so find the "FILE_ID  NAME" result line
            var m = java.util.regex.Pattern.compile("(?m)^([0-9a-f-]{36})  notes\\.bin$").matcher(out);
            assertTrue(m.find(), "expected a 'FILE_ID  notes.bin' line, stdout was: " + out);
            String fileId = m.group(1);

            Path outDir = tempDir.resolve("restored");
            assertEquals(0, Dfs.run("get", "--coordinator", coordinator, "-o", outDir.toString(), fileId));
            assertArrayEquals(bytes, Files.readAllBytes(outDir.resolve("notes.bin")));
        }
    }

    @Test
    void badInput_givesNonZeroExitCode() {
        assertNotEquals(0, Dfs.run("put", "--coordinator", "no-port-here", "x"));
        assertNotEquals(0, Dfs.run("get"));          // missing FILE_ID
        assertNotEquals(0, Dfs.run("no-such-command"));
    }

    private static String captureStdout(Runnable r) {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            r.run();
        } finally {
            System.setOut(original);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}
