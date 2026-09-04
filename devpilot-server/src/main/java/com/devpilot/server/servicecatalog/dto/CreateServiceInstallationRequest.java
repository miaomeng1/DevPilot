package com.devpilot.server.servicecatalog.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateServiceInstallationRequest(
        @NotNull Long serverId,
        @NotBlank @Size(min = 2, max = 120) String displayName,
        @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{1,62}[a-z0-9]",
                message = "实例名需为 3-64 位小写字母、数字或连字符") String instanceName,
        @NotBlank @Pattern(regexp = "DEV|TEST|STAGING|PRODUCTION") String environment,
        @Min(1024) @Max(65535) int hostPort,
        @NotBlank @Size(max = 64)
        @Pattern(regexp = "[A-Za-z_]+(?:/[A-Za-z0-9_+.-]+)*", message = "时区格式无效") String timezone) {
}
