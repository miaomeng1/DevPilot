package com.devpilot.server.capacity.service;

import com.devpilot.server.alert.mapper.AlertEventMapper;
import com.devpilot.server.capacity.dto.CapacityPlanResponse;
import com.devpilot.server.capacity.dto.CapacityServerResponse;
import com.devpilot.server.docker.entity.DockerContainerSnapshotEntity;
import com.devpilot.server.docker.entity.DockerHostSnapshotEntity;
import com.devpilot.server.docker.mapper.DockerContainerSnapshotMapper;
import com.devpilot.server.docker.mapper.DockerHostSnapshotMapper;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.metric.dto.MetricPointResponse;
import com.devpilot.server.metric.service.MetricService;
import com.devpilot.server.node.entity.ServerNodeEntity;
import com.devpilot.server.node.mapper.ServerNodeMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CapacityPlanningService {

    private static final long MIBIBYTE = 1024L * 1024;
    private static final long GIBIBYTE = 1024L * MIBIBYTE;
    private static final long MIN_MEMORY = 128L * MIBIBYTE;
    private static final long MAX_MEMORY = 64L * GIBIBYTE;
    private static final long MIN_DISK = 256L * MIBIBYTE;
    private static final long MAX_DISK = 2L * 1024 * GIBIBYTE;
    private static final long MEMORY_SAFETY = 256L * MIBIBYTE;
    private static final long DISK_SAFETY = 2L * GIBIBYTE;
    private final ServerNodeMapper serverMapper;
    private final MetricService metricService;
    private final DockerHostSnapshotMapper dockerHostMapper;
    private final DockerContainerSnapshotMapper containerMapper;
    private final AlertEventMapper alertMapper;

    public CapacityPlanResponse plan(long requiredMemoryBytes, long requiredDiskBytes) {
        if (requiredMemoryBytes < MIN_MEMORY || requiredMemoryBytes > MAX_MEMORY) {
            throw BusinessException.badRequest(40080, "预计内存必须在 128 MiB 到 64 GiB 之间");
        }
        if (requiredDiskBytes < MIN_DISK || requiredDiskBytes > MAX_DISK) {
            throw BusinessException.badRequest(40081, "预计磁盘必须在 256 MiB 到 2 TiB 之间");
        }
        List<CapacityServerResponse> scored = serverMapper.selectAllActive().stream()
                .map(server -> score(server, requiredMemoryBytes, requiredDiskBytes)).sorted(Comparator
                        .comparing(CapacityServerResponse::eligible).reversed()
                        .thenComparing(CapacityServerResponse::score, Comparator.reverseOrder())
                        .thenComparing(CapacityServerResponse::serverName)).toList();
        CapacityServerResponse best = scored.stream().filter(CapacityServerResponse::eligible).findFirst().orElse(null);
        List<CapacityServerResponse> ranked = scored.stream().map(item -> withRecommendation(item,
                best != null && best.serverId().equals(item.serverId()))).toList();
        String verdict = best == null ? "BLOCKED" : best.score() >= 75 ? "SAFE" : best.score() >= 55 ? "CAUTION" : "TIGHT";
        String summary = best == null ? "当前没有服务器满足这项工作负载的最低安全余量。"
                : "%s 当前最合适，容量评分 %d/100（%s）。".formatted(best.serverName(), best.score(), best.grade());
        return new CapacityPlanResponse(requiredMemoryBytes, requiredDiskBytes,
                best == null ? null : best.serverId(), verdict, summary, ranked);
    }

    private CapacityServerResponse score(ServerNodeEntity server, long requiredMemory, long requiredDisk) {
        List<String> blockers = new ArrayList<>();
        List<String> observations = new ArrayList<>();
        LocalDateTime now = now();
        MetricPointResponse metric = metricService.current(server.getId());
        DockerHostSnapshotEntity docker = dockerHostMapper.selectById(server.getId());
        List<DockerContainerSnapshotEntity> containers = containerMapper.selectActive(server.getId());
        int runningContainers = (int) containers.stream()
                .filter(container -> "running".equalsIgnoreCase(container.getState())).count();
        int activeAlerts = Math.toIntExact(alertMapper.countActiveByServer(server.getId()));
        int criticalAlerts = Math.toIntExact(alertMapper.countActiveCriticalByServer(server.getId()));

        if (!"ONLINE".equals(server.getAgentStatus())) blockers.add("Agent 当前不在线");
        if (metric == null) blockers.add("尚无容量指标");
        else if (metric.timestamp() == null || metric.timestamp().isBefore(now.minusMinutes(2))) {
            blockers.add("容量指标超过 2 分钟未更新");
        }
        if (docker == null || docker.getAvailable() != 1) blockers.add("Docker 当前不可用");

        Long memoryAfter = metric == null || metric.memoryAvailable() == null ? null
                : metric.memoryAvailable() - requiredMemory;
        Long diskAfter = metric == null || metric.diskFree() == null ? null : metric.diskFree() - requiredDisk;
        Double projectedMemory = metric == null ? null
                : percentage(metric.memoryUsed() + requiredMemory, metric.memoryTotal());
        Double projectedDisk = metric == null ? null
                : percentage(metric.diskUsed() + requiredDisk, metric.diskTotal());
        if (memoryAfter != null && memoryAfter < MEMORY_SAFETY) blockers.add("部署后可用内存将低于 256 MiB");
        if (diskAfter != null && diskAfter < DISK_SAFETY) blockers.add("部署后可用磁盘将低于 2 GiB");
        if (projectedDisk != null && projectedDisk >= 95.0) blockers.add("预计磁盘使用率将达到 95% 保护线");

        double loadPerCore = metric == null || server.getCpuCores() == null || server.getCpuCores() <= 0
                ? 0 : metric.loadOne() / server.getCpuCores();
        if (metric != null && metric.cpuUsage() >= 80) observations.add("CPU 当前负载偏高");
        if (loadPerCore >= 0.8) observations.add("1 分钟负载接近 CPU 核心数");
        if (activeAlerts > 0) observations.add("存在 %d 条活动告警，其中 %d 条 Critical"
                .formatted(activeAlerts, criticalAlerts));
        if (runningContainers >= 15) observations.add("当前运行容器较多（%d）".formatted(runningContainers));

        int score = blockers.isEmpty() ? weightedScore(metric, memoryAfter, diskAfter, loadPerCore,
                runningContainers, activeAlerts, criticalAlerts) : 0;
        String grade = !blockers.isEmpty() ? "BLOCKED" : score >= 85 ? "EXCELLENT"
                : score >= 70 ? "GOOD" : score >= 50 ? "TIGHT" : "RISKY";
        return new CapacityServerResponse(server.getId(), server.getName(), server.getHostname(),
                server.getArchitecture(), blockers.isEmpty(), false, score, grade,
                metric == null ? null : metric.cpuUsage(), round(loadPerCore),
                metric == null ? null : metric.memoryUsage(), projectedMemory, memoryAfter,
                metric == null ? null : metric.diskUsage(), projectedDisk, diskAfter, runningContainers,
                activeAlerts, criticalAlerts, metric == null ? null : metric.timestamp(), blockers, observations);
    }

    private static int weightedScore(MetricPointResponse metric, long memoryAfter, long diskAfter,
                                     double loadPerCore, int containers, int alerts, int critical) {
        double memoryHeadroom = ratio(memoryAfter, metric.memoryTotal());
        double diskHeadroom = ratio(diskAfter, metric.diskTotal());
        double memoryScore = clamp(memoryHeadroom * 400);
        double diskScore = clamp(diskHeadroom * 500);
        double cpuScore = clamp(100 - metric.cpuUsage());
        double loadScore = clamp(100 - loadPerCore * 100);
        double densityScore = clamp(100 - containers * 4.0);
        double result = memoryScore * .35 + diskScore * .35 + cpuScore * .15 + loadScore * .10
                + densityScore * .05 - alerts * 2.0 - critical * 8.0;
        return (int) Math.round(clamp(result));
    }

    private static CapacityServerResponse withRecommendation(CapacityServerResponse value, boolean recommended) {
        return new CapacityServerResponse(value.serverId(), value.serverName(), value.hostname(), value.architecture(),
                value.eligible(), recommended, value.score(), value.grade(), value.cpuUsage(), value.loadPerCore(),
                value.memoryUsage(), value.projectedMemoryUsage(), value.memoryAvailableAfter(), value.diskUsage(),
                value.projectedDiskUsage(), value.diskFreeAfter(), value.runningContainers(), value.activeAlerts(),
                value.criticalAlerts(), value.metricAt(), value.blockers(), value.observations());
    }

    private static double ratio(Long value, Long total) {
        return value == null || total == null || total <= 0 ? 0 : Math.max(0, (double) value / total);
    }

    private static Double percentage(Long used, Long total) {
        return used == null || total == null || total <= 0 ? null : round(used * 100.0 / total);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(100, value));
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
