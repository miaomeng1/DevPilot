package com.devpilot.server.cicd.onboarding;

import com.devpilot.server.node.entity.ServerNodeEntity;
import com.devpilot.server.node.mapper.ServerNodeMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HostPortPreflight {
    private final ServerNodeMapper servers;
    private final com.devpilot.server.docker.mapper.DockerContainerSnapshotMapper containers;

    public void check(Long serverId, int port) {
        checkEvidence(servers.selectActiveById(serverId), port, LocalDateTime.now(ZoneOffset.UTC));
        for (var container : containers.selectActive(serverId)) {
            if ("running".equals(container.getState()) && hasPublishedPort(container.getPortsJson(), port)) {
                throw new IllegalArgumentException("目标服务器 TCP 端口 " + port + " 已有 Docker 发布映射，请选择其他端口");
            }
        }
    }

    static boolean hasPublishedPort(String portsJson, int port) {
        if (portsJson == null || portsJson.isBlank()) return false;
        try {
            var ports = new com.fasterxml.jackson.databind.ObjectMapper().readTree(portsJson);
            if (!ports.isArray()) throw new IllegalArgumentException("Docker 端口数据格式异常，请等待 Agent 重新采集");
            var published = java.util.regex.Pattern.compile(":" + port + "→[0-9]+/tcp$");
            for (var value : ports) if (published.matcher(value.asText()).find()) return true;
            return false;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("Docker 端口数据格式异常，请等待 Agent 重新采集");
        }
    }

    static void checkEvidence(ServerNodeEntity server, int port, LocalDateTime now) {
        if (server == null || !"ONLINE".equals(server.getAgentStatus()) || server.getPortsCollectedAt() == null
                || server.getPortsCollectedAt().isBefore(now.minusSeconds(30)) || server.getListeningTcpPorts() == null) {
            throw new IllegalArgumentException("缺少目标服务器最近 30 秒的端口采集；请升级并在宿主机网络中运行 Agent，等待心跳后重试");
        }
        if (Arrays.asList(server.getListeningTcpPorts().split(",")).contains(String.valueOf(port))) {
            throw new IllegalArgumentException("目标服务器 TCP 端口 " + port + " 已被进程监听，请选择其他端口");
        }
    }
}
