package com.devpilot.server.cicd.onboarding;

import static org.junit.jupiter.api.Assertions.*;
import com.devpilot.server.node.entity.ServerNodeEntity;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class HostPortPreflightTests {
    @Test
    void blocksOccupiedUnknownAndStalePorts() {
        var now = LocalDateTime.of(2026, 9, 5, 12, 0);
        var server = new ServerNodeEntity();
        assertThrows(IllegalArgumentException.class, () -> HostPortPreflight.checkEvidence(server, 8080, now));
        server.setAgentStatus("ONLINE"); server.setPortsCollectedAt(now); server.setListeningTcpPorts("80,8080");
        assertThrows(IllegalArgumentException.class, () -> HostPortPreflight.checkEvidence(server, 8080, now));
        assertDoesNotThrow(() -> HostPortPreflight.checkEvidence(server, 18080, now));
        server.setListeningTcpPorts("");
        assertDoesNotThrow(() -> HostPortPreflight.checkEvidence(server, 8080, now));
        server.setPortsCollectedAt(now.minusSeconds(31));
        assertThrows(IllegalArgumentException.class, () -> HostPortPreflight.checkEvidence(server, 8080, now));
    }
}
