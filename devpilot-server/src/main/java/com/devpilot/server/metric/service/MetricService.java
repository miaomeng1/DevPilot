package com.devpilot.server.metric.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.devpilot.server.agent.dto.AgentHeartbeatRequest;
import com.devpilot.server.agent.dto.AgentHeartbeatResponse;
import com.devpilot.server.agent.service.AgentRegistrationService;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.metric.dto.AgentMetricRequest;
import com.devpilot.server.metric.dto.MetricHistoryResponse;
import com.devpilot.server.metric.dto.MetricIngestResponse;
import com.devpilot.server.metric.dto.MetricPointResponse;
import com.devpilot.server.metric.entity.ServerMetricEntity;
import com.devpilot.server.metric.mapper.ServerMetricMapper;
import com.devpilot.server.node.service.ServerNodeService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MetricService {

    private static final int MYSQL_RETENTION_DAYS = 7;
    private final AgentRegistrationService registrationService;
    private final ServerNodeService serverNodeService;
    private final ServerMetricMapper metricMapper;
    private final MetricCache metricCache;

    @Transactional
    public MetricIngestResponse ingest(String rawToken, AgentMetricRequest request) {
        validateFiniteValues(request);
        AgentHeartbeatResponse heartbeat = registrationService.heartbeat(rawToken,
                new AgentHeartbeatRequest(request.agentVersion()));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime collectedAt = LocalDateTime.ofInstant(request.collectedAt(), ZoneOffset.UTC);
        if (request.collectedAt().isAfter(Instant.now().plus(5, ChronoUnit.MINUTES))
                || request.collectedAt().isBefore(Instant.now().minus(1, ChronoUnit.HOURS))) {
            throw BusinessException.badRequest(40002, "指标时间与控制平面时间偏差过大");
        }

        MetricPointResponse rawPoint = fromRequest(request, collectedAt);
        metricCache.append(heartbeat.serverId(), rawPoint);
        aggregateMinute(heartbeat.serverId(), request, collectedAt.truncatedTo(ChronoUnit.MINUTES), now);
        return new MetricIngestResponse(heartbeat.serverId(), now.toInstant(ZoneOffset.UTC));
    }

    public MetricHistoryResponse history(Long serverId, String rangeValue) {
        serverNodeService.get(serverId);
        MetricRange range = MetricRange.parse(rangeValue);
        LocalDateTime from = LocalDateTime.now(ZoneOffset.UTC).minus(range.duration());
        List<MetricPointResponse> points;
        int resolution = range.resolutionSeconds();
        if (range == MetricRange.ONE_HOUR) {
            points = metricCache.recent(serverId, from);
            if (points.isEmpty()) {
                points = metricMapper.selectSince(serverId, from).stream().map(MetricService::fromEntity).toList();
                resolution = 60;
            }
        } else {
            List<MetricPointResponse> minutePoints = metricMapper.selectSince(serverId, from).stream()
                    .map(MetricService::fromEntity).toList();
            points = resolution == 300 ? downsample(minutePoints, 300) : minutePoints;
        }
        MetricPointResponse current = points.isEmpty() ? latest(serverId) : points.get(points.size() - 1);
        return new MetricHistoryResponse(serverId.toString(), range.value(), resolution, current, points);
    }

    public List<MetricPointResponse> globalTrend(MetricRange range) {
        LocalDateTime from = LocalDateTime.now(ZoneOffset.UTC).minus(range.duration());
        List<MetricPointResponse> points = metricMapper.selectAllSince(from).stream()
                .map(MetricService::fromEntity).toList();
        return downsample(points, range == MetricRange.SEVEN_DAYS ? 300 : 60);
    }

    public MetricPointResponse current(Long serverId) {
        MetricPointResponse cached = metricCache.latest(serverId);
        return cached == null ? latest(serverId) : cached;
    }

    @Scheduled(cron = "0 23 * * * *")
    public int purgeExpiredAggregates() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(MYSQL_RETENTION_DAYS);
        return metricMapper.delete(new LambdaQueryWrapper<ServerMetricEntity>()
                .lt(ServerMetricEntity::getCollectedAt, cutoff));
    }

    private MetricPointResponse latest(Long serverId) {
        ServerMetricEntity latest = metricMapper.selectLatest(serverId);
        return latest == null ? null : fromEntity(latest);
    }

    private void aggregateMinute(Long serverId, AgentMetricRequest sample,
                                 LocalDateTime minute, LocalDateTime now) {
        ServerMetricEntity existing = metricMapper.selectOne(new LambdaQueryWrapper<ServerMetricEntity>()
                .eq(ServerMetricEntity::getServerId, serverId)
                .eq(ServerMetricEntity::getCollectedAt, minute));
        if (existing == null) {
            metricMapper.insert(toEntity(serverId, sample, minute, now));
            return;
        }
        int count = existing.getSampleCount();
        existing.setCpuUsage(mean(existing.getCpuUsage(), sample.cpuUsage(), count));
        existing.setLoadOne(mean(existing.getLoadOne(), sample.loadOne(), count));
        existing.setLoadFive(mean(existing.getLoadFive(), sample.loadFive(), count));
        existing.setLoadFifteen(mean(existing.getLoadFifteen(), sample.loadFifteen(), count));
        existing.setMemoryTotal(sample.memoryTotal());
        existing.setMemoryUsed(mean(existing.getMemoryUsed(), sample.memoryUsed(), count));
        existing.setMemoryAvailable(mean(existing.getMemoryAvailable(), sample.memoryAvailable(), count));
        existing.setDiskTotal(sample.diskTotal());
        existing.setDiskUsed(mean(existing.getDiskUsed(), sample.diskUsed(), count));
        existing.setDiskFree(mean(existing.getDiskFree(), sample.diskFree(), count));
        existing.setNetworkBytesSent(sample.networkBytesSent());
        existing.setNetworkBytesReceived(sample.networkBytesReceived());
        existing.setNetworkUploadRate(mean(existing.getNetworkUploadRate(), sample.networkUploadRate(), count));
        existing.setNetworkDownloadRate(mean(existing.getNetworkDownloadRate(), sample.networkDownloadRate(), count));
        existing.setSampleCount(count + 1);
        existing.setUpdatedAt(now);
        metricMapper.updateById(existing);
    }

    private static ServerMetricEntity toEntity(Long serverId, AgentMetricRequest sample,
                                                LocalDateTime minute, LocalDateTime now) {
        ServerMetricEntity entity = new ServerMetricEntity();
        entity.setServerId(serverId);
        entity.setCollectedAt(minute);
        entity.setSampleCount(1);
        entity.setCpuUsage(sample.cpuUsage());
        entity.setLoadOne(sample.loadOne());
        entity.setLoadFive(sample.loadFive());
        entity.setLoadFifteen(sample.loadFifteen());
        entity.setMemoryTotal(sample.memoryTotal());
        entity.setMemoryUsed(sample.memoryUsed());
        entity.setMemoryAvailable(sample.memoryAvailable());
        entity.setDiskTotal(sample.diskTotal());
        entity.setDiskUsed(sample.diskUsed());
        entity.setDiskFree(sample.diskFree());
        entity.setNetworkBytesSent(sample.networkBytesSent());
        entity.setNetworkBytesReceived(sample.networkBytesReceived());
        entity.setNetworkUploadRate(sample.networkUploadRate());
        entity.setNetworkDownloadRate(sample.networkDownloadRate());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    public static MetricPointResponse fromEntity(ServerMetricEntity entity) {
        return point(entity.getCollectedAt(), entity.getCpuUsage(), entity.getLoadOne(), entity.getLoadFive(),
                entity.getLoadFifteen(), entity.getMemoryTotal(), entity.getMemoryUsed(), entity.getMemoryAvailable(),
                entity.getDiskTotal(), entity.getDiskUsed(), entity.getDiskFree(), entity.getNetworkBytesSent(),
                entity.getNetworkBytesReceived(), entity.getNetworkUploadRate(), entity.getNetworkDownloadRate());
    }

    private static MetricPointResponse fromRequest(AgentMetricRequest request, LocalDateTime collectedAt) {
        return point(collectedAt, request.cpuUsage(), request.loadOne(), request.loadFive(), request.loadFifteen(),
                request.memoryTotal(), request.memoryUsed(), request.memoryAvailable(), request.diskTotal(),
                request.diskUsed(), request.diskFree(), request.networkBytesSent(), request.networkBytesReceived(),
                request.networkUploadRate(), request.networkDownloadRate());
    }

    private static MetricPointResponse point(LocalDateTime timestamp, Double cpu, Double loadOne, Double loadFive,
                                             Double loadFifteen, Long memoryTotal, Long memoryUsed,
                                             Long memoryAvailable, Long diskTotal, Long diskUsed, Long diskFree,
                                             Long networkSent, Long networkReceived, Double upload, Double download) {
        return new MetricPointResponse(timestamp, round(cpu), round(loadOne), round(loadFive), round(loadFifteen),
                memoryTotal, memoryUsed, memoryAvailable, percentage(memoryUsed, memoryTotal), diskTotal, diskUsed,
                diskFree, percentage(diskUsed, diskTotal), networkSent, networkReceived, round(upload), round(download));
    }

    private static List<MetricPointResponse> downsample(List<MetricPointResponse> points, int seconds) {
        if (points.isEmpty()) {
            return List.of();
        }
        Map<LocalDateTime, PointAccumulator> buckets = new LinkedHashMap<>();
        for (MetricPointResponse point : points) {
            long epoch = point.timestamp().toEpochSecond(ZoneOffset.UTC);
            LocalDateTime bucket = LocalDateTime.ofEpochSecond(epoch - Math.floorMod(epoch, seconds), 0,
                    ZoneOffset.UTC);
            buckets.computeIfAbsent(bucket, ignored -> new PointAccumulator()).add(point);
        }
        return buckets.entrySet().stream().map(entry -> entry.getValue().finish(entry.getKey())).toList();
    }

    private static void validateFiniteValues(AgentMetricRequest request) {
        List<Double> values = List.of(request.cpuUsage(), request.loadOne(), request.loadFive(),
                request.loadFifteen(), request.networkUploadRate(), request.networkDownloadRate());
        if (values.stream().anyMatch(value -> !Double.isFinite(value))) {
            throw BusinessException.badRequest(40001, "指标必须为有限数值");
        }
        if (request.memoryUsed() > request.memoryTotal() || request.memoryAvailable() > request.memoryTotal()
                || request.diskUsed() > request.diskTotal() || request.diskFree() > request.diskTotal()) {
            throw BusinessException.badRequest(40001, "指标使用量不能大于总量");
        }
    }

    private static double mean(double current, double next, int currentCount) {
        return (current * currentCount + next) / (currentCount + 1);
    }

    private static long mean(long current, long next, int currentCount) {
        return Math.round(((double) current * currentCount + next) / (currentCount + 1));
    }

    private static Double percentage(Long used, Long total) {
        return total == null || total == 0 ? 0.0 : round(used.doubleValue() * 100.0 / total.doubleValue());
    }

    private static Double round(Double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static final class PointAccumulator {
        private final List<MetricPointResponse> points = new ArrayList<>();

        void add(MetricPointResponse point) {
            points.add(point);
        }

        MetricPointResponse finish(LocalDateTime timestamp) {
            MetricPointResponse latest = points.get(points.size() - 1);
            double count = points.size();
            return new MetricPointResponse(timestamp,
                    round(points.stream().mapToDouble(MetricPointResponse::cpuUsage).sum() / count),
                    round(points.stream().mapToDouble(MetricPointResponse::loadOne).sum() / count),
                    round(points.stream().mapToDouble(MetricPointResponse::loadFive).sum() / count),
                    round(points.stream().mapToDouble(MetricPointResponse::loadFifteen).sum() / count),
                    latest.memoryTotal(),
                    Math.round(points.stream().mapToLong(MetricPointResponse::memoryUsed).average().orElse(0)),
                    Math.round(points.stream().mapToLong(MetricPointResponse::memoryAvailable).average().orElse(0)),
                    round(points.stream().mapToDouble(MetricPointResponse::memoryUsage).sum() / count),
                    latest.diskTotal(),
                    Math.round(points.stream().mapToLong(MetricPointResponse::diskUsed).average().orElse(0)),
                    Math.round(points.stream().mapToLong(MetricPointResponse::diskFree).average().orElse(0)),
                    round(points.stream().mapToDouble(MetricPointResponse::diskUsage).sum() / count),
                    latest.networkBytesSent(), latest.networkBytesReceived(),
                    round(points.stream().mapToDouble(MetricPointResponse::networkUploadRate).sum() / count),
                    round(points.stream().mapToDouble(MetricPointResponse::networkDownloadRate).sum() / count));
        }
    }
}
