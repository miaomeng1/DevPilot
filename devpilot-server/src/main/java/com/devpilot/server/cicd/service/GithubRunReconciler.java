package com.devpilot.server.cicd.service;

import com.devpilot.server.application.mapper.ApplicationMapper;
import com.devpilot.server.automation.service.AutomationWebhookService;
import com.devpilot.server.cicd.mapper.CicdPipelineRunMapper;
import com.devpilot.server.security.SensitiveSettingCipher;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Never invokes deployment. Remote reads occur outside database transactions. */
@Service
@RequiredArgsConstructor
public class GithubRunReconciler {
    private final JdbcTemplate jdbc;
    private final ApplicationMapper applications;
    private final CicdPipelineRunMapper runs;
    private final SensitiveSettingCipher cipher;
    private final GithubRunClient client;
    private final TransactionTemplate transactions;
    private final AutomationWebhookService notifications;

    @Scheduled(fixedDelayString="${devpilot.cicd.github-observe-interval:30s}", initialDelayString="${devpilot.cicd.github-observe-initial:1m}")
    public void reconcile() {
        var ids = jdbc.queryForList("""
                SELECT o.application_id FROM github_observer_configuration o
                JOIN cicd_configuration c ON c.application_id=o.application_id
                WHERE o.enabled=1 AND o.token_cipher IS NOT NULL AND o.expires_at>?
                AND (o.next_check_at IS NULL OR o.next_check_at<=?) AND c.repository_provider='GITHUB'
                AND c.repository_url=o.repository_url AND c.branch_name=o.branch_name
                ORDER BY o.next_check_at, o.application_id LIMIT 5
                """, Long.class, now(), now());
        for (Long id : ids) checkApplication(id);
    }

    public void checkApplication(Long applicationId) {
        String lease = UUID.randomUUID().toString();
        int claimed = jdbc.update("""
                UPDATE github_observer_configuration SET check_lease=?,next_check_at=?
                WHERE application_id=? AND enabled=1 AND token_cipher IS NOT NULL AND expires_at>?
                AND (next_check_at IS NULL OR next_check_at<=?)
                """, lease, Timestamp.from(Instant.now().plusSeconds(120)), applicationId, now(), now());
        if (claimed != 1) return;
        var configurations = jdbc.queryForList("""
                SELECT o.revision,o.repository_url,o.branch_name,o.token_cipher FROM github_observer_configuration o
                JOIN cicd_configuration c ON c.application_id=o.application_id
                WHERE o.application_id=? AND o.check_lease=? AND o.enabled=1 AND o.expires_at>?
                AND c.repository_provider='GITHUB' AND c.repository_url=o.repository_url AND c.branch_name=o.branch_name
                """, applicationId, lease, now());
        if (configurations.isEmpty()) return;
        var config = configurations.getFirst();
        String revision = (String) config.get("revision");
        var candidates = jdbc.queryForList("""
                SELECT id FROM cicd_pipeline_run WHERE application_id=? AND status='RUNNING'
                AND external_run_id LIKE 'build:github-%'
                ORDER BY github_checked_at,started_at,id LIMIT 1
                """, Long.class, applicationId);
        if (candidates.isEmpty()) return;
        var snapshot = runs.selectById(candidates.getFirst());
        if (snapshot == null) return;
        String observation;
        int delay = 60;
        try {
            observation = client.observe((String) config.get("repository_url"), snapshot.getExternalRunId(),
                    snapshot.getCommitSha(), snapshot.getBranchName(), cipher.decrypt((String) config.get("token_cipher"))).name();
        } catch (GithubRunClient.Failure failure) {
            observation = failure.code;
            delay = Math.max(60, failure.retryAfterSeconds);
        } catch (Exception ignored) {
            observation = "INVALID_CONFIGURATION_OR_EVIDENCE";
            delay = 300;
        }
        final String result = observation;
        final int nextDelay = delay;
        transactions.executeWithoutResult(transaction -> {
            var application = applications.selectByIdForUpdate(applicationId);
            if (application == null) return;
            // Revocation, rotation, expiry or repository edits invalidate in-flight work.
            int updated = jdbc.update("""
                    UPDATE github_observer_configuration SET next_check_at=? WHERE application_id=?
                    AND revision=? AND check_lease=? AND enabled=1 AND expires_at>?
                    AND EXISTS (SELECT 1 FROM cicd_configuration c WHERE c.application_id=?
                        AND c.repository_provider='GITHUB' AND c.repository_url=github_observer_configuration.repository_url
                        AND c.branch_name=github_observer_configuration.branch_name)
                    """, Timestamp.from(Instant.now().plusSeconds(nextDelay)), applicationId, revision, lease, now(), applicationId);
            if (updated != 1) return;
            var current = runs.selectById(snapshot.getId());
            if (current == null || !"RUNNING".equals(current.getStatus())
                    || !Objects.equals(current.getUpdatedAt(), snapshot.getUpdatedAt())
                    || !Objects.equals(current.getCommitSha(), snapshot.getCommitSha())
                    || !Objects.equals(current.getExternalRunId(), snapshot.getExternalRunId())) return;
            LocalDateTime observedAt = LocalDateTime.now(ZoneOffset.UTC);
            jdbc.update("UPDATE cicd_pipeline_run SET github_checked_at=?,github_observation=? WHERE id=?", Timestamp.valueOf(observedAt), result, current.getId());
            if ("FAILED".equals(result) || "CANCELLED".equals(result)) {
                current.setStatus(result);
                current.setBuildResultSource("GITHUB_OBSERVATION");
                current.setDeployStatus("BUILD_FAILED");
                current.setSummary("GitHub 指定运行次数的只读核对确认：" + result + "；未触发部署");
                current.setGithubCheckedAt(observedAt);
                current.setGithubObservation(result);
                current.setCompletedAt(observedAt);
                current.setUpdatedAt(observedAt);
                runs.updateById(current);
                notifications.publishBuildFailure(current, application);
            }
        });
    }
    private static Timestamp now() { return Timestamp.from(Instant.now()); }
}
