# 自动接入项目 · Repository onboarding

入口：发布中心 → **自动接入新项目**（管理员）。生产发布始终保留人工确认。

## 当前验证范围（2026-09-07）

本文说明当前工作树的操作方式，不是“稳定版全部验收通过”的声明。先区分以下范围：

- 已有私有 GitHub 演示项目完成 push 测试/扫描、私有双架构 GHCR 构建、DevPilot 人工确认、Dokploy 发布原 digest 和 Agent 健康关联；构建 run `34017861081`，发布 run `34019657199`。此前配置曾经过人工修复，因此不能作为新用户全自动接入的证据。
- V38 已在专用私有仓库 `devpilot-onboarding-acceptance` 跑通从无 CI 开始，经运行中页面创建应用/部署配置/Secrets/PR、人工合并、push 构建、页面人工确认到原 digest 发布健康的成功路径。push run `34035719296`、release run `34037265942`。该测试在本地隔离 Linux 环境完成，Agent 手动启动、回调使用临时隧道；不代表真实主机自启动、永久公网配置或所有故障路径已验收。
- V38 应用创建去重已通过真实 MySQL 升级、服务重启和空数据库独立恢复后的请求重放；不等于完整接入任务所有超时情形均已验证。
- 专用新应用已在用户明确开启自动回滚后完成故障验收：故障 push `34038096535`、发布 `34038224252`；故障容器 unhealthy，DevPilot 未把仍健康的旧容器误判为新版本成功，验证超时后自动回滚到原 onboarding-v1 digest，回滚记录 HEALTHY、故障发布 ROLLED_BACK。Swarm 自身先发生回滚，DevPilot 随后完成独立回滚任务及新健康验证；不代表所有故障类型均已覆盖。
- 构建开始/结束回调完全丢失后，已通过 `recover_build` 补报原 attempt 制品，重复恢复不创建重复记录、不触发部署，见 [构建恢复](github-observation.md)。另有独立 Linux VM 的平台/Agent 自启动及配置、告警历史重启验收；不能把该 VM 的证据当作本段 Dokploy 业务主机也完成了内核重启。
- 最新工作树模板不自动更新旧仓库；历史演示成功不能替代最新模板的远端验收。GitLab/Coolify 保留兼容实现，未完成真实端到端验收。其余异常路径及正式稳定版交付仍未全部完成。

可复核范围及历史故障见 [验收记录](stability-validation.md)。详细历史放在该记录中，下面的使用说明不要求用户追随开发过程逐次操作。

## 使用流程

1. 输入 GitHub / GitLab 仓库地址和仓库 Token，以及 Dokploy / Coolify 地址和 API Key。

   私有 GHCR 的**拉取凭据与仓库管理 Token 分开**：使用 GitHub Token (classic)，仅授予 `read:packages` 并设置有效期。Fine-grained PAT 不适用于此 GHCR 认证路径；后端会在创建接入任务或更新凭据时拦截已知不兼容类型。这个本地类型检查不证明令牌有效、未过期或可读取指定镜像，保存成功、Registry 登录成功也不等于镜像可拉取。请勿把 Token 发送到聊天、提交到 Git 或截图公开。

   Dokploy 的 Docker 镜像源使用拉取用户名、密码和 Registry URL。不要为了拉取已经构建好的 `@sha256:...` 镜像而额外启用重新打标签/推送到 Registry 的流程；该流程可能因 digest 不能作为新标签而失败。失败后保留原版本和发布记录，先纠正拉取配置，再对同一构建执行明确的发布重试。
