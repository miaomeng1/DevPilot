# 自动接入项目 · Repository onboarding

入口：发布中心 → **自动接入新项目**（管理员）。生产发布始终保留人工确认。

## 使用流程

1. 输入 GitHub / GitLab 仓库地址和仓库 Token，以及 Dokploy / Coolify 地址和 API Key。
2. 检查连接。向导读取默认分支、Dockerfile、测试类型、可访问的部署项目和服务器；不会执行仓库代码。
3. 确认业务服务器、端口、健康路径、环境变量。默认自动创建专用项目和 production 环境，也可以选择现有环境。
4. 审阅生成的 workflow，确认允许配置变更，然后开始接入。
5. 后端分步创建部署应用、配置运行参数、保存加密的发布目标、写入独立生产环境的 CI Secrets，并创建 PR / MR。
6. 审阅并合并 PR / MR。后续 push 自动检查和构建镜像；GitHub `Run workflow` 填入成功 push 构建的 `build_run_id` 后，校验构建凭证并发布原始 digest，不重新构建。GitLab 使用手动生产任务确认。
7. 平台完成部署后，DevPilot 必须同时看到目标镜像正在关联容器内运行、以及新的健康检查通过，才标记 `HEALTHY`。

`AWAITING_MERGE` 只表示接入配置已提交，**不等于镜像已构建、生产已部署或端到端已验收**。

GitHub 构建凭证保存 90 天，包含镜像摘要、仓库路径、提交和构建 Run ID。发布任务要求同仓库、同 workflow、默认分支上的成功 push 构建，凭证缺失/过期或校验不符即停止，不会降级为 tag 部署。已接入的旧 workflow 不会被系统静默覆盖，需要通过仓库变更更新模板。GitLab/Coolify 不因此视为已完成真实 digest 验收。

## 接入范围与必要输入

- 当前向导面向根目录有 Dockerfile 的单服务仓库，支持 npm、Maven、Go 或 Dockerfile `test` stage 测试入口。业务启动命令、数据库迁移、数据卷和多服务依赖不是可以安全猜测的配置，需先在项目中定义。
- npm 项目在创建部署资源前检查非空 `scripts.test` 和有效 JSON 格式的 `package-lock.json`（含 `lockfileVersion`）；锁文件与依赖是否一致、测试是否有效仍由 `npm ci` / `npm test` 判定。使用 pnpm/yarn 的项目可自行提供 Dockerfile `test` stage，向导不擅自更换包管理器。
- 新版 Agent 通过 Linux `/proc/net/tcp*` 采集 TCP 监听端口，在心跳中上报。自动接入要求所绑定服务器在线且采集不超过 30 秒；未知/旧 Agent 数据不视为空闲。Agent 必须直接运行在目标宿主机，或使用宿主机网络命名空间；绑定的 DevPilot 服务器必须与 Dokploy 目标一致。该检查与 Dokploy 应用发布端口检查叠加，但不覆盖其他管理器设置的纯 NAT 规则，也无法预留端口防止并发抢占。
- 同时检查 Agent 最近保存的运行中 Docker 容器发布映射（含 Swarm）。内部 `8080/tcp` 不代表占用宿主机 8080；`18080→8080/tcp` 才代表发布 TCP 18080。Docker 快照可能滞后，仍需由实际部署确认端口绑定结果。
- 目标服务器必须已经安装 Docker、部署平台和对应 Agent。平台目标和 Agent 必须是同一台业务机器；界面明确要求确认。
- 服务器端口将被发布为 TCP 端口；请检查端口占用和防火墙。域名、DNS、TLS 与外部数据库账号需要用户提供或提前准备。
- GitHub 使用 GHCR；GitLab 使用该实例的 Container Registry。新包不会被自动公开，既有 GHCR 包的 Actions 写权限仍受 GitHub 权限模型约束。
- Dokploy 可自动写入逐应用私有 Registry 拉取凭据。Coolify 官方采用目标服务器用户的 Docker 登录凭据；目前向导不会代替用户通过 SSH 写入该凭据。私有镜像需预先登录，或使用 Dokploy 路径。
- GitLab 发布分支需受保护。系统不会自动改变现有成员的分支写入权限。
- GitHub 自动接入目前支持 github.com。自托管 GitLab 使用仓库所属 HTTPS 域名的 `/api/v4`，不支持挂载在子路径下的实例。

因此“自动接入”是**授权和必要业务参数确认之后，自动执行配置**，不是无账号授权、无基础设施或任意代码仓库零配置运行。

## 授权、重试和数据保留

仓库 Token 需要创建分支、提交 workflow、创建 PR/MR、管理生产环境 Secrets 的权限。GitHub 细粒度 Token 至少涉及 Contents、Workflows、Pull requests、Environments 和 Secrets 写权限；GitLab 使用具有相应项目权限的 `api` Token。

GitHub 检测会只读查询当前 REST core 剩余配额，少于 40 次或无法确认时停止；若仓库明确返回没有 push 权限，也会提前拒绝。该检查不是配额预留，不能保证后续不受二级限流影响，也不能证明细粒度 Token 的所有写权限。Dokploy Key 配额仍需按下述要求确认。

