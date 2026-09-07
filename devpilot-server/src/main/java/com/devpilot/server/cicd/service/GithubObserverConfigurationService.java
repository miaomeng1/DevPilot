package com.devpilot.server.cicd.service;

import com.devpilot.server.application.mapper.ApplicationMapper;
import com.devpilot.server.cicd.mapper.CicdConfigurationMapper;
import com.devpilot.server.exception.BusinessException;
import com.devpilot.server.security.SensitiveSettingCipher;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GithubObserverConfigurationService {
    private final JdbcTemplate jdbc;
    private final ApplicationMapper applications;
    private final CicdConfigurationMapper configurations;
    private final SensitiveSettingCipher cipher;

    public record Request(@NotBlank String revision, @AssertTrue(message="请明确确认保存查询凭据") boolean consent,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_]{10,512}", message="查询凭据格式无效")
            @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String repositoryToken, @NotNull Instant expiresAt) {
        @Override public String toString() { return "GithubObserverRequest[redacted]"; }
    }
    public record Status(String revision, String state, boolean credentialConfigured, Instant expiresAt, Instant nextCheckAt) {}

    public Status status(Long applicationId) {
        if (applications.selectById(applicationId) == null) throw BusinessException.notFound(40420,"应用不存在");
        var config = configurations.selectByApplicationId(applicationId);
        var rows = jdbc.query("SELECT revision, repository_url, branch_name, enabled, expires_at, next_check_at, token_cipher IS NOT NULL AS configured FROM github_observer_configuration WHERE application_id=?",
                (rs, index) -> {
                    Timestamp expiry = rs.getTimestamp("expires_at");
                    boolean present = rs.getBoolean("configured");
                    String state = rs.getInt("enabled") != 1 || !present ? "DISABLED"
                            : expiry == null || !expiry.toInstant().isAfter(Instant.now()) ? "EXPIRED"
                            : config == null || !"GITHUB".equals(config.getRepositoryProvider())
                                || !rs.getString("repository_url").equals(config.getRepositoryUrl())
                                || !rs.getString("branch_name").equals(config.getBranchName()) ? "CONFIGURATION_CHANGED" : "SAVED_UNVERIFIED";
                    Timestamp next = rs.getTimestamp("next_check_at");
                    return new Status(rs.getString("revision"), state, present, expiry == null ? null : expiry.toInstant(),
                            "SAVED_UNVERIFIED".equals(state) && next != null ? next.toInstant() : null);
                }, applicationId);
        return rows.isEmpty() ? new Status("initial", "DISABLED", false, null, null) : rows.getFirst();
    }

    @Transactional
    public Status save(Long applicationId, Request request) {
        if (applications.selectByIdForUpdate(applicationId) == null) throw BusinessException.notFound(40420,"应用不存在");
        if (!request.consent() || request.expiresAt() == null || !request.expiresAt().isAfter(Instant.now())
                || request.expiresAt().isAfter(Instant.now().plus(Duration.ofDays(30)))) {
            throw BusinessException.badRequest(40041,"凭据需明确授权保存，有效期必须在未来 30 天内");
        }
        var config = configurations.selectByApplicationId(applicationId);
        if (config == null || !"GITHUB".equals(config.getRepositoryProvider())) throw BusinessException.badRequest(40041,"仅支持 GitHub 配置");
        try { GithubRunEvidence.identity(config.getRepositoryUrl(), "build:github-1-1", "a".repeat(40), config.getBranchName()); }
        catch (IllegalArgumentException invalid) { throw BusinessException.badRequest(40041,"仓库地址不支持自动核对"); }
        Status current = status(applicationId);
        if (!current.revision().equals(request.revision())) throw BusinessException.conflict(40941,"配置已变化，请刷新后确认");
        String encrypted = cipher.encrypt(request.repositoryToken());
        String revision = UUID.randomUUID().toString();
        if (current.revision().equals("initial")) {
            jdbc.update("INSERT INTO github_observer_configuration(application_id,revision,repository_url,branch_name,token_cipher,expires_at,enabled,updated_at) VALUES(?,?,?,?,?,?,1,?)",
                    applicationId, revision, config.getRepositoryUrl(), config.getBranchName(), encrypted, Timestamp.from(request.expiresAt()), Timestamp.from(Instant.now()));
        } else {
            jdbc.update("UPDATE github_observer_configuration SET revision=?,repository_url=?,branch_name=?,token_cipher=?,expires_at=?,enabled=1,updated_at=? WHERE application_id=?",
                    revision, config.getRepositoryUrl(), config.getBranchName(), encrypted, Timestamp.from(request.expiresAt()), Timestamp.from(Instant.now()), applicationId);
        }
        return status(applicationId);
    }

    @Transactional
    public Status disable(Long applicationId, String revision) {
        if (applications.selectByIdForUpdate(applicationId) == null) throw BusinessException.notFound(40420,"应用不存在");
        if (!status(applicationId).revision().equals(revision)) throw BusinessException.conflict(40941,"配置已变化，请刷新后确认");
        jdbc.update("UPDATE github_observer_configuration SET revision=?,token_cipher=NULL,expires_at=NULL,enabled=0,updated_at=? WHERE application_id=?",
                UUID.randomUUID().toString(), Timestamp.from(Instant.now()), applicationId);
        return status(applicationId);
    }

    @Scheduled(fixedDelayString="${devpilot.cicd.observer-credential-cleanup:1h}", initialDelayString="${devpilot.cicd.observer-credential-cleanup-initial:1m}")
    public void clearExpired() {
        jdbc.update("UPDATE github_observer_configuration SET token_cipher=NULL,enabled=0,revision=?,updated_at=? WHERE token_cipher IS NOT NULL AND expires_at<=?",
                UUID.randomUUID().toString(), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }
}
