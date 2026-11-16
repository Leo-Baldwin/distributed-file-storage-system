package com.leo.dfss.coordinator;

import com.leo.dfss.config.CoordinatorConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PlacementTest {

    @TempDir
    Path tempDir;

    private CoordinatorServer coordinator() {
        return new CoordinatorServer(CoordinatorConfig.of(0, tempDir));
    }

    private static void node(CoordinatorServer c, String id, String domain, long freeBytes) {
        assertTrue(c.registerNode(id, "127.0.0.1", 9101, 1_000_000, domain));
        c.handleHeartbeat(id, System.currentTimeMillis(), freeBytes);
    }

    @Test
    void neverPlacesTwoCopiesInTheSameDomain() {
        CoordinatorServer c = coordinator();
        node(c, "a1", "host-a", 900);
        node(c, "a2", "host-a", 800);
        node(c, "b1", "host-b", 100);

        List<NodeInfo> chosen = c.selectPlacement(3);

        Set<String> domains = chosen.stream().map(NodeInfo::getFailureDomain).collect(Collectors.toSet());
        assertEquals(2, chosen.size(), "only 2 domains exist, so only 2 copies can be placed safely");
        assertEquals(Set.of("host-a", "host-b"), domains);
        assertTrue(chosen.stream().anyMatch(n -> n.getNodeId().equals("a1")), "picks the emptiest node in a domain");
        c.shutdown();
    }

    @Test
    void prefersDomainsWithTheMostFreeSpace() {
        CoordinatorServer c = coordinator();
        node(c, "small", "d1", 10);
        node(c, "large", "d2", 1000);
        node(c, "medium", "d3", 500);

        assertEquals(List.of("large", "medium"),
                c.selectPlacement(2).stream().map(NodeInfo::getNodeId).toList());
        c.shutdown();
    }

    @Test
    void downNodesAreNotChosen() {
        CoordinatorServer c = coordinator();
        node(c, "up", "d1", 10);
        node(c, "down", "d2", 1000);
        c.getNodes().get("down").markDown();

        assertEquals(List.of("up"), c.selectPlacement(3).stream().map(NodeInfo::getNodeId).toList());
        c.shutdown();
    }

    @Test
    void uploadPlan_warnsWhenFewerDomainsThanCopies() {
        CoordinatorServer c = coordinator();
        node(c, "only", "d1", 10);

        CoordinatorServer.UploadPlan plan = c.initFileUpload("x.bin", 10, 4);
        assertEquals(1, plan.targets().size());
        assertNotNull(plan.warning());
        c.shutdown();
    }
}
