package com.devpilot.server.cicd.service;

/** Fixed text only: never reflect upstream errors or credentials into user guidance. */
final class GithubObservationGuidance {
    private GithubObservationGuidance() {}

    static String describe(String code) {
        if (code == null) return unknown();
        return switch (code) {
            case "ACTIVE" -> "GitHub 本次任务仍在排队或运行，请查看任务详情；这不是应用在线证明。";
            case "FAILED" -> "GitHub 本次任务失败，请打开构建日志定位测试、扫描或构建错误；本次核对没有触发部署。";
            case "CANCELLED" -> "GitHub 本次任务已取消；如需继续，请核对取消原因后在 GitHub 发起新运行，本次核对不会重跑任务或部署。";
            case "SUCCESS_AWAITING_BUILD_EVIDENCE" -> "GitHub 本次任务成功，但平台仍缺测试、安全扫描及原始镜像 digest 的完整构建证据。请检查构建结果回调；证据补齐并人工确认前不能发布。";
            case "CREDENTIAL_REQUIRED", "CREDENTIAL_OR_PERMISSION" -> "无法凭当前授权读取 GitHub。请管理员检查 Token 有效期和本仓库 Actions 只读权限，必要时更新自动核对凭据；这不代表构建失败。";
            case "RATE_LIMITED" -> "GitHub 请求受限，自动核对已延后。请等待后续核对，避免连续手动重试；当前查询不能证明构建结果。";
            case "RUN_NOT_ACCESSIBLE" -> "无法读取指定运行。请核对仓库访问权限及任务是否已删除；不要据此判断构建失败。";
            case "INVALID_OR_MISMATCHED_EVIDENCE", "INVALID_RESPONSE", "INVALID_CONFIGURATION_OR_EVIDENCE" -> "核对证据无效或与本次构建不匹配。请检查仓库、分支、commit 和运行次数；该响应不能作为发布依据。";
            case "NETWORK_OR_TIMEOUT", "REMOTE_UNAVAILABLE", "INTERRUPTED" -> "GitHub 连接异常、超时或查询中断。请检查平台出站网络并等待后续核对；不能据此判断构建失败或应用离线。";
            default -> unknown();
        };
    }

    private static String unknown() {
        return "GitHub 核对结果未知，请打开任务详情并检查构建回调；不会根据未知结果发布或判定应用在线。";
    }
}
