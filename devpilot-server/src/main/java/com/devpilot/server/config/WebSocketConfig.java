package com.devpilot.server.config;

import com.devpilot.server.docker.websocket.AgentLogWebSocketHandler;
import com.devpilot.server.docker.websocket.BrowserLogWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final BrowserLogWebSocketHandler browserHandler;
    private final AgentLogWebSocketHandler agentHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(browserHandler, "/ws/logs").setAllowedOriginPatterns("*");
        registry.addHandler(agentHandler, "/ws/agent/logs").setAllowedOriginPatterns("*");
    }
}
