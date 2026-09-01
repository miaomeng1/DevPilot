package com.devpilot.server.docker.service;

import com.devpilot.server.docker.dto.AgentDockerCommandResponse;
import com.devpilot.server.docker.dto.LogTicketResponse;
import com.devpilot.server.docker.entity.DockerContainerSnapshotEntity;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Service
@RequiredArgsConstructor
public class LogRelayService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TICKET_BYTES = 32;
    private final DockerInventoryService inventoryService;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final Map<String, Relay> relays = new ConcurrentHashMap<>();
    private final Map<Long, ConcurrentLinkedQueue<LogTask>> queues = new ConcurrentHashMap<>();

    public LogTicketResponse createTicket(Long containerSnapshotId, int lines, boolean follow) {
        DockerContainerSnapshotEntity container = inventoryService.requireContainer(containerSnapshotId);
        String ticket = randomTicket();
        String sessionId = UUID.randomUUID().toString();
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1);
        tickets.put(ticket, new Ticket(sessionId, container.getServerId(), container.getContainerId(),
                lines, follow, expiresAt));
        String path = "/ws/logs?ticket=" + URLEncoder.encode(ticket, StandardCharsets.UTF_8);
        return new LogTicketResponse(path, expiresAt);
    }

    public void attachBrowser(String ticketValue, WebSocketSession browser) throws IOException {
        if (ticketValue == null || ticketValue.isBlank()) {
            browser.close(CloseStatus.POLICY_VIOLATION.withReason("Log ticket is required"));
            return;
        }
        Ticket ticket = tickets.remove(ticketValue);
        if (ticket == null || ticket.expiresAt().isBefore(LocalDateTime.now(ZoneOffset.UTC))) {
            browser.close(CloseStatus.POLICY_VIOLATION.withReason("Log ticket invalid or expired"));
            return;
        }
        Relay relay = new Relay(ticket.serverId(), browser);
        relays.put(ticket.sessionId(), relay);
        queues.computeIfAbsent(ticket.serverId(), ignored -> new ConcurrentLinkedQueue<>())
                .offer(new LogTask(ticket.sessionId(), ticket.containerId(), ticket.lines(), ticket.follow()));
    }

    public AgentDockerCommandResponse claim(Long serverId) {
        ConcurrentLinkedQueue<LogTask> queue = queues.get(serverId);
        if (queue == null) {
            return null;
        }
        LogTask task;
        while ((task = queue.poll()) != null) {
            Relay relay = relays.get(task.sessionId());
            if (relay != null && relay.browser().isOpen()) {
                return new AgentDockerCommandResponse(null, task.containerId(), "LOGS", task.sessionId(),
                        task.lines(), task.follow());
            }
        }
        return null;
    }

    public void attachAgent(String sessionId, Long serverId, WebSocketSession agent) throws IOException {
        if (sessionId == null || sessionId.isBlank()) {
            agent.close(CloseStatus.POLICY_VIOLATION.withReason("Log session is required"));
            return;
        }
        Relay relay = relays.get(sessionId);
        if (relay == null || !relay.serverId().equals(serverId) || !relay.browser().isOpen()) {
            agent.close(CloseStatus.POLICY_VIOLATION.withReason("Unknown log session"));
            return;
        }
        relay.agent(agent);
    }

    public void forward(String sessionId, String payload) throws IOException {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IOException("Log session is required");
        }
        Relay relay = relays.get(sessionId);
        if (relay == null || !relay.browser().isOpen()) {
            throw new IOException("Browser log session is closed");
        }
        synchronized (relay.browser()) {
            relay.browser().sendMessage(new TextMessage(payload));
        }
    }

    public void browserClosed(WebSocketSession browser) {
        relays.entrySet().removeIf(entry -> {
            Relay relay = entry.getValue();
            if (!relay.browser().getId().equals(browser.getId())) {
                return false;
            }
            closeQuietly(relay.agent());
            return true;
        });
    }

    public void agentClosed(WebSocketSession agent) {
        relays.entrySet().removeIf(entry -> {
            Relay relay = entry.getValue();
            if (relay.agent() == null || !relay.agent().getId().equals(agent.getId())) {
                return false;
            }
            if (relay.browser().isOpen()) {
                try {
                    relay.browser().sendMessage(new TextMessage("[DevPilot] Log stream closed"));
                    relay.browser().close(CloseStatus.NORMAL);
                } catch (IOException | IllegalStateException ignored) {
                    // Session is already closing.
                }
            }
            return true;
        });
    }

    @Scheduled(fixedDelay = 30_000)
    public void purgeExpired() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        tickets.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        relays.entrySet().removeIf(entry -> !entry.getValue().browser().isOpen());
    }

    private static String randomTicket() {
        byte[] bytes = new byte[TICKET_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void closeQuietly(WebSocketSession session) {
        if (session != null && session.isOpen()) {
            try {
                session.close(CloseStatus.NORMAL);
            } catch (IOException ignored) {
                // Best-effort cleanup.
            }
        }
    }

    private record Ticket(String sessionId, Long serverId, String containerId, int lines,
                          boolean follow, LocalDateTime expiresAt) {
    }

    private record LogTask(String sessionId, String containerId, int lines, boolean follow) {
    }

    private static final class Relay {
        private final Long serverId;
        private final WebSocketSession browser;
        private volatile WebSocketSession agent;

        private Relay(Long serverId, WebSocketSession browser) {
            this.serverId = serverId;
            this.browser = browser;
        }

        Long serverId() { return serverId; }
        WebSocketSession browser() { return browser; }
        WebSocketSession agent() { return agent; }
        void agent(WebSocketSession value) { agent = value; }
    }
}
