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

    public void check(Long serverId, int port) {
        checkEvidence(servers.selectActiveById(serverId), port, LocalDateTime.now(ZoneOffset.UTC));
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
