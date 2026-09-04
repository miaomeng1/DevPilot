package com.devpilot.server.observability;

import com.devpilot.server.alert.mapper.AlertEventMapper;
import com.devpilot.server.application.mapper.ApplicationMapper;
import com.devpilot.server.docker.mapper.DockerContainerSnapshotMapper;
import com.devpilot.server.node.mapper.ServerNodeMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DevPilotMetrics {

    private final ServerNodeMapper servers;
    private final DockerContainerSnapshotMapper containers;
    private final ApplicationMapper applications;
    private final AlertEventMapper alerts;
    private final AtomicLong serverTotal = new AtomicLong();
    private final AtomicLong serverOnline = new AtomicLong();
    private final AtomicLong containerTotal = new AtomicLong();
    private final AtomicLong containerRunning = new AtomicLong();
    private final AtomicLong applicationTotal = new AtomicLong();
    private final AtomicLong applicationHealthy = new AtomicLong();
    private final AtomicLong alertActive = new AtomicLong();
    private final AtomicLong alertCritical = new AtomicLong();
    private final AtomicLong snapshotSuccess = new AtomicLong();

    public DevPilotMetrics(MeterRegistry registry, ServerNodeMapper servers,
                           DockerContainerSnapshotMapper containers, ApplicationMapper applications,
                           AlertEventMapper alerts) {
        this.servers = servers;
        this.containers = containers;
        this.applications = applications;
        this.alerts = alerts;
        gauge(registry, "devpilot.servers.managed", "Managed servers", serverTotal);
        gauge(registry, "devpilot.servers.online", "Servers with an online Agent", serverOnline);
        gauge(registry, "devpilot.containers.discovered", "Active discovered containers", containerTotal);
        gauge(registry, "devpilot.containers.running", "Running discovered containers", containerRunning);
        gauge(registry, "devpilot.applications.managed", "Managed applications", applicationTotal);
        gauge(registry, "devpilot.applications.healthy", "Managed applications without a known fault", applicationHealthy);
        gauge(registry, "devpilot.alerts.active", "Active or acknowledged alerts", alertActive);
        gauge(registry, "devpilot.alerts.critical", "Active critical alerts", alertCritical);
        gauge(registry, "devpilot.metrics.snapshot.success", "Whether the latest control-plane snapshot succeeded", snapshotSuccess);
    }

    @Scheduled(fixedDelayString = "${devpilot.observability.snapshot-interval-ms:30000}", initialDelay = 5000)
    public void refresh() {
        try {
            long appTotal = applications.countAll();
            serverTotal.set(servers.countAllActive());
            serverOnline.set(servers.countOnline());
            containerTotal.set(containers.countAllActive());
            containerRunning.set(containers.countRunning());
            applicationTotal.set(appTotal);
            applicationHealthy.set(Math.max(0, appTotal - applications.countUnhealthy()));
            alertActive.set(alerts.countActive());
            alertCritical.set(alerts.countActiveCritical());
            snapshotSuccess.set(1);
        } catch (RuntimeException error) {
            snapshotSuccess.set(0);
            log.warn("Could not refresh DevPilot observability snapshot: {}", error.getClass().getSimpleName());
        }
    }

    private static void gauge(MeterRegistry registry, String name, String description, AtomicLong value) {
        Gauge.builder(name, value, AtomicLong::get).description(description).register(registry);
    }
}