2. 检查连接。向导读取默认分支、Dockerfile、测试类型、可访问的部署项目和服务器；不会执行仓库代码。
3. 确认业务服务器、端口、健康路径、环境变量。默认自动创建专用项目和 production 环境，也可以选择现有环境。
4. 审阅生成的 workflow，确认允许配置变更，然后开始接入。
5. 后端分步创建部署应用、配置运行参数、保存加密的发布目标、写入独立生产环境的 CI Secrets，并创建 PR / MR。
6. 审阅并合并 PR / MR。后续 push 自动检查和构建镜像；先在 DevPilot 确认待发布构建，再在 GitHub `Run workflow` 选择 `operation=release`，填写原 push 构建的 `build_run_id` 和生成的 `manual_approval_id`。发布任务校验原 attempt 的质量、扫描、镜像任务及制品，发布原始 digest，不重新构建。仅需补报丢失记录时使用 `operation=recover_build`，详见 [构建记录恢复](github-observation.md)。GitLab 使用手动生产任务确认。
7. 平台完成部署后，DevPilot 必须同时看到目标镜像正在关联容器内运行、以及新的健康检查通过，才标记 `HEALTHY`。

`AWAITING_MERGE` 只表示接入配置已提交，**不等于镜像已构建、生产已部署或端到端已验收**。

GitHub 构建凭证保存 90 天，包含镜像摘要、镜像仓库、代码仓库、分支、事件、提交、构建 Run ID 和 run attempt。制品名称按 attempt 隔离；发布任务只下载已核对的构建次数对应的制品，并校验全部身份字段。发布任务要求同仓库、同 workflow、默认分支上的成功 push 构建，凭证缺失/过期或校验不符即停止，不会降级为 tag 部署。已接入的旧 workflow 不会被系统静默覆盖，需要通过仓库变更更新模板。更新后应完成一次新的 push 构建；旧版不带 attempt 的制品不能用于新版发布任务，不应手工改名或补造字段来绕过校验。GitLab/Coolify 不因此视为已完成真实 digest 验收。

当前生成模板的 HIGH/CRITICAL 扫描不忽略未修复漏洞。上游尚无补丁的漏洞也会阻止门禁通过；应调整依赖或基础镜像并重新扫描，不能将跳过漏洞作为默认解决方案。已有演示构建的扫描证据只覆盖那次提交；修改模板或项目后应重新通过门禁，现有远端 workflow 不会自动改变。

发布模板同时核对发布 run 本身的 event=workflow_dispatch、Run ID、run attempt、默认分支和仓库，避免拿到其他任务或旧 attempt 的记录后继续发送证据。注意 workflow_dispatch 也可经 API 调用，因此这一校验不单独证明有人在网页上点击审批。现有回调字段 approvalActor/approvedAt 保留兼容名称，但模板当前填入的是 CI triggering_actor 与 run_started_at，页面明确按“CI 发起人/任务开始”显示，不能用于宣称实际人工审批已验收。

### 在哪里确认发布

在 DevPilot 的 CI/CD 发布中心选择应用，展开“人工确认 · Release approval”，选择待发布构建并核对目标后勾选确认。人工确认人来自当前登录用户，确认时间来自 DevPilot 服务器 UTC 时间；不要用 GitHub 任务开始时间或 Environment 创建/更新时间替代。GitHub 的 CI 发起信息与 DevPilot 人工确认记录是两类独立证据。

平台内人工确认接口：ADMIN/DEVELOPER 可 POST `/api/cicd/applications/{applicationId}/builds/{buildId}/approval`，其中 buildId 是平台构建行 ID，不是 GitHub Run ID；提交 UUID requestId、明确勾选对应的 confirmed=true、所看到的 commitSha、完整 imageUri@sha256:digest，以及先前 approval-context 返回的 expectedFingerprint。接口从登录身份获取确认人，用服务器 UTC 时间记录确认，保存环境、目标服务器和 CI/CD 配置更新时间。只接受本应用当前配置分支、测试/扫描通过的成功构建；相同请求重试不会重写确认人或延长 24 小时窗口。GET `/api/cicd/applications/{applicationId}/release-approvals` 可查看最近记录。

确认接口自身不会触发部署或调用 GitHub。过期请求重试仍返回原历史记录，不能据此视为有效发布授权；发布消费会核对过期时间及目标配置是否变化。确认后还需按上面的流程执行原构建对应的 GitHub 手动发布任务。

