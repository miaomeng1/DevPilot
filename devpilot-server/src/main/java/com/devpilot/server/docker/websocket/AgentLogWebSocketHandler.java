package com.devpilot.server.docker.websocket;

import com.devpilot.server.agent.controller.AgentController;
import com.devpilot.server.agent.service.AgentRegistrationService;
import com.devpilot.server.docker.service.LogRelayService;
import com.devpilot.server.exception.BusinessException;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
public class AgentLogWebSocketHandler extends TextWebSocketHandler {

    private static final int MAX_LOG_MESSAGE_CHARS = 65_536;
    private final AgentRegistrationService registrationService;
    private final LogRelayService relayService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String token = session.getHandshakeHeaders().getFirst(AgentController.AGENT_TOKEN_HEADER);
        String logSessionId = query(session.getUri(), "sessionId");
        try {
            Long serverId = registrationService.authenticateActive(token);
            session.getAttributes().put("logSessionId", logSessionId);
            relayService.attachAgent(logSessionId, serverId, session);
        } catch (BusinessException exception) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Agent authentication failed"));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String logSessionId = (String) session.getAttributes().get("logSessionId");
        String payload = message.getPayload();
        if (payload.length() > MAX_LOG_MESSAGE_CHARS) {
            payload = payload.substring(0, MAX_LOG_MESSAGE_CHARS) + "\u2026";
        }
        relayService.forward(logSessionId, payload);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        relayService.agentClosed(session);
    }

    private static String query(URI uri, String name) {
        if (uri == null) {
            return null;
        }
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(name);
    }
}
