package com.devpilot.server.cicd.service;

import com.devpilot.server.application.mapper.ApplicationMapper;
import com.devpilot.server.cicd.mapper.CicdPipelineRunMapper;
import com.devpilot.server.cicd.mapper.CicdConfigurationMapper;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.security.DevPilotPrincipal;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManualReleaseApprovalService {
    private final ApplicationMapper applications;
    private final CicdPipelineRunMapper pipelines;
    private final CicdConfigurationMapper configurations;
    private final JdbcTemplate jdbc;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public record Request(@NotNull UUID requestId, @NotBlank @Size(max=64) String commitSha,
                          @NotBlank @Size(max=1000) String imageUri, @AssertTrue boolean confirmed,
                          @NotBlank @jakarta.validation.constraints.Pattern(regexp="[a-f0-9]{64}") String expectedFingerprint) { }
    public record Context(String applicationId, String applicationName, String environment, String serverId,
                          String buildRunId, String buildExternalRunId, String commitSha, String imageUri, String fingerprint) { }

    @Transactional(readOnly = true)
    public Context context(Long applicationId, Long buildId) {
        var app = applications.selectById(applicationId);
        var config = configurations.selectByApplicationId(applicationId);
        var build = pipelines.selectById(buildId);
        if (app == null || config == null || build == null || !applicationId.equals(build.getApplicationId())) {
            throw BusinessException.notFound(40441, "应用或构建不存在");
        }
        return new Context(applicationId.toString(), app.getName(), app.getEnvironment(), app.getServerId() == null ? null : app.getServerId().toString(),
                buildId.toString(), build.getExternalRunId(), build.getCommitSha(), build.getImageUri(), fingerprint(app, config));
    }
    public record Approval(String id, String applicationId, String buildRunId, String buildExternalRunId,
                           String commitSha, String imageUri, String environment, String serverId,
                           LocalDateTime configurationUpdatedAt, String approvedBy, String approvedUsername, LocalDateTime approvedAt, LocalDateTime expiresAt,
                           LocalDateTime revokedAt, String consumedByRunId, LocalDateTime consumedAt) { }

    private static final org.springframework.jdbc.core.RowMapper<Approval> ROW = (rs, row) -> new Approval(
            rs.getString("id"), rs.getString("application_id"), rs.getString("build_run_id"), rs.getString("build_external_run_id"),
            rs.getString("commit_sha"), rs.getString("image_uri"), rs.getString("environment"), rs.getString("server_id"),
            rs.getObject("configuration_updated_at", LocalDateTime.class),
            rs.getString("approved_by"), rs.getString("approved_username"), rs.getObject("approved_at", LocalDateTime.class),
            rs.getObject("expires_at", LocalDateTime.class), rs.getObject("revoked_at", LocalDateTime.class),
            rs.getString("consumed_by_run_id"), rs.getObject("consumed_at", LocalDateTime.class));

    public List<Approval> list(Long applicationId) {
        if (applications.selectById(applicationId) == null) throw BusinessException.notFound(40441, "应用不存在");
        return jdbc.query("SELECT * FROM cicd_release_approval WHERE application_id=? ORDER BY approved_at DESC,id DESC LIMIT 100", ROW, applicationId);
    }

    @Transactional
    public Approval approve(Long applicationId, Long buildId, Request request, DevPilotPrincipal principal) {
        if (!request.confirmed()) throw BusinessException.badRequest(40050, "必须明确确认发布此构建与镜像 digest");
        jdbc.queryForObject("SELECT id FROM sys_user WHERE id=? FOR UPDATE", Long.class, principal.userId());
        var app = applications.selectByIdForUpdate(applicationId);
        if (app == null) throw BusinessException.notFound(40441, "应用不存在");
        var previous = jdbc.query("SELECT * FROM cicd_release_approval WHERE approved_by=? AND request_id=?", ROW,
                principal.userId(), request.requestId().toString());
        if (!previous.isEmpty()) {
            var saved = previous.getFirst();
            if (!saved.applicationId().equals(applicationId.toString()) || !saved.buildRunId().equals(buildId.toString())
                    || !saved.commitSha().equalsIgnoreCase(request.commitSha()) || !saved.imageUri().equals(request.imageUri())
                    || !request.expectedFingerprint().equals(jdbc.queryForObject("SELECT configuration_fingerprint FROM cicd_release_approval WHERE id=?", String.class, saved.id()))) {
                throw BusinessException.conflict(40978, "同一次人工确认请求不能更换应用、构建或镜像");
            }
            // Replay never extends validity or rewrites who approved and when.
            return saved;
        }
        var build = pipelines.selectById(buildId);
        var config = configurations.selectByApplicationId(applicationId);
        if (build == null || !applicationId.equals(build.getApplicationId()) || !build.getExternalRunId().startsWith("build:")
                || !"SUCCEEDED".equals(build.getStatus()) || !"PASSED".equals(build.getTestStatus()) || !"PASSED".equals(build.getSecurityStatus())
                || build.getImageUri() == null || !build.getImageUri().matches(".+@sha256:[a-f0-9]{64}$")
                || !build.getImageUri().equals(request.imageUri()) || !build.getCommitSha().equalsIgnoreCase(request.commitSha())
                || config == null || !config.getBranchName().equals(build.getBranchName())) {
            throw BusinessException.badRequest(40050, "只能确认本应用当前分支已通过测试和扫描的构建，commit 与 digest 必须一致");
        }
        if (app.getServerId() == null) throw BusinessException.badRequest(40050, "请先确认目标服务器");
        String currentFingerprint = fingerprint(app, config);
        if (!currentFingerprint.equals(request.expectedFingerprint())) throw BusinessException.conflict(40978, "确认页面打开后配置已变化，请重新读取目标并确认");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO cicd_release_approval(id,request_id,application_id,build_run_id,build_external_run_id,commit_sha,image_uri,environment,server_id,configuration_updated_at,configuration_fingerprint,approved_by,approved_username,approved_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, request.requestId().toString(), applicationId, buildId, build.getExternalRunId(), build.getCommitSha(), build.getImageUri(),
                app.getEnvironment(), app.getServerId(), config.getUpdatedAt(), currentFingerprint, principal.userId(), principal.getUsername(), now, now.plusHours(24));
        return jdbc.queryForObject("SELECT * FROM cicd_release_approval WHERE id=?", ROW, id);
    }

    private String fingerprint(com.devpilot.server.application.entity.ApplicationEntity app,
                               com.devpilot.server.cicd.entity.CicdConfigurationEntity config) {
        var revisions = jdbc.query("SELECT revision FROM application_environment_state WHERE application_id=?",
                (rs, row) -> rs.getLong(1), app.getId());
        // Exclude observational timestamps and runtime health. Include encrypted credential
        // values so a credential/target change invalidates approval even within one second.
        var values = java.util.Arrays.asList("release-target-v1", app.getId(), app.getEnvironment(), app.getServerId(),
                app.getDeployType(), app.getHealthCheckUrl(), app.getAccessUrl(),
                config.getRepositoryProvider(), config.getRepositoryUrl(), config.getBranchName(), config.getDeploymentProvider(),
                config.getDeploymentMode(), config.getDeploymentWebhookCipher(), config.getProviderBaseUrlCipher(),
                config.getProviderApiTokenCipher(), config.getProviderResourceId(), config.getCallbackSecretCipher(),
                config.getAutoDeploy(), config.getProductionApproval(), config.getAutoRollback(), config.getHealthTimeoutSeconds(),
                revisions.isEmpty() ? 0L : revisions.getFirst());
        try { return com.devpilot.server.security.SecretHashing.sha256(json.writeValueAsString(values)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException cause) { throw new IllegalStateException("Cannot fingerprint release target", cause); }
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public Approval consume(Long applicationId, String approvalId, String buildExternalRunId, String releaseExternalRunId,
                            String commitSha, String imageUri) {
        if (releaseExternalRunId == null || releaseExternalRunId.isBlank() || releaseExternalRunId.length() > 255
                || releaseExternalRunId.startsWith("build:")) throw BusinessException.badRequest(40050, "发布 Run ID 无效");
        var app = applications.selectByIdForUpdate(applicationId);
        if (app == null) throw BusinessException.notFound(40441, "应用不存在");
        var approval = locked(applicationId, approvalId);
        if (!approval.buildExternalRunId().equals(buildExternalRunId) || !approval.commitSha().equalsIgnoreCase(commitSha)
                || !approval.imageUri().equals(imageUri)) throw BusinessException.conflict(40978, "人工确认与发布构建、commit 或 digest 不匹配");
        if (approval.revokedAt() != null) throw BusinessException.conflict(40978, "人工确认已撤销");
        if (!approval.expiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) throw BusinessException.conflict(40978, "人工确认已过期，请重新核对版本后确认");
        var config = configurations.selectByApplicationId(applicationId);
        String expected = jdbc.queryForObject("SELECT configuration_fingerprint FROM cicd_release_approval WHERE id=?", String.class, approvalId);
        if (config == null || !fingerprint(app, config).equals(expected)) throw BusinessException.conflict(40978, "发布目标、配置或环境变量已变化，请重新确认");
        var build = pipelines.selectById(Long.valueOf(approval.buildRunId()));
        if (build == null || !"SUCCEEDED".equals(build.getStatus()) || !"PASSED".equals(build.getTestStatus())
                || !"PASSED".equals(build.getSecurityStatus()) || !approval.imageUri().equals(build.getImageUri())
                || !approval.commitSha().equalsIgnoreCase(build.getCommitSha())) throw BusinessException.conflict(40978, "构建证据已变化或不可用");
        if (approval.consumedByRunId() != null) {
            if (!approval.consumedByRunId().equals(releaseExternalRunId)) throw BusinessException.conflict(40978, "该确认已绑定另一次发布，请重新确认");
            return approval; // Revalidation still applies; binding is not permission to deploy changed targets.
        }
        jdbc.update("UPDATE cicd_release_approval SET consumed_by_run_id=?,consumed_at=? WHERE id=?",
                releaseExternalRunId, LocalDateTime.now(ZoneOffset.UTC), approvalId);
        return locked(applicationId, approvalId);
    }

    @Transactional
    public Approval revoke(Long applicationId, String approvalId) {
        if (applications.selectByIdForUpdate(applicationId) == null) throw BusinessException.notFound(40441, "应用不存在");
        var approval = locked(applicationId, approvalId);
        if (approval.consumedByRunId() != null) throw BusinessException.conflict(40978, "确认已被发布使用，撤销不能取消部署");
        if (approval.revokedAt() == null) jdbc.update("UPDATE cicd_release_approval SET revoked_at=? WHERE id=?", LocalDateTime.now(ZoneOffset.UTC), approvalId);
        return locked(applicationId, approvalId);
    }

    private Approval locked(Long applicationId, String approvalId) {
        var approvals = jdbc.query("SELECT * FROM cicd_release_approval WHERE id=? AND application_id=? FOR UPDATE", ROW, approvalId, applicationId);
        if (approvals.isEmpty()) throw BusinessException.notFound(40441, "未找到本应用的人工确认记录");
        return approvals.getFirst();
    }
}