未使用的确认可由 ADMIN/DEVELOPER DELETE `/api/cicd/applications/{applicationId}/release-approvals/{approvalId}` 撤销，保留历史和撤销时间；已经绑定发布的确认拒绝撤销，避免把它误当成取消部署。发布前核对过期/撤销、构建证据、配置指纹、环境变量 revision，并只允许绑定一个发布 Run ID。同一 Run 重试仍需通过目标与有效期检查，不会延长确认窗口。指纹不使用业务健康时间等观测字段，也不向接口返回加密凭据内容。

确认校验覆盖普通 RELEASE 回调和队列启动点。成功回调必须携带 `manualApprovalId` 和 `buildExternalRunId` 才能进入部署；缺失时记录 `AWAITING_APPROVAL`，不调用部署平台。队列真正启动前重新消费校验，过期/失效确认退回待确认。待确认的相同发布可补充新确认后重试，已经提交的发布不能更换确认。部署发起人取自确认记录，不取配置创建人。

未传递这些字段的旧 workflow 会停在待确认，需审阅更新模板。不要因为旧流水线显示绿色就跳过门禁，也不要将工作树候选称为已验收稳定版。Promote/人工回滚保留原认证用户操作入口，自动回滚仍受显式配置控制，不因此自动扩权。

最新源码已提供“人工确认 · Release approval”面板：选择通过测试和扫描的 digest 构建，点击核对目标，勾选确认。页面先 GET `/api/cicd/applications/{applicationId}/builds/{buildId}/approval-context` 获取当前应用/环境/服务器/构建与配置指纹；确认 POST 必须携带 `expectedFingerprint`。页面打开后配置变化则拒绝旧快照，不能悄悄确认另一目标。重试标识与快照在浏览器按用户、应用、构建隔离保存；响应丢失后重新选择同一构建可恢复，成功后清除标识，记录可重新查询。配置冲突需明确结束旧请求、重新读取目标并再次勾选。

确认记录显示服务器记录的用户、UTC 时间、目标环境、digest、确认 ID、过期/撤销/使用状态；未使用可明确撤销。GitHub 新模板在 operation=release 时要求 `build_run_id` 和 `manual_approval_id`，通过环境变量传递，先检查 UUID 格式，再放入 HMAC 签名回调。确认不触发构建或部署，不扩大 GitHub 权限。旧工作流需审阅合并更新后才能提供该输入；复制 ID 不代表它仍有效，后端发布前继续核对指纹、有效期和使用情况。GitLab 模板通过 `DEVPILOT_MANUAL_APPROVAL_ID` 传递确认，操作见文末兼容路径；该实现尚未通过真实 Runner 验收。

### 构建状态与生产发布分离

新版 GitHub 模板在 push 开始、质量/扫描/镜像任务结束后，向 `/api/cicd/webhooks/{code}/builds` 上报签名构建状态。成功必须通过测试和扫描并提供镜像证据，发布中心显示“待人工发布”，即使应用启用自动部署，该接口也不会创建部署任务。

自动接入额外创建独立 `build-status-{应用编码}` 环境，只写入 `DEVPILOT_BUILD_CALLBACK_URL` 和 `DEVPILOT_BUILD_CALLBACK_SECRET`，不写入生产发布密钥。构建上报 job 不检出代码，生产任务仍在 `production-{应用编码}` 环境读取生产密钥。不要给构建状态环境添加生产审批，否则状态上报也会等待审批；自动接入不会修改已有环境的保护规则。

状态密钥通过生产密钥的 HMAC-SHA256 派生，仅状态接口接受，不能代替生产密钥。轮换生产密钥会同时使旧状态密钥失效，必须同步更新两处 Secrets。手动配置用户可在轮换时复制页面单独显示的一次性状态密钥。状态上报成功不会标记生产回调验证成功。

构建 ID 使用 `build:github-RunID-Attempt`，与生产发布 ID 隔离。终态后迟到的 RUNNING 和重复上报不改变结果；重新运行使用新的 Attempt。取消整个 workflow 时 GitHub 可能无法执行最终上报，此时仍需结合 GitHub 原始任务判断，不能把缺失终态视为上线成功。已有演示的成功状态上报通过，不代表所有取消、丢回调及恢复情形都已验收。

