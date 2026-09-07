package com.devpilot.server.cicd.service;

import com.devpilot.server.cicd.mapper.CicdConfigurationMapper;
import com.devpilot.server.cicd.mapper.CicdPipelineRunMapper;
import com.devpilot.server.exception.BusinessException;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Explicit diagnostic check: no state writes, no credential retention, no deployment. */
@Service
@RequiredArgsConstructor
public class GithubRunCheckService {
    private final CicdConfigurationMapper configurations;
    private final CicdPipelineRunMapper runs;
    private final GithubRunClient client;

    public record Result(Long buildId, String state, String message, int retryAfterSeconds, Instant checkedAt) {}

    public Result check(Long applicationId, Long buildId, String token) {
        var run = runs.selectById(buildId);
        if (run == null || !Objects.equals(applicationId, run.getApplicationId())) {
            throw BusinessException.notFound(40440, "本应用构建记录不存在");
        }
        var configuration = configurations.selectByApplicationId(applicationId);
        if (configuration == null || !"GITHUB".equals(configuration.getRepositoryProvider())) {
            throw BusinessException.badRequest(40041, "当前核对仅支持 GitHub push 构建");
        }
        try {
            var state = client.observe(configuration.getRepositoryUrl(), run.getExternalRunId(),
                    run.getCommitSha(), run.getBranchName(), token);
            String message = switch (state) {
                case ACTIVE -> "GitHub 确认本次任务仍在排队或运行；未修改本地发布状态";
                case FAILED -> "GitHub 确认本次任务失败；本次只读核对不会触发部署";
                case CANCELLED -> "GitHub 确认本次任务已取消；本次只读核对不会触发部署";
                case SUCCESS_AWAITING_BUILD_EVIDENCE -> "GitHub 已完成，但仍需测试、安全扫描及原始镜像 digest 证据；不能直接发布";
                case UNKNOWN -> "GitHub 返回的状态不足以确定结果；保留未知状态并检查任务详情";
            };
            return new Result(buildId, state.name(), message, 0, Instant.now());
        } catch (GithubRunClient.Failure failure) {
            String message = switch (failure.code) {
                case "CREDENTIAL_REQUIRED", "CREDENTIAL_OR_PERMISSION" -> "请提供本仓库 Actions 只读权限的有效凭据；本接口不保存凭据";
                case "RATE_LIMITED" -> "GitHub 请求受限，请等待建议时间后重试";
                case "RUN_NOT_ACCESSIBLE" -> "无法读取指定运行；请检查仓库权限以及运行是否仍存在";
                case "INVALID_OR_MISMATCHED_EVIDENCE", "INVALID_RESPONSE" -> "响应与本次构建不匹配或无效，不能作为发布依据";
                default -> "GitHub 连接异常或请求中断；未修改本地状态，请稍后核对";
            };
            return new Result(buildId, failure.code, message, failure.retryAfterSeconds, Instant.now());
        } catch (IllegalArgumentException invalid) {
            throw BusinessException.badRequest(40041, "该记录缺少可验证的 GitHub push 运行身份，无法自动核对");
        }
    }
}
