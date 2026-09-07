package com.devpilot.server.cicd.onboarding;

import com.devpilot.server.exception.BusinessException;

/** Local compatibility check only; passing does not prove registry access. */
final class RegistryCredentialPolicy {
    private RegistryCredentialPolicy() { }

    static void validate(String imageRepository, String password) {
        if (imageRepository == null || password == null) return;
        String host = imageRepository.trim().split("/", 2)[0];
        if ("ghcr.io".equalsIgnoreCase(host) && password.trim().startsWith("github_pat_")) {
            throw BusinessException.badRequest(40070,
                    "GHCR 私有镜像拉取不支持 Fine-grained PAT。请使用 Token (classic)，仅授予 read:packages；不要复用仓库管理 Token。保存后仍需验证指定镜像的拉取权限。");
        }
    }
}