新版生产任务在签名回调中携带 `buildExternalRunId`、`approvalActor`、`approvedAt`，后端要求对应构建属于同一应用、已成功且 commit/image URI 完全一致，否则拒绝发布。页面显示来源构建和 CI 上报的发起人/任务开始时间；数据来自 GitHub Run API 的 `triggering_actor` 与 `run_started_at`（缺失时回退 actor/created_at），不是独立核实的 GitHub Environment reviewer 审批日志。环境审批人的点击事件和准确点击时间仍需另行采集。

同一已完成发布的构建来源、镜像和发起人不能重写，重复回调返回原记录、不重复部署。旧 workflow 缺失的关联信息不会被猜测补齐；保留旧接口或记录不代表允许绕过当前发布门禁。成功的构建状态事件只接受 digest，不接受 sha-* tag。

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

Dokploy 接入配置重试会先核对远端应用 ID，并保留已存在的 `dockerImage`（包括完整 digest）；只有空镜像才初始化为 `pending-first-release`。超时后应恢复原任务，不要新建应用。已有相同端口映射会复用。此保护不等于与 Dokploy 控制台并发修改时的原子比较更新；接入期间避免同时从其他入口变更同一应用配置。

端口复用要求发布端口、容器端口、TCP 协议和 ingress 发布模式全部一致。同一发布端口目标不同、协议/模式不同、重复映射，或无法读取端口列表时，配置阶段会在写入拉取凭据、端口和环境变量之前停止。请在 Dokploy 核对原应用，不要靠重复新建任务绕过冲突；其他发布端口的映射不会被删除。创建端口成功但响应丢失后，恢复原任务会重新读取并复用该映射。这不是服务器全部进程的端口占用检测，也不保证阻止外部同时修改配置。

环境变量配置会保留远端 `buildArgs`、`buildSecrets` 和 `createEnvFile`。已保存的变量文本与接入计划完全一致时跳过重复写入；若不一致或平台未返回完整字段，则在任何配置写入前停止并提示核对。不会猜测或自动合并任意 dotenv 文本，即使仅格式不同也需要先审阅；不要通过清空已有变量来绕过检查，确认需要保留的配置后再恢复原任务。

仓库 Token 需要创建分支、提交 workflow、创建 PR/MR、管理生产环境 Secrets 的权限。GitHub 细粒度 Token 至少涉及 Contents、Workflows、Pull requests、Environments 和 Secrets 写权限；GitLab 使用具有相应项目权限的 `api` Token。

GitHub 检测会只读查询当前 REST core 剩余配额，少于 40 次或无法确认时停止；若仓库明确返回没有 push 权限，也会提前拒绝。该检查不是配额预留，不能保证后续不受二级限流影响，也不能证明细粒度 Token 的所有写权限。Dokploy Key 配额仍需按下述要求确认。

Dokploy API Key 还需要足够的请求配额：v0.30.3 默认可能只有每天 10 次请求，耗尽后返回 401，而非 429。自动接入和持续部署轮询会超过该配额；创建 Key 时应设置适当限流及有效期。若读取成功后很快出现 401，请同时检查平台日志中的 `Rate limit exceeded`，不要仅重复更换密码。

Dokploy 配额由管理员在接入前人工确认（已接受的产品边界），不标记为自动验证。向导需要单独勾选配额与有效期确认项；后端要求 `providerQuotaConfirmed: true`，缺失或为 false 时拒绝创建接入任务。创建任务后的 Key 更换仍须管理员重新核对该 Key 的限制，旧确认不证明新 Key 的配额。

Dokploy 创建资源前使用 `user.getPermissions` 检查 service、environment、server、deployment 和 envVars 的必要权限；创建专用项目时还检查 project/environment create。缺失或不能确认会停止，系统不会提升权限。这不代表资源范围限制、Key 配额或未来请求一定通过。v0.30.3 实测 API Key 请求 `/api/auth/api-key/list` 返回 401，项目响应没有配额头，因此不能自动推断剩余额度。

