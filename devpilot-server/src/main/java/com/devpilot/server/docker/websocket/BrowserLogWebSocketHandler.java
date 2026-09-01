package com.devpilot.server.docker.websocket;

import com.devpilot.server.docker.service.LogRelayService;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
public class BrowserLogWebSocketHandler extends TextWebSocketHandler {

    private final LogRelayService relayService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String ticket = query(session.getUri(), "ticket");
        relayService.attachBrowser(ticket, session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        relayService.browserClosed(session);
    }

    private static String query(URI uri, String name) {
        if (uri == null) {
            return null;
        }
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(name);
    }
}
