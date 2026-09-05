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
    @ExceptionHandler({IllegalArgumentException.class, OnboardingHttpClient.RemoteFailure.class})
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> connectionError(RuntimeException error) {
        return ApiResponse.error(40070, error.getCause() instanceof java.net.URISyntaxException ? "地址格式无效" : error.getMessage());
    }
    public record InspectRequest(@NotBlank @Pattern(regexp = "GITHUB|GITLAB") String repositoryProvider,
            @NotBlank @Size(max = 1000) String repositoryUrl, @NotBlank @Size(max = 4000) String repositoryToken,
            @NotBlank @Pattern(regexp = "DOKPLOY|COOLIFY") String deploymentProvider,
            @NotBlank @Size(max = 1000) String providerBaseUrl, @NotBlank @Size(max = 4000) String providerApiToken) { }
    public record Inspection(RepositoryOnboardingClient.Repository repository, ProviderOnboardingClient.Discovery provider) { }
    public record Credentials(@Size(max = 4000) String repositoryToken, @Size(max = 4000) String providerApiToken,
                              @Size(max = 4000) String registryPassword) { }

    @PostMapping("/inspect")
    public ApiResponse<Inspection> inspect(@Valid @RequestBody InspectRequest request) {
        return ApiResponse.success(new Inspection(repositories.inspect(request.repositoryProvider(), request.repositoryUrl(), request.repositoryToken()),
                providers.discover(request.deploymentProvider(), request.providerBaseUrl(), request.providerApiToken())));
    }
    @PostMapping("/{applicationId}")
    public ApiResponse<OnboardingService.Status> start(@PathVariable Long applicationId, @Valid @RequestBody OnboardingRequest request) {
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
        return ApiResponse.success(service.credentials(applicationId, request.repositoryToken(), request.providerApiToken(), request.registryPassword()));
    }
}