接入参数使用现有 AES-GCM 主密钥加密；API 不回传凭据；审计记录脱敏 Token、Registry 密码、业务变量和 workflow 正文。成功创建 PR/MR 后清除暂存的接入参数。未完成任务的暂存凭据在最近一次授权保存 24 小时后由每小时运行的清理任务清除，状态变为 `EXPIRED`。清除仓库 Token、部署平台 Key、Registry 密码和环境变量值，保留加密的接入计划、环境变量名、阶段及资源 ID。流水线正文作为计划保留，因此不要在 workflow 中硬编码密钥，应使用 Secrets 引用。

每一步保存完成进度；同一应用只能创建一项接入任务。客户端中断时先查询原任务状态；重试核对专用项目、应用、分支和 PR/MR，不会无条件重复创建。执行中的任务使用租约防止双击并发。失败时可更新凭据并继续；过期时展开“重新授权，恢复原任务”，重新提供仓库 Token、部署平台 Key，以及原计划需要的 Registry 密码。stage 0–2 还需重新填写原计划全部环境变量（变量名必须一致），Dokploy 配额需再次确认。重新授权保留原阶段、应用与资源 ID，不执行部署。空凭据请求不会续期。资源不会因接入失败被自动删除。

兼容限制：升级前已经被旧版清空整个接入计划的 EXPIRED 任务，无法自动重建丢失计划；页面授权更新会拒绝并提示已清除。不要删除远端资源来绕过这一限制，应核对既有资源后手工完成接入。升级时仍持有计划的任务会由 V26 记录凭据时间，之后可按上述方式恢复。

预检阶段（stage 0）失败时，可展开“端口冲突？修改预检参数”，修改容器端口、发布端口及健康检查路径。页面刷新时从原任务加载这些非敏感参数。保存只更新加密接入计划，不创建或部署资源；随后点击继续，重新执行预检。已进入资源创建阶段、凭据已清除或仍有有效执行租约的任务拒绝该操作，避免直接改动既有远端资源。后端入口为管理员专用 `PUT /api/cicd/onboarding/{applicationId}/ports`。

向导创建 DevPilot 应用后，立即将应用 ID 保存在页面查询参数中，再提交接入任务。因此接入任务请求失败后刷新页面仍可复用应用；地址不包含授权 Token。

V38 起还覆盖“创建应用本身的响应丢失”：在发送创建请求前，向导按登录用户保存 UUID 和原应用名称、编码、服务器、健康地址等非密钥参数。重新打开空白接入页会恢复这些参数，需重新填写仓库/平台授权并核对同一项目；重试传入原请求标识，返回同一个应用。请勿修改编码来绕过失败，也不要清空尚未核对的恢复记录。浏览器存储不可用、恢复记录损坏或非安全浏览器上下文（非 HTTPS 且非 localhost）时拒绝新建，不生成无法恢复的请求。

`POST /api/applications` 的可选 `requestId` 是 UUID，按认证用户隔离；同一标识必须重放相同请求参数。并发请求使用数据库用户行锁串行化，应用及去重记录在同一事务提交。参数改变或原应用已删除返回 40978，不自动重建；不同用户不复用对方的请求结果。未传 `requestId` 的已有客户端保持旧行为，不能宣称其重试也有同等保证。`application_creation_request` 只保存请求哈希、用户/应用 ID、请求标识和时间，不保存授权或环境变量；去重记录在应用删除后保留，避免旧请求重新生效，备份与恢复应保留该表。

这是新增数据库迁移，升级前须备份并审阅 V38。前端与后端应配套升级：旧后端不会提供此保证。不要仅替换 Web 镜像便声称创建恢复已启用。隔离环境已有真实 MySQL 升级、服务重启及独立恢复后重放证据，见本文顶部范围；真实主机重启及完整新用户接入不能由这些检查替代。

Dokploy 应用已配置后，在尚未完成的接入任务中更新 Registry 密码，会同步平台拉取凭据，保留当前镜像 URI，不重建应用、端口或环境。更新需要原任务中已有 Registry 用户名；平台同步失败则不把本地凭据更新标记为成功。

GitHub 在独立分支增加应用专属 workflow，遇到现有同名文件不会覆盖。GitLab 在独立分支保留原 CI 文件内容，通过 `.post` 阶段的独立子流水线接入，避免覆盖现有 jobs、stages 和 variables。接入不自动合并，也不自动触发生产发布。

