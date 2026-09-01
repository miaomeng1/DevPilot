package com.devpilot.server.metric.service;

import com.devpilot.server.metric.dto.MetricPointResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MetricCache {

    private static final Duration RAW_RETENTION = Duration.ofHours(1);
    private static final Duration KEY_TTL = Duration.ofHours(2);
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public MetricCache(ObjectProvider<StringRedisTemplate> redisProvider, ObjectMapper objectMapper) {
        this.redis = redisProvider.getIfAvailable();
        this.objectMapper = objectMapper;
    }

    public void append(Long serverId, MetricPointResponse point) {
        if (redis == null) {
            return;
        }
        String key = key(serverId);
        long timestamp = point.timestamp().toInstant(ZoneOffset.UTC).toEpochMilli();
        try {
            redis.opsForZSet().add(key, objectMapper.writeValueAsString(point), timestamp);
            redis.opsForZSet().removeRangeByScore(key, 0,
                    timestamp - RAW_RETENTION.toMillis() - 1);
            redis.expire(key, KEY_TTL);
        } catch (RuntimeException | JsonProcessingException exception) {
            log.warn("Unable to cache raw metrics for server {}: {}", serverId, exception.getMessage());
        }
    }

    public List<MetricPointResponse> recent(Long serverId, LocalDateTime from) {
        if (redis == null) {
            return List.of();
        }
        try {
            Set<String> entries = redis.opsForZSet().rangeByScore(key(serverId),
                    from.toInstant(ZoneOffset.UTC).toEpochMilli(), Double.POSITIVE_INFINITY);
            if (entries == null || entries.isEmpty()) {
                return List.of();
            }
            List<MetricPointResponse> points = new ArrayList<>(entries.size());
            for (String entry : entries) {
                points.add(objectMapper.readValue(entry, MetricPointResponse.class));
            }
            points.sort((left, right) -> left.timestamp().compareTo(right.timestamp()));
            return Collections.unmodifiableList(points);
        } catch (RuntimeException | JsonProcessingException exception) {
            log.warn("Unable to read raw metrics for server {}: {}", serverId, exception.getMessage());
            return List.of();
        }
    }

    public MetricPointResponse latest(Long serverId) {
        if (redis == null) {
            return null;
        }
        try {
            Set<String> entries = redis.opsForZSet().reverseRange(key(serverId), 0, 0);
            if (entries == null || entries.isEmpty()) {
                return null;
            }
            return objectMapper.readValue(entries.iterator().next(), MetricPointResponse.class);
        } catch (RuntimeException | JsonProcessingException exception) {
            log.warn("Unable to read latest raw metric for server {}: {}", serverId, exception.getMessage());
            return null;
        }
    }

    private static String key(Long serverId) {
        return "devpilot:metrics:server:" + serverId;
    }
}
