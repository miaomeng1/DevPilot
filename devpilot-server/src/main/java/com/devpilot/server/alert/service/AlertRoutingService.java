package com.devpilot.server.alert.service;

import com.devpilot.server.alert.dto.AlertRouteRequest;
import com.devpilot.server.alert.dto.AlertRouteResponse;
import com.devpilot.server.alert.dto.MaintenanceWindowRequest;
import com.devpilot.server.alert.dto.MaintenanceWindowResponse;
import com.devpilot.server.alert.entity.AlertEventEntity;
import com.devpilot.server.alert.entity.AlertMaintenanceWindowEntity;
import com.devpilot.server.alert.entity.AlertNotificationRouteEntity;
import com.devpilot.server.alert.mapper.AlertMaintenanceWindowMapper;
import com.devpilot.server.alert.mapper.AlertNotificationRouteMapper;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.node.entity.ServerNodeEntity;
import com.devpilot.server.node.mapper.ServerNodeMapper;
import com.devpilot.server.security.DevPilotPrincipal;
import com.devpilot.server.security.SensitiveSettingCipher;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AlertRoutingService {

    private final AlertNotificationRouteMapper routeMapper;
    private final AlertMaintenanceWindowMapper maintenanceMapper;
    private final ServerNodeMapper serverMapper;
    private final SensitiveSettingCipher cipher;

    public List<AlertRouteResponse> routes() {
        Instant now = Instant.now();
        return routeMapper.selectAll().stream().map(route -> toResponse(route, now)).toList();
    }

    @Transactional
    public AlertRouteResponse createRoute(AlertRouteRequest request, DevPilotPrincipal principal) {
        if (routeMapper.selectAll().size() >= 20) {
            throw BusinessException.conflict(40970, "通知路由最多支持 20 条");
        }
        ValidatedRoute values = validateRoute(request, null, true);
        LocalDateTime now = now();
        AlertNotificationRouteEntity route = new AlertNotificationRouteEntity();
        apply(route, request, values);
        route.setWebhookUrlEncrypted(cipher.encrypt(values.webhookUrl()));
        route.setDestinationType(AlertSettingsService.destinationType(values.webhookUrl()));
        route.setCreatedBy(principal.userId());
        route.setCreatedAt(now);
        route.setUpdatedAt(now);
        routeMapper.insert(route);
        return toResponse(route, Instant.now());
    }

    @Transactional
    public AlertRouteResponse updateRoute(Long id, AlertRouteRequest request) {
        AlertNotificationRouteEntity route = requireRoute(id);
        ValidatedRoute values = validateRoute(request, id, false);
        apply(route, request, values);
        if (values.webhookUrl() != null) {
            route.setWebhookUrlEncrypted(cipher.encrypt(values.webhookUrl()));
            route.setDestinationType(AlertSettingsService.destinationType(values.webhookUrl()));
        }
        route.setUpdatedAt(now());
        routeMapper.updateById(route);
        return toResponse(route, Instant.now());
    }

    @Transactional
    public void deleteRoute(Long id) {
        requireRoute(id);
        routeMapper.deleteById(id);
    }

    public List<MaintenanceWindowResponse> maintenanceWindows() {
        LocalDateTime now = now();
        return maintenanceMapper.selectRecent(now, now.minusDays(7)).stream()
                .map(window -> toResponse(window, now)).toList();
    }

    @Transactional
    public MaintenanceWindowResponse createMaintenance(MaintenanceWindowRequest request,
                                                       DevPilotPrincipal principal) {
        LocalDateTime startsAt = LocalDateTime.ofInstant(request.startsAt(), ZoneOffset.UTC);
        LocalDateTime endsAt = LocalDateTime.ofInstant(request.endsAt(), ZoneOffset.UTC);
        if (!endsAt.isAfter(startsAt)) {
            throw BusinessException.badRequest(40070, "维护结束时间必须晚于开始时间");
        }
        if (Duration.between(request.startsAt(), request.endsAt()).compareTo(Duration.ofDays(30)) > 0) {
            throw BusinessException.badRequest(40071, "单次维护窗口不能超过 30 天");
        }
        validateServer(request.serverId());
        LocalDateTime now = now();
        AlertMaintenanceWindowEntity window = new AlertMaintenanceWindowEntity();
        window.setName(request.name().trim());
        window.setReason(trimToNull(request.reason()));
        window.setServerId(request.serverId());
        window.setStartsAt(startsAt);
        window.setEndsAt(endsAt);
        window.setCreatedBy(principal.userId());
        window.setCreatedAt(now);
        window.setUpdatedAt(now);
        maintenanceMapper.insert(window);
        return toResponse(window, now);
    }

    @Transactional
    public void deleteMaintenance(Long id) {
        if (maintenanceMapper.selectById(id) == null) {
            throw BusinessException.notFound(40471, "维护窗口不存在");
        }
        maintenanceMapper.deleteById(id);
    }

    public boolean hasEnabledRoutes() {
        return routeMapper.countEnabled() > 0;
    }

    public List<AlertNotificationRouteEntity> matchingRoutes(AlertEventEntity event, String transition) {
        return routeMapper.selectEnabled().stream()
                .filter(route -> route.getServerId() == null || route.getServerId().equals(event.getServerId()))
                .filter(route -> severityRank(event.getSeverity()) >= severityRank(route.getMinimumSeverity()))
                .filter(route -> !"RESOLVED".equals(transition) || route.getNotifyResolved() == 1)
                .toList();
    }

    public DeliveryTarget deliveryTarget(Long routeId, AlertEventEntity event, Instant instant) {
        AlertNotificationRouteEntity route = routeId == null ? null : routeMapper.selectById(routeId);
        if (route == null) {
            return DeliveryTarget.muted("通知路由已删除");
        }
        if (route.getEnabled() != 1) {
            return DeliveryTarget.muted("通知路由已停用");
        }
        boolean bypass = "CRITICAL".equals(event.getSeverity()) && route.getCriticalBypassMute() == 1;
        LocalDateTime utc = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        if (!bypass && !maintenanceMapper.selectActive(utc, event.getServerId()).isEmpty()) {
            return DeliveryTarget.muted("处于维护窗口，通知已静默");
        }
        if (!bypass && isQuiet(route, instant)) {
            return DeliveryTarget.muted("处于周期静默时段，通知已静默");
        }
        return new DeliveryTarget(cipher.decrypt(route.getWebhookUrlEncrypted()), route.getDestinationType(), null);
    }

    private ValidatedRoute validateRoute(AlertRouteRequest request, Long routeId, boolean create) {
        String name = request.name().trim();
        if (routeMapper.countByNameExcept(name, routeId) > 0) {
            throw BusinessException.conflict(40971, "通知路由名称已存在");
        }
        validateServer(request.serverId());
        String webhookUrl = trimToNull(request.webhookUrl());
        if (create && webhookUrl == null) {
            throw BusinessException.badRequest(40072, "创建通知路由时必须提供 Webhook URL");
        }
        if (webhookUrl != null) {
            AlertSettingsService.validateUrl(webhookUrl);
        }
        ZoneId timezone;
        try {
            timezone = ZoneId.of(request.timezone().trim());
        } catch (DateTimeException exception) {
            throw BusinessException.badRequest(40073, "通知路由时区无效");
        }
        String quietDays = null;
        if (request.quietEnabled()) {
            if (request.quietStart() == null || request.quietEnd() == null
                    || request.quietStart().equals(request.quietEnd())
                    || request.quietDays() == null || request.quietDays().isEmpty()) {
                throw BusinessException.badRequest(40074, "启用周期静默时必须选择日期和不同的开始、结束时间");
            }
            quietDays = request.quietDays().stream().distinct().collect(Collectors.joining(","));
        }
        return new ValidatedRoute(webhookUrl, timezone.getId(), quietDays);
    }

    private void validateServer(Long serverId) {
        if (serverId != null && serverMapper.selectActiveById(serverId) == null) {
            throw BusinessException.notFound(40470, "通知范围中的服务器不存在");
        }
    }

    private static void apply(AlertNotificationRouteEntity route, AlertRouteRequest request, ValidatedRoute values) {
        route.setName(request.name().trim());
        route.setServerId(request.serverId());
        route.setMinimumSeverity(request.minimumSeverity());
        route.setNotifyResolved(request.notifyResolved() ? 1 : 0);
        route.setEnabled(request.enabled() ? 1 : 0);
        route.setQuietEnabled(request.quietEnabled() ? 1 : 0);
        route.setQuietStart(request.quietEnabled() ? request.quietStart() : null);
        route.setQuietEnd(request.quietEnabled() ? request.quietEnd() : null);
        route.setQuietDays(request.quietEnabled() ? values.quietDays() : null);
        route.setTimezone(values.timezone());
        route.setCriticalBypassMute(request.criticalBypassMute() ? 1 : 0);
    }

    private AlertNotificationRouteEntity requireRoute(Long id) {
        AlertNotificationRouteEntity route = routeMapper.selectById(id);
        if (route == null) {
            throw BusinessException.notFound(40472, "通知路由不存在");
        }
        return route;
    }

    private AlertRouteResponse toResponse(AlertNotificationRouteEntity route, Instant now) {
        ServerNodeEntity server = route.getServerId() == null ? null : serverMapper.selectById(route.getServerId());
        return new AlertRouteResponse(route.getId(), route.getName(), route.getServerId(),
                route.getServerId() == null ? "全部服务器" : server == null ? "已删除服务器" : server.getName(),
                route.getMinimumSeverity(), route.getDestinationType(), route.getWebhookUrlEncrypted() != null,
                route.getNotifyResolved() == 1, route.getEnabled() == 1, route.getQuietEnabled() == 1,
                route.getQuietStart(), route.getQuietEnd(), splitDays(route.getQuietDays()), route.getTimezone(),
                route.getCriticalBypassMute() == 1, route.getEnabled() == 1 && isQuiet(route, now),
                route.getCreatedAt(), route.getUpdatedAt());
    }

    private MaintenanceWindowResponse toResponse(AlertMaintenanceWindowEntity window, LocalDateTime now) {
        ServerNodeEntity server = window.getServerId() == null ? null : serverMapper.selectById(window.getServerId());
        String status = !window.getEndsAt().isAfter(now) ? "ENDED"
                : window.getStartsAt().isAfter(now) ? "UPCOMING" : "ACTIVE";
        return new MaintenanceWindowResponse(window.getId(), window.getName(), window.getReason(),
                window.getServerId(), window.getServerId() == null ? "全部服务器" : server == null
                ? "已删除服务器" : server.getName(), window.getStartsAt(), window.getEndsAt(), status,
                window.getCreatedAt());
    }

    static boolean isQuiet(AlertNotificationRouteEntity route, Instant instant) {
        if (route.getQuietEnabled() != 1 || route.getQuietStart() == null || route.getQuietEnd() == null
                || route.getQuietDays() == null) {
            return false;
        }
        ZonedDateTime local = instant.atZone(ZoneId.of(route.getTimezone()));
        LocalTime start = LocalTime.parse(route.getQuietStart());
        LocalTime end = LocalTime.parse(route.getQuietEnd());
        Set<DayOfWeek> days = Arrays.stream(route.getQuietDays().split(","))
                .map(DayOfWeek::valueOf).collect(Collectors.toSet());
        LocalTime time = local.toLocalTime();
        if (start.isBefore(end)) {
            return days.contains(local.getDayOfWeek()) && !time.isBefore(start) && time.isBefore(end);
        }
        if (!time.isBefore(start)) {
            return days.contains(local.getDayOfWeek());
        }
        return time.isBefore(end) && days.contains(local.minusDays(1).getDayOfWeek());
    }

    private static List<String> splitDays(String value) {
        return value == null || value.isBlank() ? List.of() : Arrays.asList(value.split(","));
    }

    private static int severityRank(String severity) {
        return switch (severity) {
            case "CRITICAL" -> 3;
            case "WARNING" -> 2;
            case "INFO" -> 1;
            default -> 0;
        };
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private record ValidatedRoute(String webhookUrl, String timezone, String quietDays) {
    }

    public record DeliveryTarget(String url, String destinationType, String mutedReason) {
        static DeliveryTarget muted(String reason) {
            return new DeliveryTarget(null, null, reason);
        }

        public boolean muted() {
            return mutedReason != null;
        }
    }
}