## 发布状态的证据

新接入应用默认关闭自动回滚。管理员在发布配置中明确开启后，失败发布才自动尝试恢复上一健康镜像；回滚任务失败不会继续自动回滚。没有上一健康版本时不能凭空恢复。数据迁移和数据卷不会随镜像回滚，需按业务自己的备份/迁移方案处理。已有配置的选择保持不变。

- “凭据已保存”：仅表示字段存在，没有证明网络或权限有效。
- “平台连接已验证”：实际读取目标应用并比对资源 ID；15 分钟后视为过期证据，可重新验证。失败项阻断发布，未经验证项显示 WARN。
- “构建签名回调已验证”：收到过当前密钥的有效构建回调，展示 UTC 时间；不代表构建成功，也不代表发布回调可达。GitHub 状态查询不能替代签名回调证据。
- “发布签名回调已验证”：收到过当前密钥的有效发布回调；不代表部署或健康验证成功。首次发布前缺少该证据显示 WARN，仍需人工确认，不应为消除提示跳过审批。
- 两类历史验证都不能保证当前网络持续可用；轮换回调密钥会清除两类验证时间。V37 升级不从历史构建记录回填构建回调证据，需等待新的有效回调。
- 发布前检查的 Agent、健康和容器证据还会核对时间：心跳或容器清单超过 2 分钟、时间明显超前（超过 30 秒）时，不将历史在线/running/健康显示为当前验证通过。Agent 明确离线仍阻断发布；证据缺失或时钟异常提示核对，不直接判定应用故障。
- API 部署：Dokploy / Coolify 都需要 Provider 成功、目标容器镜像一致、最新容器采集和 Provider 完成后的健康探测。旧版本 HTTP 200 不足以证明上线。
- Webhook 模式不能提供同等的精确镜像与 Provider 完成保证，推荐 API 模式。

## 本地验证

运行 `make test` 和 `make cicd-verify`。测试包括容器替换重关联、Coolify 旧健康响应、无容器首次创建、接入步骤重试、暂存凭据清除、GitLab 原配置保留和 GitHub Sealed Box 加密。

`OnboardingUiSmokeApplication` 是 **test classpath 专用** 的隔离 UI 测试入口，使用 H2 和模拟仓库/平台客户端，不会访问真实 GitHub / GitLab / 部署平台，不会包含在生产镜像中。其完成状态不代表真实外部验收。启用该入口时必须显式使用 `src/test/resources/application.yml`，绑定 `127.0.0.1`，并在测试结束后停止。

真实验收仍需在指定业务仓库和目标服务器验证：授权、PR/MR 合并、镜像发布、人工确认、签名回调、容器替换、健康检查和失败回滚。

## 初始化创建服务器的重试

初始化页的 Agent“验证通过”要求服务器状态为 ONLINE，并且心跳处于配置的有效期内；近期心跳不能覆盖明确的 OFFLINE 状态。心跳或 Dokploy 验证时间比 DevPilot 当前时间超前超过 30 秒时显示“需要人工确认”，请核对主机时间并等待新心跳或重新验证连接。该提示不代表业务容器故障，也不会自动部署或重启。Dokploy 验证超过 15 分钟仍需重新验证；刷新状态只是读取现有证据，不发送平台验证请求。

初始化向导在创建前保存一个 UUID 请求标识和名称到当前管理员对应的浏览器存储，不保存 Agent Token 或安装命令。请求失去响应时，刷新后再次点击添加会继续该请求。服务器创建 API 的可选 `requestId` 按管理员隔离，相同标识与名称返回同一服务器；更改名称或服务器已删除时拒绝重用，不会暗中重新创建。

为恢复丢失响应，带请求标识的创建结果加密暂存 24 小时，只有原管理员通过同一请求才能重取。窗口结束后不再返回凭据，定期清除密文，但保留去重元数据。不要清空浏览器重试标识再反复创建；过期应先查看报错中的服务器 ID，核对已有记录。此机制不是永久查看 Agent 密钥的接口。需要新凭据时，在服务器列表找到原服务器并使用“重新签发 Token”，不要删除服务器重建。

