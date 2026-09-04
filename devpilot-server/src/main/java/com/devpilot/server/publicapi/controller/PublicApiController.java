package com.devpilot.server.publicapi.controller;

import com.devpilot.server.alert.dto.AlertEventResponse;
import com.devpilot.server.alert.mapper.AlertEventMapper;
import com.devpilot.server.alert.service.AlertEventService;
import com.devpilot.server.application.dto.ApplicationResponse;
import com.devpilot.server.application.mapper.ApplicationMapper;
import com.devpilot.server.application.service.ApplicationService;
import com.devpilot.server.cicd.dto.CicdActivityResponse;
import com.devpilot.server.cicd.service.CicdDeploymentService;
import com.devpilot.server.common.ApiResponse;
import com.devpilot.server.docker.mapper.DockerContainerSnapshotMapper;
import com.devpilot.server.node.dto.ServerNodeResponse;
import com.devpilot.server.node.mapper.ServerNodeMapper;
import com.devpilot.server.node.service.ServerNodeService;
import com.devpilot.server.publicapi.dto.PublicApiStatusResponse;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PublicApiController {
    public static final String VERSION = "2026-09-01";
    private final ServerNodeMapper serverMapper;
    private final DockerContainerSnapshotMapper containerMapper;
    private final ApplicationMapper applicationMapper;
    private final AlertEventMapper alertMapper;
    private final ServerNodeService serverService;
    private final ApplicationService applicationService;
    private final AlertEventService alertService;
    private final CicdDeploymentService deploymentService;

    @GetMapping("/status")
    public ApiResponse<PublicApiStatusResponse> status() {
        return ApiResponse.success(new PublicApiStatusResponse(VERSION, Instant.now(), serverMapper.countAllActive(),
                serverMapper.countOnline(), containerMapper.countAllActive(), containerMapper.countRunning(),
                applicationMapper.countAll(), alertMapper.countActive(), alertMapper.countActiveCritical()));
    }

    @GetMapping("/servers") public ApiResponse<List<ServerNodeResponse>> servers() {
        return ApiResponse.success(serverService.list());
    }

    @GetMapping("/applications") public ApiResponse<List<ApplicationResponse>> applications() {
        return ApiResponse.success(applicationService.list());
    }

    @GetMapping("/alerts")
    public ApiResponse<List<AlertEventResponse>> alerts(@RequestParam(required = false) String status,
                                                        @RequestParam(required = false) String severity,
                                                        @RequestParam(required = false) Long serverId) {
        return ApiResponse.success(alertService.list(status, severity, serverId));
    }

    @GetMapping("/deployments")
    public ApiResponse<List<CicdActivityResponse>> deployments(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(deploymentService.activity(limit));
    }
}
