package com.devpilot.server.cicd.onboarding;

import com.devpilot.server.common.ApiResponse;
import com.devpilot.server.security.DevPilotPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/cicd/onboarding")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class OnboardingController {
    private final OnboardingService service;
    private final RepositoryOnboardingClient repositories;
    private final ProviderOnboardingClient providers;
    private final com.devpilot.server.setup.PlatformSetupService setup;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    @ExceptionHandler({IllegalArgumentException.class, OnboardingHttpClient.RemoteFailure.class})
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> connectionError(RuntimeException error) {
        return ApiResponse.error(40070, error.getCause() instanceof java.net.URISyntaxException ? "地址格式无效" : error.getMessage());
    }
    public record InspectRequest(@NotBlank @Pattern(regexp = "GITHUB|GITLAB") String repositoryProvider,
            @NotBlank @Size(max = 1000) String repositoryUrl, @NotBlank @Size(max = 4000) String repositoryToken,
            @NotBlank @Pattern(regexp = "DOKPLOY|COOLIFY") String deploymentProvider,
            @Size(max = 1000) String providerBaseUrl, @Size(max = 4000) String providerApiToken,
            Boolean usePlatformConnection, @Size(max=36) String platformRevision) {
        @Override public String toString() { return "InspectRequest[REDACTED]"; }
    }
    public record Inspection(RepositoryOnboardingClient.Repository repository, ProviderOnboardingClient.Discovery provider) { }
    public record Credentials(@Size(max = 4000) String repositoryToken, @Size(max = 4000) String providerApiToken,
                              @Size(max = 4000) String registryPassword,
                              @Size(max = 100) java.util.Map<@Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*") String, @NotNull @Size(max = 4000) String> environmentValues,
                              Boolean providerQuotaConfirmed) { }
    public record PortParameters(@Min(1) @Max(65535) int containerPort, @Min(1024) @Max(65535) int hostPort,
            @NotBlank @Pattern(regexp = "/[A-Za-z0-9/_?=&.-]*") @Size(max = 500) String healthPath) { }

    @PutMapping("/{applicationId}/ports")
    public ApiResponse<OnboardingService.Status> ports(@PathVariable Long applicationId, @Valid @RequestBody PortParameters request) {
        return ApiResponse.success(service.updatePorts(applicationId, request.containerPort(), request.hostPort(), request.healthPath()));
    }

    @PostMapping("/inspect")
    public ApiResponse<Inspection> inspect(@Valid @RequestBody InspectRequest request) {
        var connection = connection(request.usePlatformConnection(), request.platformRevision(), request.deploymentProvider(), request.providerBaseUrl(), request.providerApiToken());
        return ApiResponse.success(new Inspection(repositories.inspect(request.repositoryProvider(), request.repositoryUrl(), request.repositoryToken()),
                providers.discover(request.deploymentProvider(), connection.url(), connection.token())));
    }
    @PostMapping("/{applicationId}")
    public ApiResponse<OnboardingService.Status> start(@PathVariable Long applicationId, @Valid @RequestBody OnboardingRequest request) {
        var connection = connection(request.usePlatformConnection(), request.platformRevision(), request.deploymentProvider(), request.providerBaseUrl(), request.providerApiToken());
        try {
            var payload = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(request);
            payload.put("providerBaseUrl", connection.url()); payload.put("providerApiToken", connection.token());
            request = json.treeToValue(payload, OnboardingRequest.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalArgumentException("无法构建接入参数"); }
        return ApiResponse.success(service.start(applicationId, request));
    }
    @GetMapping("/{applicationId}")
    public ApiResponse<OnboardingService.Status> get(@PathVariable Long applicationId) { return ApiResponse.success(service.get(applicationId)); }
    @PostMapping("/{applicationId}/advance")
    public ApiResponse<OnboardingService.Status> advance(@PathVariable Long applicationId, @AuthenticationPrincipal DevPilotPrincipal principal) {
        return ApiResponse.success(service.advance(applicationId, principal));
    }
    @PostMapping("/{applicationId}/verify")
    public ApiResponse<String> verify(@PathVariable Long applicationId) {
        service.verifyConnection(applicationId); return ApiResponse.success("已验证部署平台连接和应用 ID");
    }
    @PutMapping("/{applicationId}/credentials")
    public ApiResponse<OnboardingService.Status> credentials(@PathVariable Long applicationId, @Valid @RequestBody Credentials request) {
        return ApiResponse.success(service.credentials(applicationId, request.repositoryToken(), request.providerApiToken(), request.registryPassword(), request.environmentValues(), request.providerQuotaConfirmed()));
    }
    private com.devpilot.server.setup.PlatformSetupService.InternalConnection connection(Boolean useSaved, String revision, String provider, String url, String token) {
        if (Boolean.TRUE.equals(useSaved)) {
            if (!"DOKPLOY".equals(provider)) throw new IllegalArgumentException("初始化授权只适用于 Dokploy");
            return setup.connection(revision);
        }
        if (url == null || url.isBlank() || token == null || token.isBlank()) throw new IllegalArgumentException("请提供部署平台地址和 Key，或选择初始化授权");
        return new com.devpilot.server.setup.PlatformSetupService.InternalConnection(OnboardingHttpClient.origin(url, false), token);
    }
}