Dokploy API Key 还需要足够的请求配额：v0.30.3 默认可能只有每天 10 次请求，耗尽后返回 401，而非 429。自动接入和持续部署轮询会超过该配额；创建 Key 时应设置适当限流及有效期。若读取成功后很快出现 401，请同时检查平台日志中的 `Rate limit exceeded`，不要仅重复更换密码。

Dokploy 配额由管理员在接入前人工确认（已接受的产品边界），不标记为自动验证。向导需要单独勾选配额与有效期确认项；后端要求 `providerQuotaConfirmed: true`，缺失或为 false 时拒绝创建接入任务。创建任务后的 Key 更换仍须管理员重新核对该 Key 的限制，旧确认不证明新 Key 的配额。

Dokploy 创建资源前使用 `user.getPermissions` 检查 service、environment、server、deployment 和 envVars 的必要权限；创建专用项目时还检查 project/environment create。缺失或不能确认会停止，系统不会提升权限。这不代表资源范围限制、Key 配额或未来请求一定通过。v0.30.3 实测 API Key 请求 `/api/auth/api-key/list` 返回 401，项目响应没有配额头，因此不能自动推断剩余额度。

接入参数使用现有 AES-GCM 主密钥加密；API 不回传凭据；审计记录脱敏 Token、Registry 密码、业务变量和 workflow 正文。成功创建 PR/MR 后清除暂存的仓库凭据。未完成任务的暂存凭据在 24 小时后由每小时运行的清理任务清除，状态变为 `EXPIRED`。

每一步保存完成进度；同一应用只能创建一项接入任务。客户端中断时先查询原任务状态；重试核对专用项目、应用、分支和 PR/MR，不会无条件重复创建。执行中的任务使用租约防止双击并发。失败时可更新凭据并继续；已过期任务不能继续使用已清除的凭据。资源不会因接入失败被自动删除，需由操作者核对后处理。

Dokploy 应用已配置后，在尚未完成的接入任务中更新 Registry 密码，会同步平台拉取凭据，保留当前镜像 URI，不重建应用、端口或环境。更新需要原任务中已有 Registry 用户名；平台同步失败则不把本地凭据更新标记为成功。

GitHub 在独立分支增加应用专属 workflow，遇到现有同名文件不会覆盖。GitLab 在独立分支保留原 CI 文件内容，通过 `.post` 阶段的独立子流水线接入，避免覆盖现有 jobs、stages 和 variables。接入不自动合并，也不自动触发生产发布。

## 发布状态的证据

新接入应用默认关闭自动回滚。管理员在发布配置中明确开启后，失败发布才自动尝试恢复上一健康镜像；回滚任务失败不会继续自动回滚。没有上一健康版本时不能凭空恢复。数据迁移和数据卷不会随镜像回滚，需按业务自己的备份/迁移方案处理。已有配置的选择保持不变。

- “凭据已保存”：仅表示字段存在，没有证明网络或权限有效。
- “平台连接已验证”：实际读取目标应用并比对资源 ID；15 分钟后视为过期证据，可重新验证。失败项阻断发布，未经验证项显示 WARN。
- “签名回调已验证”：至少收到过有效签名 CI 回调；没有回调时显示 WARN，不会因生成密钥就显示通过。
- API 部署：Dokploy / Coolify 都需要 Provider 成功、目标容器镜像一致、最新容器采集和 Provider 完成后的健康探测。旧版本 HTTP 200 不足以证明上线。
- Webhook 模式不能提供同等的精确镜像与 Provider 完成保证，推荐 API 模式。

## 本地验证

运行 `make test` 和 `make cicd-verify`。测试包括容器替换重关联、Coolify 旧健康响应、无容器首次创建、接入步骤重试、暂存凭据清除、GitLab 原配置保留和 GitHub Sealed Box 加密。

`OnboardingUiSmokeApplication` 是 **test classpath 专用** 的隔离 UI 测试入口，使用 H2 和模拟仓库/平台客户端，不会访问真实 GitHub / GitLab / 部署平台，不会包含在生产镜像中。其完成状态不代表真实外部验收。启用该入口时必须显式使用 `src/test/resources/application.yml`，绑定 `127.0.0.1`，并在测试结束后停止。

真实验收仍需在指定业务仓库和目标服务器验证：授权、PR/MR 合并、镜像发布、人工确认、签名回调、容器替换、健康检查和失败回滚。

## 上游接口参考

- [GitHub Actions Secrets API](https://docs.github.com/en/rest/actions/secrets)
- [GitLab Repository Files API](https://docs.gitlab.com/api/repository_files/)
- [GitLab Project Variables API](https://docs.gitlab.com/api/project_level_variables/)
- [Dokploy Application API](https://docs.dokploy.com/docs/api/reference-application)
- [Coolify Docker Image Application API](https://next.coolify.io/docs/api/endpoints/applications/create-dockerimage-application)
- [Coolify Registry Credentials](https://coolify.io/docs/knowledge-base/docker/registry)
