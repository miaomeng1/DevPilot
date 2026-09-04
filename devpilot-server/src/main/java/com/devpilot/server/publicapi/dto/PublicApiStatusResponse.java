package com.devpilot.server.publicapi.dto;

import java.time.Instant;

public record PublicApiStatusResponse(String apiVersion, Instant generatedAt, long serversManaged,
                                      long serversOnline, long containersDiscovered, long containersRunning,
                                      long applicationsManaged, long alertsActive, long alertsCritical) {
}
