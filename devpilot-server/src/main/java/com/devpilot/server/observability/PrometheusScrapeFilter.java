package com.devpilot.server.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class PrometheusScrapeFilter extends OncePerRequestFilter {

    private static final String PATH = "/actuator/prometheus";
    private static final String BEARER = "Bearer ";
    private final ObservabilityProperties properties;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!properties.prometheusEnabled()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String supplied = bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (supplied == null) supplied = request.getHeader("X-DevPilot-Metrics-Key");
        if (!constantTimeEquals(properties.prometheusScrapeToken(), supplied)) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"DevPilot metrics\"");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static String bearerToken(String header) {
        return header != null && header.startsWith(BEARER) ? header.substring(BEARER.length()) : null;
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        if (expected == null || supplied == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
}