旧版不带 requestId 的 API 调用保持原有行为。初始化向导和独立“服务器”页面共用按管理员保存的创建请求；未完成时切换入口也应恢复同一次操作。独立页面收到凭据后关闭窗口，才结束本次重试；关闭前请保存安装命令。该行为不意味着过期凭据可永久恢复，也不覆盖自行清空浏览器存储的情况。

原 Token 已撤销或服务器已删除时，同一请求不再返回安装凭据，也不会重新创建服务器。删除服务器会立即清除该服务器的创建结果密文，保留请求去重记录。不同管理员使用相同 requestId 不会读到彼此的创建结果；它们是独立请求，可能分别创建服务器，因此多人协作仍应先核对服务器列表。

创建窗口或初始化向导保留旧请求时，可以点击“核对原服务器（只读）”。页面只查询当前管理员的创建结果，显示原名称、服务器 ID 和可恢复/过期/已清除/已删除状态，不重新创建、不返回密钥。核对后勾选确认并点击“结束旧创建请求”，仅清除浏览器中的该次恢复标识，允许以后添加另一台服务器；不会删除原服务器或轮换 Token。没有找到记录或查询失败时不能凭空结束请求，应先保留原标识重试。对应只读接口为 GET `/api/servers/creation-requests/{requestId}`，仅管理员可用，且结果按创建管理员隔离。

### Agent 凭据重新签发

服务器列表中，管理员点击对应服务器的“重新签发 Token”，核对名称与 ID，勾选了解旧 Token 失效的影响后确认。网络错误时，关闭或刷新页面后再次打开同一服务器的签发窗口，使用“恢复同一次签发”。窗口不会自动提交，也不会把新 Token 保存到浏览器；成功后默认隐藏，可显示或复制，确认保存后关闭窗口结束恢复。

如果接口明确报告恢复窗口过期或版本冲突，可以勾选放弃旧结果恢复，点击“结束旧请求，重新核对”。此按钮只清除该次浏览器标识并读取当前版本，必须再次勾选确认并点击签发才会轮换。普通网络错误不会提供自动重新轮换行为。不要把新 Token 的签发成功当成 Agent 恢复在线，应在更新目标机配置后检查心跳。

管理员可先 GET `/api/servers/{id}/registration` 获取当前凭据版本 `revision`（不含 Token），再 POST 同一路径，提交 UUID `requestId`、`expectedRevision` 和 `confirmed: true`。成功返回同一服务器及新 Agent Token；旧 Token 全部撤销。仅查询版本不会改变任何配置。服务模板安装进行中禁止签发。

POST 请求标识及 expectedRevision 必须在发请求前保存；响应超时后重用原值，不要自动生成新标识或刷新版本后重发。同一管理员同一次请求在 24 小时内可以恢复签发结果；不同请求持有旧版本会被拒绝。恢复窗口过期或 Token 被再次替换后，原请求不会隐式再次轮换，需要核对最新服务器状态并重新明确确认。签发结果使用主密钥加密暂存，到期清除；删除服务器或下一次轮换也会清除旧结果密文，保留去重记录。

签发不会自动连接目标机。已有 Agent 应人工更新受保护配置文件中的 `agent.token`，保留其他自定义配置，然后重启 Agent 并验证注册/心跳。返回的安装命令适用于安装；现有安装脚本会重写配置，不能把重复执行安装命令当成无损修改 Token 的方式。旧 Agent 在配置更新前可能无法继续上报，服务器记录和应用不应因此被删除。

## 构建长期停留 Running

流水线 API 保留 CI 最后上报的 `status`，另返回 `observationStatus`。非终态超过 `devpilot.cicd.running-stale-after`（默认 `2h`）没有更新时，页面显示“状态未知 Stale”，而不是猜测成功、失败或取消。这是本地观察超时，不是直接查询 GitHub 的结果，也不影响发布权限、触发回滚或发送构建失败通知。

