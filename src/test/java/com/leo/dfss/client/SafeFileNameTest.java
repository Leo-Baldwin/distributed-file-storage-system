package com.leo.dfss.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SafeFileNameTest {

    @Test
    void plainNames_areAccepted() {
        assertEquals("photo.jpg", DownloadOrchestratorClient.safeFileName("photo.jpg"));
        assertEquals("..hidden", DownloadOrchestratorClient.safeFileName("..hidden"));
    }

    @Test
    void namesThatChooseAPath_areRejected() {
        for (String bad : new String[]{"../../.zshrc", "/etc/passwd", "a/b", "..\\evil", ".", "..", "", " ", "x\0y"}) {
            assertThrows(IllegalArgumentException.class, () -> DownloadOrchestratorClient.safeFileName(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> DownloadOrchestratorClient.safeFileName(null));
    }
}
