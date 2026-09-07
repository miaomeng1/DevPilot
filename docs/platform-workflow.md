# DevPilot 仓库自身的 CI/CD

`.github/workflows/cicd.yml` 有两类用途，不能混为一谈：

- **平台镜像交付（默认启用）**：PR 运行测试、安全门禁及双架构构建，但不推镜像；main/master push 和 v* tag 通过门禁后推送 Server、Web、Agent 镜像，并分别保存 digest 凭证。
- **Web 镜像的独立部署演示（显式启用）**：只将 Web 作为受管应用接入发布中心，验证人工确认及 digest 发布。这不是 DevPilot 全平台升级，不处理 Server、数据库或迁移。完整平台升级必须使用安装/备份/升级流程。

普通业务项目仍使用接入向导生成的独立 workflow，无需复制这个三组件流水线。

## 可选 Web 发布的配置

1. 在 DevPilot 为独立 Web 应用配置仓库、受保护分支、Dokploy 资源、端口及 `/healthz` 健康检查。镜像为 `ghcr.io/<owner>/devpilot-web`；是否公开镜像由所有者决定，本 workflow 不修改可见性。
2. 在 GitHub 创建 `build-status-platform-web` Environment，仅配置这个应用的 `DEVPILOT_BUILD_CALLBACK_URL` 和派生的 `DEVPILOT_BUILD_CALLBACK_SECRET`。构建密钥不能复用生产发布密钥。
3. 在 `production` Environment 配置同一应用的 `DEVPILOT_CICD_CALLBACK_URL` 和 `DEVPILOT_CICD_CALLBACK_SECRET`，按需增加 Required reviewers；保护 main/master 分支。页面保存不等于回调连通，须看到真实回调证据。
4. 设置仓库 Actions variable `DEVPILOT_SELF_DELIVERY=true`。未设置时仅构建镜像，不上报该演示应用，也不触发部署。不要将平台管理端点作为 Web 应用的健康检查地址。

这几步仅配置这个仓库的可选自演示入口；不会自动为其他仓库增加权限或 Secrets。现有接入向导生成的 Environment 名称可能不同，不能直接混用。

## 构建、恢复回调和人工发布

- main/master push 执行质量、安全、三个组件镜像任务；构建开始/结束由不具备部署权限的密钥上报。tag 构建保留镜像凭证，但不能从此入口直接生产发布。
- 每个组件的产物 `devpilot-release-platform-<component>-<run_attempt>` 保存镜像 digest、commit、仓库、分支、Run ID 和 attempt，保留 90 天。复用已有 sha 标签时保存当次读取到的准确 digest；标签本身不是不可变凭证，发布不重新解析标签。
- 构建回调丢失时，Run workflow 选择 `recover_build`，填写原 **push** 的 `build_run_id`。系统先核验原工作流及五个成功任务，再从该 attempt 的 Web 产物恢复构建记录；不会部署。构建任务确实失败时不能借此绕过门禁。
- 在 DevPilot 发布中心核对构建并确认，取得 24 小时内有效的 `manual_approval_id`。Run workflow 选择 `release`，填写原 `build_run_id` 与确认 ID。发布任务不重建，只发送原 digest，并绑定构建、确认及本次发布 Run；后端仍需校验确认的归属、有效期及幂等性。仅点击 Run workflow 不是充分的发布授权。
- 产物过期、缺失、门禁不成功或凭证不匹配时拒绝发布，不能回退到 tag。重新构建后必须重新确认。

## 验证范围

本地 `node --experimental-strip-types scripts/test-platform-workflow.mjs` 运行实际 workflow Shell 片段，使用合成 GitHub 响应测试来源、五个门禁、产物、签名及构建回调确认；还覆盖八种生成模板的构建上报。不会访问 GitHub、推镜像或部署。`node --experimental-strip-types scripts/test-github-workflow-lint.mjs` 还检查主 workflow 及生成模板的 Actions 语义。

构建回调除了要求 HTTP 成功，还要求业务响应成功、Run/commit 对应，并在构建成功时确认相同 digest 及通过的质量/安全结果。恢复构建还必须返回待人工确认状态，不接受正在部署的响应。遇到代理返回 HTML、错误 Run 或 HTTP 200 内的业务错误，任务会失败，不能显示“上报成功”。开始回调的幂等重放可接受同一构建的终态，避免已完成记录被误判为连通故障；不要求服务端退回 RUNNING。

这些检查不能替代远端 GitHub Actions 验收。本次调整尚未推送或 dispatch；原先业务演示仓库的成功记录不能当成本文件新版本已经通过的证据。