遇到提示，打开记录中的 CI 任务链接核对：任务仍在运行则继续等待；任务已结束则检查最终上报 job、构建状态环境的 URL/Secret 与 DevPilot 可达性。修复后让 CI 重新发送有效签名终态。终态被接受后提示消失，重复或迟到的 RUNNING 不会覆盖终态。合法长构建可适当调大上述 Spring 配置，但不能用放大超时掩盖失效回调。

`LAST_REPORTED` 仅表示近期收到状态，不证明任务此刻仍在执行；`TERMINAL_REPORTED` 表示已收到终态，不证明生产应用已经上线。由于整个 workflow 取消可能跳过最终上报，自动检测所有取消事件仍属于待完成事项。

当前生成的 GitHub 最终上报脚本区分取消与失败：存在 `failure` 时报告 FAILED；无失败但有 `cancelled` 时报告 CANCELLED；被取消或跳过的质量门禁记为 SKIPPED，不伪装为测试断言失败。只有测试、扫描和镜像构建全部成功且 digest 有效才报告 SUCCEEDED。该修改只影响新生成并合并的工作流，不自动更新已有仓库。整个 workflow 的最终报告任务未能运行时，仍按上述未知状态处理。

待发布指引要求先在 DevPilot 本页完成版本/目标确认，再执行对应 GitHub/GitLab 手动发布任务。发布失败不保证旧容器仍在运行，历史 HEALTHY 也不是当前持续健康的证明；应同时核对实际镜像、容器及最新健康数据。

## GitLab 人工确认兼容路径（尚未真实 Runner 验收）

GitLab 模板的 image 作业通过 Buildx 推送镜像并保存 `devpilot-build.json`，记录 project、pipeline、job、commit、branch、repository 和 digest。build_report 与 production 从同一 image 制品读取并核对这些字段，不使用可覆盖的 dotenv 变量作为镜像证据。制品保留 7 天，过期后不承诺仍能发布。

自动接入将构建专用 URL/派生密钥限定到 `build-status-应用编码` 环境，将生产回调 URL/密钥限定到 `production-应用编码`，保持 Protected、Masked、raw。必须先保护发布分支；接入不会替用户调整分支权限。独立生成模板时按实际 environment 名称配置变量。不能把生产密钥复制到构建状态作用域。

build_report 成功后，在 DevPilot 确认面板核对该构建并创建确认。打开**原流水线**的 production 手动作业，在作业变量中填写 `DEVPILOT_MANUAL_APPROVAL_ID` 为确认 UUID，再运行。这个 ID 不是密钥，不要在手动作业变量中粘贴回调 Secret。GitLab 支持运行手动作业时填写变量，且这些变量可能对有重试权限的成员可见，参见 [GitLab 手动作业说明](https://docs.gitlab.com/ci/jobs/job_control/)。

production 只读取原 digest，不重新构建。人工身份/时间依据 DevPilot 确认记录；`GITLAB_USER_LOGIN`、`CI_JOB_STARTED_AT` 仅作为 CI 发起信息。缺少确认 ID、制品过期、目标或构建不匹配都不能靠关闭门禁处理。镜像 digest 使用 [Buildx metadata-file](https://docs.docker.com/reference/cli/docker/buildx/build/) 的 `containerimage.digest` 字段。

限制：需要支持 Docker-in-Docker 和 Buildx 的 Runner；当前 GitLab 镜像构建未配置双架构。此兼容模板已做本地校验，但真实 Runner、手动作业变量、环境作用域及远端制品下载尚未验收；构建失败/取消的自动终态上报也未补齐，不能将 GitLab 标记为稳定版已验收链路。

## 上游接口参考

- [GitHub Actions Secrets API](https://docs.github.com/en/rest/actions/secrets)
- [GitLab Repository Files API](https://docs.gitlab.com/api/repository_files/)
- [GitLab Project Variables API](https://docs.gitlab.com/api/project_level_variables/)
- [Dokploy Application API](https://docs.dokploy.com/docs/api/reference-application)
- [Coolify Docker Image Application API](https://next.coolify.io/docs/api/endpoints/applications/create-dockerimage-application)
- [Coolify Registry Credentials](https://coolify.io/docs/knowledge-base/docker/registry)
