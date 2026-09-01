package com.devpilot.server.node.dto;

import com.devpilot.server.node.entity.ServerNodeEntity;
import java.time.LocalDateTime;

public record ServerNodeResponse(
        Long id,
        String name,
        String hostname,
        String ip,
        String os,
        String kernel,
        String architecture,
        String cpuModel,
        Integer cpuCores,
        Long memoryTotal,
        Long diskTotal,
        String agentVersion,
        String status,
        LocalDateTime lastHeartbeat,
        LocalDateTime registeredAt,
        LocalDateTime createdAt) {

    public static ServerNodeResponse from(ServerNodeEntity entity) {
        return new ServerNodeResponse(entity.getId(), entity.getName(), entity.getHostname(), entity.getIp(),
                entity.getOs(), entity.getKernel(), entity.getArchitecture(), entity.getCpuModel(),
                entity.getCpuCores(), entity.getMemoryTotal(), entity.getDiskTotal(), entity.getAgentVersion(),
                entity.getAgentStatus(), entity.getLastHeartbeat(), entity.getRegisteredAt(), entity.getCreatedAt());
    }
}

