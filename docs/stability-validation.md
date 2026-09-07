# 个人自用稳定版验收记录

> 2026-09-06 最新发布门禁：尚未完成。源平台为 V36，独立恢复实验环境已升级到 V37；真实 GitHub → 私有 GHCR → 人工确认 → Dokploy v4 → Agent 健康验证的正向链路通过。完整异常恢复、全仓库安全门禁、用户独立完成接入及其他验收仍未完成。最新运行版本与每轮验证边界详见文末，局部验收不能替代完整上线验收。

稳定版尚未完成。本文记录实际执行的检查，不以脚本测试替代真实安装或数据库恢复。当前交付缺口集中在[稳定版交付检查](stable-release-readiness.md)，历史条目中的待办以之后具有明确证据的记录为准。

## 2026-09-07：安装前时间风险提示与通知验收前置检查

安装器新增限时只读 `timedatectl` 检查；未确认 `NTPSynchronized=yes` 且 `LocalRTC=no` 时，提示核对 UTC、NTP 和宿主/虚拟机 RTC，不调整系统时间，不将缺少 systemd 误判为不可安装。模拟 Docker 的完整 install/upgrade 生命周期测试退出 0，新增已同步、未同步、本地 RTC、查询失败、字段缺失五种提示分支；语法检查及 `git diff --check` 通过。在现有 Linux VM 用无效端口运行当前安装器，实际时间查询后返回预期的端口错误，无时间风险提示，且在创建维护锁和调用 Docker 前退出；这不是重新安装验收，也不能证明将来不会回拨。

新的故障发布已获用户确认，发布前发现旧临时公网回调不可达且无 cloudflared 进程，本地 callback-only gateway 仍返回 200。最初只查询仓库级 Actions Secrets，数量 0，被错误解读为 Secrets 已清空；后续检查实际 workflow 的两个 Environment，确认原四项 Secrets 均存在，已向用户纠正，不能将仓库级空列表当作环境级凭据缺失的证据。

用户授权恢复后，仅更新 build-status-onboarding-acceptance 与 production-onboarding-acceptance 的两项回调 URL，原签名密钥保留。新 callback-only 隧道健康 200、管理路径 404、无签名回调 401。创建专用通知订阅及隔离后端网络命名空间内的 HMAC 接收端，并基于原故障构建生成新人工审批。02:09:40 UTC 调度 release workflow 34075348166，复用构建 34038096535 的原 digest，不重新构建；原 v4 未改动。此处只记录调度成功，发布/回滚与通知结果仍需后续核验。私有证据目录 `/tmp/devpilot-release-notify.juGLla`。

随后完成真实发布失败与回滚成功通知验收：新审批 `1b1cdffd-d408-4358-a0a7-f8f363c75bcd` 由 `github-34075348166-1` 消耗，workflow 仅 production 执行成功，构建任务跳过。发布 `2096782969042968577` 使用既有故障 digest `237f4247…df545`，实际故障容器健康检查失败；Swarm 先自行 rollback_completed，DevPilot 没有将旧容器健康当作发布成功，保持 VERIFYING 至原定 02:14:50 UTC 期限。之后 DevPilot 创建关联回滚 `2096784261861679106`，最终原发布 ROLLED_BACK、回滚 HEALTHY；Agent 关联到本次发布之后启动的新健康容器，运行原 `onboarding-v1` digest `ce61a0df…6a039`。未调用人工恢复，原 v4 服务镜像及故障开关未变。

实际接收两条 HTTP 通知：DEPLOYMENT_FAILED 事件 `2adb1cb6-ac81-45bb-95aa-4acc023777fb`、ROLLBACK_HEALTHY 事件 `c1771038-944b-409f-a3e9-6edbfe759cda`，均 HMAC/Delivery ID 校验通过、HTTP 204、SUCCEEDED/attemptCount=1。核对应用 ID/名称、PRODUCTION 环境、对应失败/恢复 digest、原因与详情入口一致；重复观察后仍仅两条，不代表无限期或高负载重复抑制验收。`verify.mjs`、`verified.json`、`consumed-approval.json`、`timeline.json`、`provider-fault.json` 保留私有证据。专用订阅已禁用，接收进程已停止，历史保留。回调隧道 session 68100 与两项更新后的 URL 暂保留用于后续验收；未创建或轮换 Key，后续清理不能误删原环境级签名密钥。

本项证明发布失败和回滚成功通知，不证明真实回滚失败或构建失败通知，也不替代回调丢失恢复、完整交付门禁和正式版本发布；稳定版整体仍未完成。

构建通知补验：增加质量失败、扫描失败、取消且已有镜像三组载荷断言，要求应用/环境/commit/阶段状态/原因/详情入口完整；尚无镜像时保持 null，不制造 digest，构建 summary 中的敏感标记不得进入通知。JDK 21 隔离容器离线执行 DeploymentNotificationPayloadTests（7）与 CicdIntegrationTests（42），共 49 tests、零失败/错误/跳过；其中集成测试覆盖重复失败回调只入队一条通知和不触发部署。这是模拟/集成证据，不是 GitHub 真实构建失败通知验收。日志 `/tmp/devpilot-build-notification-tests.log`，代码逻辑未修改，未更新运行镜像。

## 2026-09-07：构建回调完整丢失与原制品恢复

用户明确授权回调故障测试后，仅将专用应用 build-status Environment 的回调 URL 临时改为网关上返回 404 的路径，签名密钥、生产回调及原 v4 不变。重跑原 push `34038096535`，生成 attempt 2；quality/security/image 全部成功，build_started/build_finished 均因实际 HTTP 404 退出 22，整个 workflow 失败，production 跳过。结束后通过平台 API 确认 `build:github-34038096535-2` 不存在，未删除或直接修改数据库记录。

下载 attempt 2 的 `devpilot-release-onboarding-acceptance-2` 制品，核对仓库、commit、Run 与 attempt。其 digest 为 `sha256:c47d36b341efac9bad1ac0ccf6fc5f621afd661d5a0c670cf4481874d4a42eae`；重新构建与旧 attempt digest 不同，因此恢复严格使用本次原制品，不能沿用旧 digest。随后恢复正确构建回调 URL，调度 `operation=recover_build` 任务 `34076178258`，只有 recover_build 执行，其他任务全部跳过。平台新增唯一记录 `2096786687754838018`，SUCCEEDED/PASSED/PASSED、AWAITING_APPROVAL，commit/image 与 attempt 2 制品一致。

再次重跑恢复任务（attempt 2）成功，平台记录 ID 不变、无重复；全部发布记录与测试前逐项一致，运行容器 ID 和 onboarding-v1 健康镜像不变。没有生成新发布审批或触发部署。故障 URL 已移除，正确临时回调地址恢复；隧道继续保留用于后续验收，不是永久生产入口。原 v4 故障开关仍关闭。

私有证据 `/tmp/devpilot-callback-loss.y2IsJ3` 包含 before.json、attempt2.json、callback-errors.log、missing-runs.json、artifact/release.json、recovery-run.json、recovered.json、verify.mjs 和 verified.json。本项覆盖完全缺记录的恢复，不覆盖只丢结束回调后被 GitHub observer 判失败再补正的分支；未用回调恢复成功替代真实构建失败通知验收，稳定版整体未完成。

## 2026-09-07：当前候选统一回归复查

### 真实构建失败通知及恢复提交

用户授权后仅在专用仓库 server.test.mjs 添加显式失败断言，故障提交 `216ea6e9e8881f694fbcceec02333f74d98cd4fc`，GitHub Run `34077138104`：quality 失败、security 成功、image/production 跳过，开始/终态回调成功。平台记录 FAILED/BUILD_FAILED、无镜像；专用 BUILD_FAILED 通知包含对应 commit、应用/PRODUCTION 环境、阶段结果、原因、详情入口，HMAC 与 Delivery ID 验证通过，新订阅 HTTP 204/attemptCount=1。

首次验证总请求数断言失败，追查发现恢复实验环境遗留 `Stability notification fixture` 仍启用且指向同一 loopback 接收端：它对同一个事件使用旧订阅密钥，累计 5 次 HTTP 401 后终止。这不是新订阅的重复成功投递，也不能说总共只有一次请求；旧订阅已按确认的测试用途禁用，失败历史保留。新订阅成功事件 `1a89b589-f1d4-430c-aa1c-7a3baa433bda` 与两条订阅投递分别留证。

恢复提交 `b61b1ca1a2adf6e99e265d327ae825f4388ac325` 的树与故障前 `b994507` 完全一致，本地原三项测试通过。恢复 Run `34077235040` attempt 1 的质量/扫描通过，但 GitHub Actions Cache 导出报 not_found，image 任务失败，平台不把已上传镜像当作可发布证据；此构建另外生成事件 `31c814ed-6e60-4c60-9f69-bcce875e1ee5`，成功投递一次。未修改门禁，重跑同一恢复 Run attempt 2 全部应执行任务成功，平台进入 AWAITING_APPROVAL，digest `13c21f7e…a395ee`。没有创建审批或部署，全部既有发布记录、onboarding-v1 镜像和运行容器保持不变，原 v4 故障开关不变。

私有证据 `/tmp/devpilot-build-failure.WNGznx` 包含原测试、前后状态、通知载荷摘要、两条订阅投递记录、验证脚本、缓存失败日志及恢复 Run 结果。专用订阅最终禁用、接收进程退出；没有删除历史。恢复的是新增故意失败断言，原仓库 HTTP 503 部署故障夹具依旧存在，不得将该 main 或待确认镜像部署为健康业务版本。本项不覆盖回滚失败通知，整体稳定版仍未完成。

在 JDK 21 容器限制 2 CPU/3 GiB、离线 Maven 缓存下执行全量 test，退出 0；30 suites / 130 tests，failures=0、errors=0、skipped=1（DokployLocalRetryLiveTests 未设置 opt-in 环境变量，未计为实测）。Web 39 tests 全通过，type-check、生产 build 通过；Agent `go test -count=1 ./...` 非缓存通过。`make maintenance-verify` 三组 Agent 安装/备份恢复/安装升级夹具通过，这些使用模拟 Docker，不替代真实 Linux 验收。

CI/CD 配置检查通过；工作流 actionlint 首次在 JAVA 场景遇到 Go 代理 EOF，离线 `go run` 又因版本元数据查询失败退出，均未当作通过。随后从本机已缓存的 actionlint v1.7.7 模块以 `go build -mod=readonly` 构建同版本二进制，使用受支持的 DEVPILOT_ACTIONLINT 入口重跑：平台 workflow、8 组模板语义检查通过，84 项 shell/HMAC/不重建门禁通过。仍未执行独立 shellcheck/pyflakes。

Trivy 0.65.0 使用源码只读挂载、vuln/secret/misconfig、include-dev-deps、HIGH/CRITICAL、exit-code 1，实际退出 0。该次 JSON 输出位于已自动移除的扫描容器内，未作为留存报告交付；仅保留日志及工具退出证据，不宣称有新制品/SBOM扫描报告。未降低严重级别、跳过 Secret/IaC 检查或添加忽略项。

日志：`/tmp/devpilot-final-candidate-backend.log`、`/tmp/devpilot-final-candidate-maintenance.log`、`/tmp/devpilot-final-candidate-workflow-cached.log`、`/tmp/devpilot-final-candidate-scan.log`；前两次未完成 workflow 校验日志也保留。`git diff --check` 通过。本轮不发布、不重启业务、不创建版本标签；这些结果针对当前工作树，最终版本提交及其制品仍需绑定后核验。

## 2026-09-07：真实回滚失败通知及人工恢复

用户授权先发布健康 v2，再测试回滚失败并恢复 v1，原 v4 不动。专用仓库提交 `6a0cc853fabbd0c8d0b6caefa27a61cd797a72ea` 移除 HTTP 503 夹具、将版本改为 onboarding-v2；本地测试及 push Run `34077859099` quality/security/image 全通过。制品首次下载遇到 EOF，未创建审批；重试下载成功后基于原制品创建新确认，release Run `34078010585` 仅 production 执行成功，发布 digest `24f5c8bb…f9e555`，未重新构建。

Dokploy 实际拉取 GHCR 时返回 EOF/login failed，发布 `2096795051943600130` 被识别失败并启动自动回滚 `2096795088853475330`；回滚也在 GHCR 拉取阶段发生同一错误，最终 UNHEALTHY，原发布 ROLLBACK_FAILED。旧 v1 容器仍健康，平台未以旧健康状态冒充回滚成功。真实 ROLLBACK_FAILED 事件 `fa473658-c8af-4d4f-af08-0520fa3b1d9d` HMAC/Delivery ID 通过、HTTP 204/attemptCount=1。

因真实网络故障已覆盖所需回滚失败事件，没有再注入无效凭据；配置 API 返回与测试前逐项一致。按用户恢复授权调用平台回滚入口，创建 `2096795530069090305`，指向原 v1 健康记录且关联失败回滚；恢复成功后 Agent 关联新容器 `609307713ded3dd54e2c95048b423fb885f0d74544e8cb0d2640ca3b8e6df9ac`，实际镜像仍为原 `ce61a0df…6a039`，启动时间晚于此次恢复，健康探测新鲜。ROLLBACK_HEALTHY 事件 `1555d92c-a27e-4e17-9751-0203cf7b7144` 同样成功一次，应用/PRODUCTION/目标 digest/原因/详情入口核验通过。

验收脚本和私有证据位于 `/tmp/devpilot-rollback-failure.LpjNrN`。专用订阅禁用，接收进程停止，失败和恢复历史保留；原 v4 故障开关未改。最新源代码是健康 v2，但这次 v2 没有上线成功，实际运行 v1；无需为了宣称 v2 成功额外重发。该项证明真实网络导致的回滚失败与人工恢复，不证明 Token 失效注入或数据库回滚。原计划有明确调整且未改变凭据，整体稳定版仍待正式交付及持久化证据补齐。

<!-- Earlier chronological records follow; newer scoped evidence is above. -->

## 2026-09-05：备份与恢复的失败保护

执行命令（仓库根目录）：

```bash
docker run --rm --network none \
  -v "$PWD:/workspace:ro" -w /workspace \
  debian:12-slim bash scripts/test-maintenance.sh
git diff --check
```

结果：两项均退出 0。测试容器不挂载 Docker socket、不联网；Docker CLI 为测试替身，所有生成文件在容器临时目录，未修改现有业务数据库。

已验证：

- 备份生成数据库压缩包、环境配置和校验文件；默认私有文件权限，文件名带随机后缀避免同秒覆盖。
- 正常恢复提交 SQL，并等待服务健康。
- 校验文件缺失、校验不匹配、符号链接、重复归档条目、损坏或空 SQL 压缩包均在任何 Docker 调用前拒绝。
- SQL 导入失败后不重新启动后端和网关。
- 恢复后服务健康等待失败时再次停止后端和网关，并返回失败。

尚未由本轮证明：真实 MySQL 导入、加密凭据恢复、独立环境登录与 Agent 接入、主机重启持久化、安装与升级、其余稳定版功能。必须继续做真实集成验收。

恢复只接受可信备份；校验和并不是备份来源认证。`--yes` 表示管理员已确认替换目标数据库。失败可能已经破坏目标数据，停止服务是防止继续使用不完整数据，并非自动回滚。

## 2026-09-05：安装与升级脚本检查

执行 `docker run --rm --network none -v "$PWD:/workspace:ro" -w /workspace maven:3.9-eclipse-temurin-21 bash scripts/test-install-upgrade.sh`，退出 0。Docker、端口查询和 HTTP 探测为替身，不是真实安装验收。

已验证端口占用、既有 Compose 项目会阻止安装；重复安装不改变原密钥文件；生成的后端健康检查使用镜像内的 wget；升级备份失败不改镜像引用、不启动服务；正常升级按停服务、备份、启动顺序执行且归档保留旧配置；健康等待失败后停止后端与网关。

另外修复安装模板 Redis 未传递健康检查所需的 `REDIS_PASSWORD` 环境变量。真实 MySQL 安装、升级以及业务级恢复仍待验证。

## 2026-09-05：真实 Linux/MySQL 安装、容器重启和独立恢复

环境：Mac Docker Desktop 内新建两个 `docker:28-dind` Linux arm64 环境，名称 `devpilot-stability-lab` 和 `devpilot-stability-restore-lab`。均无宿主机 Docker socket 挂载、无宿主机端口发布。软件准备后断开外层 bridge 网络，测试 API 只在各自 Linux 环境内访问。内层 Docker 28.5.2，MySQL 8.4。不是云服务器或完整虚拟机主机重启验收。

从当前工作树构建 `devpilot/server:stability-test` 和 `devpilot/web:stability-test`，通过 `docker save`/`docker load` 导入两个独立 daemon。后端构建成功；前端构建成功并生成 amd64/arm64 Agent 二进制。双架构二进制构建不等于双架构镜像运行验收。

在源 Linux 环境实际执行：

```bash
bash /workspace/scripts/install.sh --install-dir /opt/devpilot \
  --port 18081 --public-url http://127.0.0.1:18081 \
  --server-image devpilot/server:stability-test \
  --web-image devpilot/web:stability-test --offline
bash /workspace/scripts/test-persistence-api.sh seed http://127.0.0.1:18081 /opt/stability-evidence
/opt/stability-evidence/devpilot-agent -config /opt/stability-evidence/agent.yaml
bash /workspace/scripts/backup.sh --install-dir /opt/devpilot --backup-dir /opt/stability-backups
docker compose --env-file /opt/devpilot/.env -f /opt/devpilot/docker-compose.yml restart
docker compose --env-file /opt/devpilot/.env -f /opt/devpilot/docker-compose.yml up -d --wait --wait-timeout 180
DEVPILOT_TEST_REQUIRE_AGENT=true bash /workspace/scripts/test-persistence-api.sh verify http://127.0.0.1:18081 /opt/stability-evidence
```

Agent 二进制由已安装平台的 `/downloads/devpilot-agent-linux-arm64` 获取。验证：5 个服务健康；Flyway 成功记录 25 条、最后版本 25；管理员初始化及重新登录成功；Agent 注册并上报；平台所有容器重启后登录、服务器身份和 Agent ONLINE 正常。

实际备份：`devpilot-20260905T112416Z-1Fk1jd.tar.gz`，归档和 SHA-256 sidecar 在源环境 `/opt/stability-backups`，包含测试密钥，不提交 Git。

独立恢复流程：只复制 `/opt/devpilot` 配置、备份与私有验收客户端状态到第二个 Linux 环境，不复制 MySQL/Redis 卷；先启动 MySQL、Redis、Web（全新卷），随后执行：

```bash
bash /workspace/scripts/restore.sh --install-dir /opt/devpilot \
  --archive /opt/stability-backups/devpilot-20260905T112416Z-1Fk1jd.tar.gz --yes
bash /workspace/scripts/test-persistence-api.sh verify http://127.0.0.1:18081 /opt/stability-evidence
/opt/stability-evidence/devpilot-agent -config /opt/stability-evidence/agent.yaml
DEVPILOT_TEST_REQUIRE_AGENT=true bash /workspace/scripts/test-persistence-api.sh verify http://127.0.0.1:18081 /opt/stability-evidence
```

结果全部成功：恢复后 5 个服务健康，原管理员可登录，服务器 ID 保持一致，原 Agent Token 可注册，上报至少 5 个真实容器，25 条迁移记录保留。

本轮尚未验证加密业务凭据、发布/回滚记录、告警配置的恢复，也未做真实版本升级、systemd 自动启动或主机内核重启。两套验收环境暂时保留用于后续检查，私有状态位于各自 `/opt/stability-evidence`（0700），未创建云端 Key、Secrets 或公开隧道。最终清理须仅针对这两个新建环境及其测试卷，不涉及既有 Dokploy 或用户业务。

## 2026-09-05：接入任务端口修正

新增管理员端口修正 API 与失败页面入口；仅允许尚未创建资源的 stage 0 任务。状态响应仅增加端口、健康路径，不回传凭据或环境变量。创建本地应用后立即记录页面 applicationId，降低后续请求失败导致重复创建的风险。

执行 `mvn -q -Dtest=CicdIntegrationTests test` 成功；集成测试验证有效租约拒绝修改、非法端口拒绝、更新后保留阶段并返回新参数、更新过程未创建远端应用，以及 stage 2 拒绝端口修改；完整重试流程仍仅创建一次应用。前端 `npm run type-check` 和 9 项 workflow 模板测试通过。尚未做该页面的浏览器交互验收；EXPIRED 任务重新授权仍待实现。

## 2026-09-05：过期任务重新授权

新增 V26 记录凭据最近更新时间。清理任务将临时 Token/Key、Registry 密码及环境变量值置空，保留加密接入计划。EXPIRED 任务不能 advance；重新授权必须提供仓库和平台凭据，以及所需 Registry 密码、尚未配置的环境变量和 Dokploy 人工确认。空授权请求不延长保留时间。完成任务仍清除整个临时计划。

集成测试实际解密清理后的测试计划，断言原仓库密钥、平台密钥和环境变量值均不存在；过期任务不能前进，缺失凭据不能恢复；完整重新授权恢复同一 stage 0 任务，再次清理不会立即过期；后续失败重试依然只创建一次远端应用。新增迁移已在 H2 MySQL 模式执行，尚待真实 MySQL 升级验证。

本轮全量 `mvn -q test` 退出 0，日志 `/tmp/devpilot-stability-backend-tests.log`；前端类型检查、9 项模板测试和生产构建均通过。页面新增恢复表单，但浏览器交互未验收。此前已被旧版清空完整计划的任务无法凭空恢复，兼容限制见自动接入文档。两套真实 MySQL 验收环境仍运行前一轮镜像，不能把本轮源码测试当作运行环境已升级。

## 2026-09-05：V25 → V26 真实升级与升级前备份恢复

源环境已更新，恢复环境保留旧版本。构建当前源码为 `devpilot/server:stability-v26` 与 `devpilot/web:stability-v26`，导入源 Linux daemon。临时 `registry:2` 容器 `stability-registry` 仅发布至该隔离 Linux 的 `127.0.0.1:15000`，未发布宿主机端口、未向 GHCR 推送。此仓库只用于本地验收，不作为生产 Registry 安全方案。

升级前通过 `test-persistence-api.sh seed-business` 调用正常 API 创建 `persistence-fixture` 应用，写入 `PUBLIC_URL`（加密保存、允许读取）和 `API_KEY`（加密保存、页面脱敏）。数据库只读查询确认两行均为 `v1:` 密文，存在一条环境变量更新审计。

源环境实际命令：

```bash
bash /workspace/scripts/upgrade.sh --install-dir /opt/devpilot \
  --backup-dir /opt/stability-upgrade-backups \
  --server-image 127.0.0.1:15000/devpilot/server@sha256:af5d2b7ac98da8a05c0ec84154efefb174f90d6afded0d4a381faa5dde6857de \
  --web-image 127.0.0.1:15000/devpilot/web@sha256:58ac0543355c6d8fa880e399043e3bec766ab709c5665cbe48d076462e688927 \
  --migrations-reviewed --yes
```

审阅的 V26 变更为新增可空凭据时间列并为持有请求的历史任务回填创建时间，没有删除列或修改业务数据格式。升级脚本退出 0：先拉取指定 digest，停止平台写入，生成 `devpilot-20260905T113820Z-b2qtTj.tar.gz` 及校验文件，再更新 Server/Web 并等待健康。回报备份状态时网关已停止，因此连接警告是预期现象，不代表回报成功。归档本身生成并校验成功。

升级后 Docker inspect 确认 Server/Web 的 Config.Image 与上述 digest 完全相同，均 healthy；MySQL 最新迁移 `26, success=1`。API 验证登录、服务器身份、Agent ONLINE、至少 5 个真实容器快照、环境变量 revision 1、公开变量解密及敏感变量 configured 且 value=null 均通过。

将升级前备份和验收客户端状态复制到独立恢复环境（不复制数据库卷），在其旧版镜像下执行 `restore.sh --yes`。恢复后 MySQL 最新版本 25、两条环境变量密文和一条更新审计仍存在；同一 API 验证全部通过。这验证的是“旧数据库备份 + 匹配旧镜像”的恢复，不是对升级后数据库直接切换旧镜像。

限制：敏感 API_KEY 验证了密文存在与脱敏状态，未向真实部署平台同步以验证该值端到端使用。发布/回滚历史、告警配置、主机内核重启和最终稳定版发布仍待验收。内部 Registry 随源验收环境保留，后续清理范围增加 `stability-registry`；原用户业务未变更。

## 2026-09-05：统一初始化入口第一轮

新增 V27 `platform_setup` 状态表、管理员 `/api/setup` 配置和只读 Dokploy 验证 API，以及 `/setup` 页面。首次管理员创建后进入向导；服务器安装命令读取持久化访问地址。配置采用 revision 防止覆盖并发修改，Key 加密保存，变更地址必须重新授权。

新增集成测试覆盖未登录拒绝、保存后状态为尚未验证且不调用部署平台、Key 密文及响应不含 Key、独立验证调用、换地址不带新 Key 拒绝、旧 revision 拒绝。前端类型检查及生产构建通过。UI 显式区分人工确认访问地址与基于心跳的连接验证。

尚需完成：复用平台凭据到项目接入、向导内连续创建服务器体验、浏览器验收和 V27 真实 MySQL 升级。当前真实环境仍为源 V26 / 恢复 V25，不能视为已经运行新向导。

## 2026-09-05：初始化授权复用与服务器创建入口

接入页面增加显式复用 Dokploy 授权选项；服务端按 revision 读取加密 Key 和固定地址，忽略请求中另附的地址/Key，防止将保存的授权发送至调用者替换的地址。接入任务保存凭据快照，后续初始化配置修改不改变已创建任务。初始化页面增加创建服务器、保存选择及显示一次性安装命令。

`CicdIntegrationTests` 扩展并通过：复用请求实际使用保存的地址和 Key；附带不同地址不被使用；旧 revision 拒绝；创建任务的加密计划包含正确连接且 API 不返回 Key。前端类型检查通过。服务器创建请求丢失响应后仍需要刷新核对，跨刷新幂等创建尚待完善；浏览器和真实 V27 MySQL 验收尚未完成。

## 2026-09-05：构建状态上报与发布权限分离

新增 `/builds` 签名回调，使用由生产密钥派生的独立 HMAC 状态密钥。构建记录使用 `build:` 命名空间，不能通过状态接口创建部署任务，终态不被迟到 RUNNING 覆盖。GitHub 模板增加 push 开始/结束上报，读取独立 `build-status-*` 环境 Secrets，不读取生产密钥。自动接入配置新环境的两项 Secrets；手动配置可在轮换时获得一次性状态密钥。生产回调验证时间不由构建状态回调更新。

本轮全量后端测试退出 0（64 项，日志 `/tmp/devpilot-build-status-all-tests.log`），前端类型检查、9 项模板测试及生产构建通过；CI/CD 配置检查通过。新增测试覆盖运行→待确认、重放和迟到状态、状态密钥调用发布接口被拒绝、启用自动部署仍不创建部署记录。模板测试确认状态 job 不检出代码、不引用生产密钥。

尚未证明：真实 GitHub 环境创建及状态上报、完整取消事件、构建与人工发布记录的结构化关联、确认人/时间、通知和最终浏览器展示。当前线上/本地旧 workflow 不会自动变化，必须通过 PR 更新后再做端到端验证。

## 2026-09-05：发布来源与 CI 发起人关联

新增 V28 为流水线记录保存构建外部 ID、CI 上报发起人和时间。生成的 GitHub 生产任务从已查询的 build-run/release-run 元数据填入签名回调；后端要求来源构建属于同一应用且成功，commit 和 image URI 完全一致。已完成关联发布的来源、镜像与发起人禁止重写。构建成功状态仅接受 digest，不再允许 tag。

专项集成测试通过：状态密钥不能发布、成功构建不部署、来源镜像不匹配拒绝、匹配来源发布两次回调仅创建一次部署、之后修改发起人返回冲突。前端 9 项模板测试、类型检查与生产构建通过，CI/CD 配置检查通过。

信息边界：`approvalActor/approvedAt` 当前取自 GitHub triggering_actor 和 run_started_at，页面明确标为“CI 上报发布发起人 / 任务开始”。这不是独立核验的 Environment reviewer 点击记录，不能据此宣称准确审批人/点击时间已经验收。真实 GitHub 关联回调、审批事件采集、V28 MySQL 升级与浏览器验收仍待完成。

## 2026-09-05：构建与回滚通知

现有签名 Webhook 增加 BUILD_FAILED、ROLLBACK_HEALTHY、ROLLBACK_FAILED，复用持久化投递和重试。构建失败在受应用行锁保护的终态变更中生成，终态重放直接返回，不重复通知。为兼容原订阅，回滚仍产生原部署事件；回滚专属事件为可选订阅，文档明确同时选择会收到两种事件。默认新订阅增加构建失败。

通知包含应用、环境、状态、构建 commit 或部署镜像，以及发布中心相对路径；不转发可能包含凭据的原始摘要和日志。修复发布中心初始化忽略 application 查询参数的问题。

执行 `mvn -q -Dtest=CicdIntegrationTests,AutomationWebhookIntegrationTests test` 退出 0，21 项通过（日志 `/tmp/devpilot-notification-tests.log`）。新增断言验证失败回调三次仅一条 BUILD_FAILED 投递；本地真实 HTTP 接收器收到回滚成功事件并校验 HMAC，回滚失败队列记录存在。前端类型检查通过。尚未完成真实 GitHub 故障通知、回滚失败实际 HTTP 投递、通知页面浏览器验收与完整取消上报，不视为通知目标全部完成。

## 2026-09-05：长期 Running 的观察过期提示

流水线响应新增 observationStatus / observationMessage。RUNNING 最后更新时间超过默认 2 小时（可配置）时展示 STALE，不改写数据库中的 CI 状态，不触发失败通知、部署或取消。页面保留任务入口和最近更新时间，并提示核对 CI 与回调。终态回调到达后恢复正常展示。

专项 20 项测试通过，新增断言覆盖三小时前的 RUNNING 显示 STALE、原始 status 仍为 RUNNING、查询不创建部署或通知、迟到成功终态解除提示、后续 RUNNING 不能覆盖成功。随后全量后端 `mvn -q test` 退出 0（日志 `/tmp/devpilot-stability-current-all-tests.log`）；前端类型检查、9 项模板测试、生产构建和 CI/CD 配置检查通过。此功能并非轮询 GitHub 的取消检测，浏览器验收仍待完成。

## 2026-09-05：V26 → V28 MySQL 升级与新配置重启验证

重新核对源隔离 Linux `devpilot-stability-lab`：升级前 MySQL 最新迁移为 26，登录、应用环境变量 revision 1 与解密、Agent ONLINE 和容器快照均通过。审阅 V27（新增平台配置表与初始行）、V28（流水线表新增三个可空关联字段）；未删除旧列或转换旧数据。

当前测试镜像通过宿主构建并导入隔离 daemon，仅推送其内部 loopback Registry，没有发布 GHCR 或开放公网。升级命令：

```bash
bash /workspace/scripts/upgrade.sh --install-dir /opt/devpilot \
  --backup-dir /opt/stability-v28-upgrade-backups \
  --server-image 127.0.0.1:15000/devpilot/server@sha256:ccc7af7c2fe9fd156e52217f4b6fbae42c5a68e07955b20f607cfd9750a061e6 \
  --web-image 127.0.0.1:15000/devpilot/web@sha256:0256e2e7db45a87ff24dd6de560f8fa413795b09568484f767489fad8e9bee93 \
  --migrations-reviewed --yes
```

脚本先生成升级前备份 `/opt/stability-v28-upgrade-backups/devpilot-20260905T122406Z-X6Deu2.tar.gz` 及 SHA 文件，再更新镜像并等待全部应用服务健康，退出 0。停网关时备份状态回报失败警告如实保留，归档生成成功。升级后只读 MySQL 查询显示 V28、V27、V26 均 success=1；Docker Config.Image 与指定 digest 完全一致。

扩展 `test-persistence-api.sh seed-setup`：通过正式初始化 API 保存访问地址、原服务器选择和随机合成平台 Key；Key 对应 `http://127.0.0.1:9`，不调用连接验证、不接触真实 Dokploy，也不代表平台连接已通过。数据库只读检查确认 provider_token_cipher 使用 `v1:` 密文。GET 响应不含 Key，状态保持 SAVED_UNVERIFIED。

随后实际重启 Compose 五个服务（MySQL、Redis、Server、Web、网关），等待全部 healthy；验证登录、服务器身份、Agent ONLINE、至少五个容器快照、应用变量解密与敏感变量脱敏、初始化 revision 和服务器选择均通过。仅证明容器重启，不是主机内核重启。新备份 `/opt/stability-v28-backups/devpilot-20260905T122602Z-IBBFFG.tar.gz` 及校验文件已生成，这次在线备份状态回报未报错；独立恢复环境仍为旧 V25，尚未恢复此新备份。

当前运行源码包含初始化向导、构建/回滚通知、构建发布关联与 Stale 提示，但本轮未做其浏览器操作或真实 GitHub 验收。合成 Key 只验证密文保存和脱敏，不证明真实平台授权可用。发布/回滚历史完整恢复、主机重启及其他目标仍未完成。

## 2026-09-05：V28 独立恢复与凭据实际解密使用

恢复目标仅为 `devpilot-stability-restore-lab`，不是源或原 Dokploy 业务环境。只读检查确认两个外层 daemon 的持久卷分别为 `98ff3be28aaa440393e4aab671c445a67d14ca94c415dcc083482efa0d224269` 与 `3fafd6f3cebfbef6cf15f3b489884d6ce68ff651252abcadaf8c1dd05442e9b4`；两边内层 MySQL 虽同名，但并未共享数据目录。

传入 V28 镜像、备份和私有验收客户端资料，不复制 MySQL 卷。恢复环境新增内部 loopback Registry `stability-restore-registry`（只在隔离 Linux 内监听 127.0.0.1:15000），以相同 digest 准备匹配镜像。通过 upgrade.sh 先备份旧测试环境并切换到 V28；旧环境备份为 `/opt/restore-lab-pre-v28-backups/devpilot-20260905T122759Z-6P0Ovy.tar.gz`。然后执行：

```bash
bash /workspace/scripts/restore.sh \
  --archive /opt/stability-v28-backups/devpilot-20260905T122602Z-IBBFFG.tar.gz \
  --install-dir /opt/devpilot --yes
```

恢复脚本校验归档和主密钥，停止目标 Server/网关，重建并导入目标测试数据库，等待服务健康，退出 0。原目标测试数据库被覆盖，可从上述旧环境备份恢复；源数据库未变更。恢复后 MySQL V28 success=1、初始化 Key 密文存在、审计记录存在。API 验证管理员登录、原服务器身份、Agent ONLINE、容器快照、应用变量解密及敏感值脱敏、初始化 revision 与服务器选择均通过。复制的私有测试文件保持目录 0700、文件 0600。

进一步运行 `scripts/test-restored-provider.mjs`：一次性 Node 测试容器共享恢复后 Server 的网络命名空间，仅监听其 127.0.0.1:9，只读挂载原合成 Key。正式 `/api/setup/verify-provider` 从数据库解密 Key，调用 `/api/project.all` 与 `/api/server.all`；接收器恒定时间比较两个请求中的 Key，均与备份前客户端保存的原值一致，接口返回 VERIFIED。接收器完成后退出，`--rm` 自动移除测试容器。再次请求连接验证得到 FAILED 与明确错误，验证接口失联不会继续伪装为验证通过。Key 未输出至日志。

此处是真实数据库恢复、正式后端解密和真实 HTTP 传输，但对端是明确标识的合成只读接口，不是真实 Dokploy。当前恢复环境的合成连接因此显示 FAILED，初始化配置和凭据本身仍保留且可验证。源环境不受影响。恢复环境内部 Registry 纳入后续清理清单。仍缺少包含完整真实发布/回滚历史的恢复验收、主机重启及真实外部 CI/CD 新流程验收。

## 2026-09-05：新增接口的定向安全检查与加固

按 llm-sast-scanner 的 Source→Sink 和复核流程检查初始化配置、重新授权、构建回调及对应 UI；参考权限、凭据披露、SQL/XSS/SSRF、密码学与并发规则。这不是全仓 34 类扫描，也不替代依赖扫描或真实攻击面验收。

已复核的保护：初始化与接入控制器均受管理员方法权限保护，SecurityConfig 启用方法安全；平台配置 SQL 的用户值通过参数绑定；保存 Key 使用 AES/GCM 随机 IV。Hash check: SHA-256 → strong → SAFE（主密钥派生，不是密码存储）。构建状态密钥采用带域标签的 HMAC-SHA256，状态接口不能创建生产部署，已有事务应用行锁与终态幂等测试。管理员指定部署平台地址是产品需要的操作范围，不能仅因支持内网地址就当作越权 SSRF；HTTP 客户端不跟随重定向，错误不转发远端正文。

加固路径：合法构建状态密钥持有者能控制 runUrl，旧代码仅检查长度，随后页面直接绑定 href。确认输入可达与缺少协议限制，但未证明特定浏览器在 target=_blank 下的脚本执行影响，因此不宣称已确认可利用的存储型 XSS。仍按 URL 输入边界要求修复：后端只接受无 userinfo 的 HTTP(S)，前端 safeHttpLink 同时保护已有数据库记录中的非法 URL。没有删除或改写历史记录。

初始化保存与验证新增 UPDATE_PLATFORM_SETUP / VERIFY_PLATFORM_PROVIDER 审计动作，复用原请求脱敏机制。新增实际账号登录测试验证 DEVELOPER、VIEWER 均不能 GET/PUT setup、POST verify-provider 或更换接入凭据，拒绝后不调用部署平台、不改 revision；管理员审计请求保留操作信息而不含 Key。构建回调测试验证 javascript/data/file/带 userinfo URL 被拒绝且不创建流水线行；前端测试覆盖历史危险链接、换行协议及正常 GitHub/内网 HTTP 链接。

`mvn -q -Dtest=CicdIntegrationTests test` 21 项通过（`/tmp/devpilot-security-focused-tests.log`）；`npm test` 10 项、前端类型检查及 diff 检查通过。本轮改动尚未重建至两个 V28 实验环境；其运行镜像仍是上一轮构建，不能把源码专项测试当作部署完成。全仓安全、依赖扫描及真实浏览器验收仍待完成。

## 2026-09-05：当前依赖门禁与回归

在 devpilot-web 正确锁文件目录执行 `npm audit --audit-level=high`，退出 0，报告 0 vulnerabilities（最初误在仓库根目录执行返回 ENOLOCK，没有创建根锁文件或安装依赖）。Agent `go test -race ./...` 全部通过。`make maintenance-verify` 两套 Linux 脚本测试退出 0，包括预期失败分支；其中 Docker 为模拟实现，不能替代前述真实恢复证据。

按照仓库 CI 当前配置运行 `aquasec/trivy:0.65.0 fs --scanners vuln,secret,misconfig --severity HIGH,CRITICAL --exit-code 1 --ignore-unfixed --format json .`。镜像拉取 digest 为 `sha256:a22415a38938a56c379387a8163fcb0ce38b10ace73e593475d3658d578b2436`，本次下载漏洞库和检查规则，扫描退出 0。JSON 的结果目标包含 Go 模块、Maven POM、npm lockfile 和三个 Dockerfile，在这套过滤条件下无报告项。原始 JSON `/tmp/devpilot-stability-trivy.json` 以 0600 权限保存，日志 `/tmp/devpilot-stability-trivy.log`；未添加漏洞忽略项或关闭扫描器。扫描器提示有新版本，但本轮保持与现有 CI 相同版本，未声称当前版本是最新版。

边界：该结果不覆盖被过滤的中低危、未修复漏洞、全部运行镜像操作系统包或未知漏洞；不是“全仓安全无漏洞”的证明。随后后端全量回归退出 0（`/tmp/devpilot-current-regression.log`），65 项、0 failure、0 error。真实外部验收尚待重新准备临时授权与回调通道；只读 GitHub 查询确认 `miaomeng1/devpilot-e2e-demo` 仍为 private、默认分支 main，未修改仓库权限、Secrets 或外部资源。

## 2026-09-05：初始化与接入识别的真实浏览器验收

新增可复跑的 `scripts/test-stability-ui.cjs`，使用 Playwright Chromium。测试要求 `127.0.0.1:19090` 是全新 OnboardingUiSmokeApplication（测试 classpath、测试 H2 配置、模拟仓库和部署平台），前端为当前源码 Vite `127.0.0.1:19091`。浏览器 context 将 API 请求转到该后端，拒绝其他 origin 请求；不会访问真实 GitHub/Dokploy。不是正式部署文档中的用户使用步骤。

本轮 Maven 生成测试 classpath 后，以 JDK 25 显式路径启动专用测试入口，设置 `--spring.config.location=file:src/test/resources/application.yml --server.address=127.0.0.1 --server.port=19090`。系统 `java` 桩首次返回缺少运行时，随后使用 Maven 所用的 Homebrew JDK 正常启动；没有替换系统 Java。Playwright 通过已提供的本地运行时包加载，没有向项目添加运行依赖。

浏览器通过 UI 创建随机密码管理员并自动到 /setup，保存地址与合成 Key，验证保存/验证状态分离、保存后 Key 输入框清空；创建服务器、检查安装命令使用保存地址、隐藏命令并刷新，确认服务器选择保留且一次性命令不再显示。390px 手机宽度无横向溢出，无 pageerror。随后接入页显式复用保存授权，平台 Key 输入不显示，模拟识别结果产生 8080 容器端口，未勾选确认时执行按钮保持 disabled。没有执行部署资源创建或 PR 提交。

首轮截图发现刷新按钮在手机下挤压换行、验证时间紧贴按钮，修改布局后再次从新 H2 实例完整复测通过。已查看桌面/手机及识别页截图，结果目录 `/var/folders/0k/bg28r22s5m53w39tn9b5krz80000gn/T/devpilot-stability-ui-j5ByZx`，包含 setup-desktop.png、setup-mobile.png、onboarding-inspected.png。另外为接入授权勾选行复用 consent 样式，修正复选框与标签分离；此最后一处样式尚未重新截图。截图前隐藏真实生成的 Agent 安装命令，仓库 Token 为无权限的合成值且输入框遮蔽。

前端类型检查、测试脚本语法和 diff 检查通过。本轮浏览器 finally 关闭；确认并停止仅本轮启动的后端 PID 91286、前端 PID 90782（前一测试后端 90298 已在复测前停止），旧用户实例不变。临时 H2 数据随进程结束消失。真实发布中心状态、失败重试/过期重新授权全流程和生产镜像浏览器验收仍待完成。临时外部授权申请尚未收到明确答复，本轮未创建 Key、隧道或修改 GitHub Secrets。

## 2026-09-05：创建服务器响应丢失后的幂等恢复

新增 V29 server_creation_request。API 可选 UUID requestId 按管理员隔离，锁定已有 sys_user 行后查询/创建请求，避免不存在记录时的并发窗口。相同请求和名称在 24 小时内返回同一服务器、原 Token 和安装命令；结果加密保存。名称变更、服务器删除、Token 撤销或恢复窗口过期拒绝重放。定期清除过期密文，保留元数据用于防止旧请求重新创建。

初始化页面创建前将请求 ID 与名称写入按管理员隔离的 localStorage（没有凭据）；完成创建与保存选择后再清除。请求失败或刷新时恢复同一个请求，重试期间名称只读。不带 requestId 的旧调用保持兼容，独立服务器页面暂未接入，过期后的重新签发交互也未完成。

22 项 CicdIntegrationTests 通过（`/tmp/devpilot-server-create-retry-tests.log`）：两个并发请求得到同一服务器与凭据、数据库只有一条对应服务器、响应密文不含明文 Token、变更名称拒绝、过期密文清除且重放不重复创建。前端类型检查通过。

扩展浏览器测试，在真正 POST 创建成功后主动丢弃响应，页面出现错误；刷新后恢复名称与请求 ID，再点击得到原服务器和命令。断言两次请求 ID 一致、刷新列表中对应服务器仅一个选项；后续隐藏、刷新、接入识别和确认门禁测试继续通过。截图目录 `/var/folders/0k/bg28r22s5m53w39tn9b5krz80000gn/T/devpilot-stability-ui-RsoDO6`。结束后关闭浏览器并停止本轮后端 95271、前端 95323，原实例未改动。

V29 本轮仅在 H2 测试实例执行，真实 MySQL 实验环境仍为 V28，尚未部署本轮改动。跨管理员重复键、Token 撤销、记录删除等更多集成断言、全量回归及完整过期恢复仍待完善，不将本小节视作整个接入恢复目标完成。

## 2026-09-05：两个服务器创建入口共用恢复请求

新增 serverCreationRequest 前端工具，初始化与独立服务器页面共用管理员对应的请求标识。读取异常或存储不可用时拒绝自动替换请求；完成清理只删除匹配 requestId，不清除其他请求。独立页面收到凭据后关闭窗口才结束重试，进行中不能关闭并重新点击；名称在恢复期间只读。更新“仅存摘要”的旧提示，说明创建响应加密暂存 24 小时。

前端新增两项单元测试覆盖跨调用恢复、管理员隔离、只保存名称/ID、旧 ID 不误清除、损坏/不可用存储不自动生成替代请求。`npm test` 共 12 项通过，类型检查与生产构建通过（`/tmp/devpilot-v29-web-build.log`）。后端全量回归 66 项通过、0 failures/errors（`/tmp/devpilot-v29-regression.log`）。

浏览器脚本扩展为两个不同名称分别丢弃首次创建响应，两个页面都在刷新后发送相同 ID，最终每个名称只有一条服务器记录；随后关闭凭据窗口并刷新，列表仍只有一条。测试完成退出 0，目录 `/var/folders/0k/bg28r22s5m53w39tn9b5krz80000gn/T/devpilot-stability-ui-gYRFmB` 保存向导截图，未截取独立页面明文凭据窗口。浏览器关闭，专用后端 PID 97817 与前端 PID 97874 已停止，不改动旧实例。

本轮没有创建外部 Key/隧道或修改 GitHub Secrets。真实 MySQL 仍运行 V28；V29 迁移、跨管理员服务端隔离更多断言和过期后的明确重新签发流程仍待验收/实现。

## 2026-09-05：创建请求的撤销、删除与管理员隔离

服务器删除事务现在同时撤销 Agent Token、清空该服务器创建结果的加密密文并软删除服务器；保留请求去重记录，旧请求不会重新创建资源。此变更只涉及平台记录，不执行目标服务器上的容器删除或 Agent 卸载。

新增 MockMvc 集成测试覆盖撤销后同请求返回 409 且无明文凭据、删除后缓存立即清空且重试不新增服务器/Token、创建成功响应 Cache-Control: no-store。以两个真实登录管理员使用同一 requestId，验证响应隔离且各自重试复用自己的结果；这不是跨管理员全局去重，不同管理员仍可分别创建同名服务器。已有过期测试增加定时清理前的请求检查，证明到期即拒绝，而非依赖清理任务及时运行。

专项 `mvn -q -Dtest=CicdIntegrationTests test` 24 项全部通过，日志 `/tmp/devpilot-creation-boundaries.log`。后端全量 `mvn -q test` 退出 0，日志 `/tmp/devpilot-creation-boundaries-regression.log`；首次误从仓库根目录执行因无 POM 退出 1，随后在 devpilot-server 正确执行。以上是测试数据库证据，未更新现有实验环境运行镜像，未操作用户服务器或外部凭据。过期后的明确重新签发 UI/API 和真实 MySQL 迁移验收仍未完成。

## 2026-09-05：原服务器 Agent Token 重新签发后端

新增 V30 agent_token_renewal_request，提供管理员 GET/POST `/api/servers/{id}/registration`。GET 仅返回无密钥版本号；POST 必须明确确认，携带版本号与 UUID。事务锁定管理员和目标服务器，旧版本的新请求拒绝；同一次成功请求在 24 小时内重放同一签发结果。签发撤销旧 Token、清空旧创建/签发密文，生成 PENDING Token 并加密保存响应，不新增服务器，不自动操作目标机。删除也使用服务器行锁并清除签发密文。保留去重元数据，定期清除过期响应，新增 RENEW_AGENT_TOKEN 审计动作。

MockMvc 专项 25 项通过（`/tmp/devpilot-renewal-tests.log`）。新增测试验证明确确认、同请求并发重放、新 Token 不同于旧 Token、只有一条未撤销 Token、密文保存、陈旧版本拒绝、到期立即拒绝和清理、审计存在。随后增加 DEVELOPER/VIEWER 对 GET/POST 的拒绝测试，后端全量回归退出 0（`/tmp/devpilot-renewal-regression.log`）。

边界：目前是后端接口完成，前端确认/响应丢失恢复/过期处理尚未接入；尚未实测新 Token 注册与旧 Token 鉴权失败的完整链路、不同管理员同时轮换、删除竞争及真实 MySQL V30 迁移。没有在现有用户服务器上执行轮换。安装脚本会重写 Agent 配置，文档明确已有 Agent 应保留配置仅人工替换 Token 并重启，不将安装命令宣传为无损轮换。

## 2026-09-05：签发页面与注册链路验证

新增 AgentTokenRenewal 组件，服务器列表仅管理员可见入口。明确确认后发送签发请求，成功结果默认隐藏；已有 Agent 提示只更新 Token 并保留其他配置，安装命令放在带覆盖警告的折叠区域。请求标识按管理员及服务器隔离，在提交前写入浏览器；不保存凭据。网络错误重试和刷新恢复复用原 requestId/expectedRevision。40977 冲突后需要明确选择放弃旧结果恢复，重新读取版本并再次确认，不能因超时自动轮换。

新增恢复标识单元测试覆盖复用、不同账号/服务器隔离、完成时只清除匹配请求、损坏/不可用存储禁止新请求。前端 14 项测试、类型检查和生产构建通过，构建日志 `/tmp/devpilot-renewal-web-build.log`。后端既有签发测试扩展实际 `/api/agent/register` 和 `/api/agent/heartbeat` 请求：新 Token 返回原 serverId 并上报成功，旧 Token 两个入口都返回 401；25 项专项通过，日志 `/tmp/devpilot-renewal-registration.log`。

浏览器测试脚本对签发首次 200 响应故意丢弃；刷新、重开同一服务器窗口、再次确认后，服务端返回相同 Token，两个请求 ID 相同。验证未勾选不能提交、结果默认隐藏、localStorage 不含 Token、关闭后仅清除签发标识、390px 手机宽度无横向溢出，无 pageerror。测试使用隔离 H2 和模拟外部服务，不操作现有服务器。截图目录 `/var/folders/0k/bg28r22s5m53w39tn9b5krz80000gn/T/devpilot-stability-ui-FiuOqv`，已查看 renewal-masked-mobile.png；凭据未显示且安装命令折叠。浏览器关闭，专用后端 PID 22739 与 Vite PID 22792 已发送 SIGTERM 停止，旧用户实例未动。

边界：UI 的 40977 再确认分支尚未浏览器验收；初次创建标识过期后的清理交互仍需补齐（可先在列表原服务器上重新签发，不应重复创建）。真实 MySQL V30 迁移、跨管理员同时轮换及删除竞争仍待验证。

## 2026-09-05：真实 MySQL V28 → V30 升级与凭据恢复

只读复核 devpilot-stability-lab 仍为隔离 Docker-in-Docker、无宿主发布端口、仓库只读挂载、独立 daemon 数据卷；内部 stability-registry 仅监听实验容器 127.0.0.1:15000。未改动 devpilot-dokploy-lab 或其他用户容器。

当前源码构建镜像后通过 docker save/load 导入实验 daemon，再推送其内部 Registry。升级目标为 server `sha256:7d13c2d4a032bc11bf6a06f098c181cb3823cf6f709d88b245925fd9dd0f4bc2`、web `sha256:7523667b459a6086e1b9bf45f90646c281e86145fa63695f90eea3b7f8821ea4`（仓库分别 127.0.0.1:15000/devpilot/server、web）。构建日志 `/tmp/devpilot-server-v30-build.log`、`/tmp/devpilot-web-v30-build.log`。没有推送 GHCR，没有公开任何镜像。

在升级前通过持久化验证脚本。审阅 V29/V30 为新增缓存表与索引后，执行 upgrade.sh，指定上述 digest、--migrations-reviewed、--yes。脚本先停后端/网关并完成备份 `/opt/stability-v30-upgrade-backups/devpilot-20260905T134059Z-6z4sZy.tar.gz` 及 SHA256 校验文件，再升级并等待健康。停网关期间备份状态回报无法到达，脚本明确警告，备份自身成功。日志 `/tmp/devpilot-v30-upgrade.log`，退出 0。真实 MySQL 日志证实依次应用 V29/V30，2 项迁移成功，版本 v30。

新增可复跑 `scripts/test-agent-renewal-api.sh`，只接受 loopback 实验入口，读取私有实验管理员文件，在新的 0700 目录保存结果，不输出凭据。真实 HTTP 验证：同创建请求复用服务器/Token；两个并发签发请求返回同一新 Token；陈旧版本新请求 409；被轮换后的创建请求 409；旧 Token 注册 401、新 Token 注册到相同 serverId。新增专用服务器 ID `2096232261264056322`，私有结果目录 `/opt/stability-v30-renewal-evidence`。注册硬件信息为测试合成数据，不是第二个真实 Agent 进程。

随后重启实验平台全部五个 Compose 容器并等待健康，日志 `/tmp/devpilot-v30-restart.log`、`/tmp/devpilot-v30-postrestart-health.log`。脚本 verify 模式确认重启后可解密并重放原签发 Token，新 Token 仍可注册、旧 Token 仍被拒绝。原持久化脚本确认管理员登录、原服务器、应用变量解密/脱敏、setup revision/选择/合成 Key 保留；开启 DEVPILOT_TEST_REQUIRE_AGENT=true，原真实 Agent ONLINE 且至少五条实际容器快照。没有轮换原 stability-linux 的凭据。

边界：这是当前源码的 Linux 容器/MySQL 升级及容器重启证据，不是宿主机内核重启或 systemd 验收，也不是 V30 独立恢复证明。另一台 restore-lab 仍保留 V28，尚未恢复本轮签发记录。跨管理员并发、删除竞争、完整真实发布链路及未完成 UI 分支仍待验收。

## 2026-09-05：V30 在独立 MySQL 数据卷恢复

再次检查恢复目标 devpilot-stability-restore-lab 无宿主机发布端口，daemon 数据卷为 `3fafd6f3cebfbef6cf15f3b489884d6ce68ff651252abcadaf8c1dd05442e9b4`，与源实验卷 `98ff3be28aaa440393e4aab671c445a67d14ca94c415dcc083482efa0d224269` 不同。只操作实验平台，没有修改用户 Dokploy 环境。

源环境生成 V30 备份 `/opt/stability-v30-backups/devpilot-20260905T134421Z-rkVPJL.tar.gz` 和 SHA256 旁文件，日志 `/tmp/devpilot-v30-backup.log`。通过容器间 tar 流复制备份与私有签发测试客户端文件到目标，不在宿主写明文副本；目录权限随 0700 保留。客户端证据文件只是测试输入/预期值，不注入数据库。

目标先导入并推送相同 server/web digest 至其内部 loopback Registry，执行 upgrade.sh 升级至 V30；升级前保留目标原 V28 备份 `/opt/restore-lab-pre-v30-backups/devpilot-20260905T134516Z-RmmNt8.tar.gz`，日志 `/tmp/devpilot-restore-v30-upgrade.log`。随后 restore.sh --yes 校验源备份、核对已有主密钥、停止目标后端和网关、替换目标实验数据库并导入备份，最终全部服务健康，退出 0，日志 `/tmp/devpilot-v30-independent-restore.log`。覆盖仅涉及已指定恢复实验库，可用目标升级前备份和对应旧镜像恢复原实验状态；没有覆盖源数据库或业务数据卷。

增强签发 verify 模式：重放前先通过审计 API 检查已有该服务器 RENEW_AGENT_TOKEN 成功记录，且返回审计内容不包含新 Token。第一次使用 query=serverId 未命中，因为当前审计关键词不搜索资源 ID；修正为动作筛选后在返回记录中比对资源 ID，脚本独立重跑退出 0。随后验证从恢复数据库解密重放的 Token 与备份前完全相同、新 Token 注册成功、旧 Token 401。不是仅检查“密钥已配置”标志。

恢复后的持久化脚本另验证管理员登录、原服务器身份、应用环境变量解密及脱敏、setup revision/选中服务器/合成部署平台凭据保留；启用 REQUIRE_AGENT，原真实 Agent 在线且至少五个实际容器快照。新签发测试服务器注册仍为合成 HTTP 测试，未把它称作新真实 Agent 安装。独立恢复已经覆盖 V30 签发缓存、撤销状态和审计；完整真实发布/回滚历史恢复与宿主机重启等剩余范围不因此完成。

## 2026-09-05：创建恢复标识的明确结束流程

新增管理员只读 GET `/api/servers/creation-requests/{UUID}`，按当前用户查询创建元数据，不查询或返回密文内容，返回原 serverId/name 及 AVAILABLE/EXPIRED/UNAVAILABLE/DELETED。没有当前管理员记录则 404，不能借请求标识看到另一位管理员结果。可恢复仅表示缓存窗口/密文仍存在，不保证凭据未在其他路径撤销；真正重放仍执行原有效性检查。

初始化向导与服务器创建窗口共用 ServerCreationRecovery。只有查询到原记录并由用户勾选确认后，才可结束旧请求；操作只清除匹配的浏览器标识，不删除资源、轮换 Token 或发起创建。未知结果与网络错误不提供盲目清除。此流程解决正常保留标识的过期请求卡住后续添加，不处理用户自行篡改/损坏 localStorage 的人工修复。

25 项后端专项通过，日志 `/tmp/devpilot-creation-recovery-tests.log`：验证 AVAILABLE/EXPIRED/DELETED、原 serverId、无 Token/安装命令、跨管理员未知请求 404 与相同 UUID 各自结果隔离。前端 14 项、类型检查及生产构建通过（`/tmp/devpilot-creation-recovery-web-build.log`）。

扩展 Playwright 实际创建后丢弃第一次响应，点击只读核对、确认按钮初始禁用、勾选后结束请求。创建请求总数不增加，名称输入清空，关闭刷新列表仍只有一条原服务器。既有签发重试及向导测试仍通过，无 pageerror，输出目录 `/var/folders/0k/bg28r22s5m53w39tn9b5krz80000gn/T/devpilot-stability-ui-s0iaI5`。这是隔离 H2 真实 HTTP/UI 验证；本轮未重建至两个真实 MySQL 实验镜像，未宣称新增只读接口已在该环境部署。过期页面文案分支由后端状态测试支撑，尚未在浏览器内人为推进时间验收。

## 2026-09-05：发布任务来源核对与审批证据缺口

核对 GitHub 官方 REST workflow runs 文档的审批历史接口：返回 user/state/environments，但公开响应没有审批事件时间；环境创建/更新时间不能当作审批发生时间。当前模板上报 triggering_actor 和 run_started_at，只能支撑 CI 发起记录，不是实际人工审批证据。后端错误信息同步改为 CI 发起人/开始时间，避免接口将这些字段称作已经验证的审批人/审批时间；兼容字段名未更改。

GitHub 模板新增发布 run 自身的 workflow_dispatch event、id、run_attempt、head_branch、repository.full_name 核对，再进行已有构建来源和 digest 验证。新增测试从实际生成的模板提取 jq 条件，以 jq 执行正确 fixture 和错误事件/Run ID/attempt/分支/仓库五种输入，错误输入都拒绝。前端 15 项及类型检查通过；运行前端模板测试现在需要 PATH 中有 jq（与流水线运行依赖一致）。后端 25 项专项通过，日志 `/tmp/devpilot-release-provenance-tests.log`。

没有发送外部 workflow、修改 Secrets 或扩大权限。workflow_dispatch 可经 API 发起，新增校验不能证明真人点击；完整人工确认身份/时间证据仍未完成。下一步应实现可追踪到认证用户、具体构建 digest 的明确确认记录并接入发布校验，而不是用 CI 任务时间补齐审批字段。

## 2026-09-05：人工确认记录后端（尚未接入发布门禁）

新增 V31 cicd_release_approval、ManualReleaseApprovalService 及确认/列表接口。确认 POST 允许 ADMIN/DEVELOPER，VIEWER 禁止；身份由 DevPilotPrincipal 取得，时间由服务器 UTC 记录，不接受客户端指定确认人或时间。绑定本应用当前分支的成功构建、已通过的测试/扫描、精确 commit 和完整镜像 digest，同时保存环境、服务器及配置更新时间快照。创建确认不触发部署，不调用外部服务。

按确认用户/requestId 去重并使用用户及应用事务行锁。相同请求并发返回同一记录，commit/image/应用/构建不允许替换；记录有效窗口 24 小时，重试只返回历史记录，不修改时间或续期。新增 APPROVE_BUILD_RELEASE 审计动作。测试验证 RUNNING 构建拒绝、未确认拒绝、错误镜像拒绝、未登录拒绝、VIEWER 拒绝、并发只一行、服务端用户身份/时间、修改已有确认请求冲突、列表可读且部署行数仍为零。

首轮专项 26 项通过，但检查日志发现既有非管理员请求不存在服务器 ID=1 时，审计 server_id 外键错误导致事件丢失。修复 AuditLogService：不存在的服务器不设置关联外键，保留 textual resourceId 与失败事件；测试断言两个拒绝请求均保存 FAILED、resource_id=1、server_id=NULL。最新源码全量回归退出 0，日志 `/tmp/devpilot-manual-approval-final-regression.log`，日志无 ERROR。V31 仅测试数据库执行，两个真实 MySQL 实验环境仍为 V30。

边界：尚无确认 UI、撤销/消费流程或发布回调强制引用校验，不能宣称未经人工确认已被这套机制阻断。配置更新时间快照也还不是完整的配置变化防护，接入消费时需要可靠的配置版本/指纹核对与并发测试。后续须完成有效期、环境/目标变化、回调重放、确认记录与部署关联，以及真实发布和恢复验收。

## 2026-09-05：人工确认的撤销与单次发布绑定校验

尚未部署的 V31 补充 configuration_fingerprint、revoked_at、consumed_by_run_id、consumed_at。确认时对目标环境/服务器、部署类型、健康/访问地址、仓库/分支、Provider 地址/资源/凭据密文、回调密钥密文、发布策略及环境变量 revision 生成确定性 SHA256 指纹；不包含会随观测变化的健康状态和验证时间。指纹不在 API 返回，不解密或输出凭据。

内部 consume 方法使用应用及确认行锁，要求本应用的确认、相同构建/commit/digest、未撤销未过期、当前指纹及成功构建证据仍一致，然后绑定唯一发布 Run ID。同 Run 重试不修改绑定时间，也必须重新检查指纹与有效期；不同 Run 不可复用。一份确认不能同时授权两次发布。撤销接口允许 ADMIN/DEVELOPER、保留撤销历史并审计 REVOKE_BUILD_APPROVAL；已消费拒绝撤销，不宣称可以取消已开始部署。

测试扩展匹配失败、配置时间不变但超时设置变化、确认过期、撤销后消费拒绝、同 Run 并发复用、不同 Run 拒绝、已消费拒绝撤销、环境变量更新失效、不同 Run 并发仅一方成功，消费自身不产生部署。全量回归退出 0（`/tmp/devpilot-approval-consumption-regression.log`）；随后加强同 Run 重试的重新校验并增加环境变化后的已消费重试拒绝断言，最新专项 26 项通过（`/tmp/devpilot-approval-consumption-final.log`）。

边界：consume 是内部服务方法，目前由集成测试通过真实 Spring 事务调用，尚未接入实际回调或部署队列。因此不能宣称已阻断所有未经确认发布。下一步必须让发布入口强制使用并持久关联 approvalId，覆盖队列执行前配置变化以及历史/重复回调，完成 UI 与 workflow 传递；不能以内部方法通过测试替代整条门禁验收。V31 未部署到真实 MySQL，仍需迁移/备份恢复证据。

## 2026-09-05：发布回调与队列接入确认门禁（回归迁移未完成）

新增 V32 cicd_pipeline_run.manual_approval_id 和回调 manualApprovalId。普通 RELEASE 缺失确认或来源构建时停在 AWAITING_APPROVAL，不调用 Provider；startRelease（即时和队列共用）消费并重新核对确认。拒绝只记录待确认原因，不创建部署。consume 的 BusinessException 在写入前抛出，配置 noRollbackFor 使外层可持久化待确认状态；数据库等其他异常仍回滚。成功部署的 triggeredBy 来自确认的认证用户。

待确认的同一已成功发布允许补充确认重试，仍保持构建/commit/digest/CI 发起记录一致；非成功的迟到回调不恢复发布。已提交或已完成发布拒绝更换确认 ID。构建专用回调不允许声明人工确认 ID。队列遗留未确认记录也必须经过 startRelease 校验，未增加兼容绕过开关。Promote/人工回滚仍走已有认证用户入口，自动回滚仍受显式开关限制，需后续整体验证证据关联。

两个重点场景独立重跑通过（`/tmp/devpilot-approval-gate-acceptance.log`）：缺失人工确认的签名成功回调不部署；补充确认可持久关联；已有构建关联场景增加真实 POST approval 后成功部署一次，重复回调不重复部署，修改来源仍拒绝。未经确认的配置创建人不再被当成发布确认人。

全量回归当前**未通过**：70 项中 6 failure、3 error，日志 `/tmp/devpilot-approval-gate-regression.log`。九个旧场景（磁盘水位、旧健康端点、自动/人工回滚、环境同步、Agent 离线、发布串行、签名回调、跨环境提升）仍直接发送旧成功回调/sha 标签并期待部署，因新增门禁停在待确认，后续取不到部署记录。需要迁移测试夹具为实际成功 digest 构建 + 认证用户确认，不允许关闭门禁、跳过测试或把其写成已通过。新 UI/模板输入和队列失效场景仍待补齐。当前源码不是可发布稳定版，两个运行 MySQL 实验镜像仍为 V30，未受本轮源码门禁影响。

## 2026-09-05：发布测试夹具迁移与过期队列恢复

迁移此前失败的九个测试场景：新的 approvedCallback 测试辅助方法先使用派生构建密钥 POST 成功构建，再以真实初始化管理员登录 Token POST 人工确认，最后生成携带来源构建/确认 ID 的签名发布回调。没有直接插入确认行、伪造审批用户名/时间、模拟掉确认服务或关闭门禁。

这些部署/回滚/提升场景的成功镜像从 sha 标签迁移为固定 digest 测试值；相应 Provider 参数、运行快照和版本断言同步核对 digest。原本测试失败扫描、sha 标签与 commit 不匹配的负例保留；Preview 等本轮未涉及场景没有批量改写。流水线列表断言适应真实新增的构建行，仍核对指定发布 Run 和镜像，而不是假定只有一条回调记录。

先完成原 26 项 CI/CD 专项回归（`/tmp/devpilot-approved-fixtures-tests.log`），再新增排队期间确认过期场景：Agent 离线使已确认发布排队，到期后恢复 Agent 并驱动队列，发布退回 AWAITING_APPROVAL、部署行数为 0、旧确认未消费；通过确认接口重新确认同一 digest 后重发同一发布，成功 TRIGGERED 且只有一个部署。验证队列不会因为之前带过确认 ID 就忽略有效期。

最新后端全量回归通过，日志 `/tmp/devpilot-approved-fixtures-regression.log`，未跳过失败项或降低门禁。测试仍是 H2 + MockMvc + 模拟 Provider；不替代真实 GitHub/GHCR/Dokploy 验收。UI 人工确认操作、workflow 传递、V31/V32 MySQL 迁移/恢复及真实发布记录关联仍待完成，源码仍不应作为已验收稳定版发布。

## 2026-09-05：人工确认面板、目标快照与 GitHub 输入

新增 ManualReleaseApprovals 面板，发布中心选择成功 digest 构建后只读获取目标快照，展示应用、环境、服务器、commit 和镜像，明确勾选后创建确认。按用户/应用/构建保存 requestId 和非凭据快照，丢失响应时复用；成功后清除标识，历史通过列表读取。提供确认用户/UTC 时间/目标/ID/使用或过期状态、复制 ID 及未使用记录的明确撤销。配置冲突需要先确认结束旧请求、重新读取目标并再次勾选。

后端新增 approval-context 只读接口；确认 Request 的 expectedFingerprint 为必填，保存前比较当前指纹，同请求重放也核对原指纹，避免用户打开旧页面后被悄悄确认到另一个目标。测试夹具通过真实 GET context 再 POST approval，不绕过快照校验。原 CI/CD 27 项专项通过（`/tmp/devpilot-approval-context-tests.log`），随后增加伪造/陈旧快照指纹拒绝断言，人工确认专项通过（`/tmp/devpilot-approval-stale-context.log`）。

GitHub 生成模板新增必填 manual_approval_id 输入，通过 MANUAL_APPROVAL_ID 环境变量传递、先校验 UUID 格式，再把 manualApprovalId 纳入签名发布回调；原 build_run_id 和 provenance/digest 校验保留。未扩大 Actions 权限、不自动覆盖远端 workflow。前端 15 项、类型检查及生产构建通过，日志 `/tmp/devpilot-approval-panel-build.log`；之后仅调整切换构建时清空重新核对勾选状态。

边界：新面板尚未真实浏览器验收，也尚未验证响应丢失/刷新/撤销的整段 UI 与后端交互。GitLab 未接入新确认参数传递，不宣称通过。两个真实 MySQL 实验镜像仍为 V30；V31/V32、真实 GitHub 发布与确认关联以及备份恢复仍待完成。此阶段只在源码与测试环境操作，没有创建外部 Key、隧道、Secrets 或触发真实发布。

## 2026-09-05：发布记录的确认引用展示

PipelineRunResponse 新增 manualApprovalId，由已持久化的流水线记录返回，不从 CI 发起人或任务时间推断。人工确认面板按该 ID 展示最近发布任务及部署状态，并将“仅收到引用”与服务端确认记录 consumedByRunId 匹配的“已通过消费校验”分开说明；两者都不表示应用已经健康上线。发布任务引用或部署状态变化时刷新确认记录，避免消费后页面一直保留待使用状态；不会因普通时间戳刷新反复查询。

成功签名发布及重复回调的集成测试增加 manualApprovalId 响应断言。CI/CD 27 项通过，0 失败/错误/跳过，日志 `/tmp/devpilot-approval-reference-tests.log`。前端 15 项通过，类型检查与生产构建退出 0，git diff --check 通过。此轮未进行浏览器交互验收、真实 MySQL 迁移或外部发布，相关完成边界不变。

## 2026-09-06：人工确认浏览器恢复与撤销验收

扩展 scripts/test-stability-ui.cjs，在全新隔离 H2 后端中通过认证 HTTP API 创建应用和配置，使用派生构建密钥上报成功 digest 构建。Playwright 在确认 POST 已得到后端 200 后丢弃首次响应；刷新浏览器后重新选择构建、读取原请求并明确勾选，重放完整相同的 requestId/目标快照，返回同一确认 ID、认证用户名和服务端确认时间。确认只有一条，部署为零，成功后本地请求标识清除。

撤销通过页面确认框完成，后端记录 revokedAt。手机截图检查发现旧成功提示仍引导进入 GitHub，已修复为当前有效性提示；撤销后明确不能用于新发布，并移除该记录的发布输入指引。截图 `/var/folders/0k/bg28r22s5m53w39tn9b5krz80000gn/T/devpilot-stability-ui-lscrKO/approval-revoked-mobile.png` 已视觉检查。

再读取目标后经 API 改变健康超时配置，旧快照确认返回 409，记录数不增加。结束旧请求必须单独勾选，重新读取后发布确认勾选重置；再次确认生成新的请求标识和目标指纹，新增一条确认，仍无部署。完整浏览器脚本退出 0，无 pageerror，既有初始化、创建恢复及 Token 签发恢复场景仍通过。前端 15 项及生产构建通过，日志 `/tmp/devpilot-approval-ui-build.log`。后端全量 Surefire 报告合计 71 项、零失败/错误/跳过，日志 `/tmp/devpilot-approval-ui-full-regression.log`；进程句柄已结束不可读取退出码，以上结论依据落盘报告。核对 19090/19091 无监听，临时浏览器测试服务已停止。

这是实际浏览器与隔离测试后端的交互证据，不是外部 GitHub/Dokploy 发布验收。V31/V32 真实 MySQL 迁移、确认历史备份恢复及真实发布仍待完成。

## 2026-09-06：源隔离 Linux 环境 V30 → V32 升级

只读检查 devpilot-stability-lab 无宿主发布端口、仓库挂载只读、使用原独立 daemon 数据卷。升级前持久化及 V30 凭据签发验证脚本通过。审阅 V31 新确认表/索引、V32 新流水线确认引用列后，构建当前源码并通过 save/load 导入内部 daemon，仅推送内部 loopback Registry，没有 GHCR 推送或公开镜像。

使用 upgrade.sh --migrations-reviewed --yes 执行升级，server digest `sha256:e43fa39f94901f3e1847053733e505a8a4d3898d3a97ee2b2320c6226cbba99d`，web digest `sha256:563dcff72c44f532114f4f8c39a7891763ec804aed0f2588c49c8cc0f4b1d789`，仓库前缀分别为 `127.0.0.1:15000/devpilot/server`、`web`。升级前备份 `/opt/stability-v32-upgrade-backups/devpilot-20260906T010411Z-4JLMaz.tar.gz` 及 SHA256 文件保留。

脚本退出 0，服务健康；真实 MySQL Flyway 日志显示从 30 依次应用 31/32，成功到 v32。升级后真实 HTTP 验证管理员登录、服务器身份、应用变量解密/脱敏、初始化配置以及旧签发请求解密重放通过，新 Token 注册成功、已撤销旧 Token 拒绝。构建日志 `/tmp/devpilot-server-v32-build.log`、`/tmp/devpilot-web-v32-build.log`，升级日志 `/tmp/devpilot-v32-upgrade.log`。

本轮只升级源实验环境，独立恢复实验环境仍是 V30。确认记录真实 MySQL 写入、发布记录及确认记录独立恢复、真实 GitHub 发布仍未验收；不能用迁移成功替代这些场景。没有修改原 Dokploy 业务环境或用户其他容器。

## 2026-09-06：真实 MySQL 确认记录写入与独立恢复

新增 scripts/test-release-approval-api.sh，仅接受 loopback 实验入口、已有私有管理员/服务器夹具目录，seed 要求新建 0700 结果目录。使用正式认证 API 创建独立 approval-persistence 应用，autoDeploy=false、Provider 地址为内部 loopback 9；通过派生密钥签名上报合成的成功 digest 构建，经目标快照接口和确认接口创建真实管理员确认。重复请求身份/时间/有效期不变，撤销后列表与原撤销结果一致，构建记录仅一条，部署为零。未直接插入数据库或伪造服务端确认身份。

源环境真实 HTTP seed 与 verify 均退出 0，私有夹具 `/opt/stability-v32-approval-evidence`。生成源 V32 备份 `/opt/stability-v32-backups/devpilot-20260906T010640Z-69JF2Y.tar.gz`，源和恢复目标 SHA256 一致为 `be9939a49ab2b1fbe7843ec2e244f773ffbce81806efe609f542fcd059eb34ed`。通过容器间 tar 流复制备份与私有客户端夹具，不复制 MySQL 数据卷，不在宿主输出密钥。

复核恢复实验环境使用另一独立 daemon 数据卷且无宿主发布端口。准备与源相同 V32 镜像 digest，upgrade.sh 先保留目标 V30 备份 `/opt/restore-lab-pre-v32-backups/devpilot-20260906T010710Z-gtoCwl.tar.gz`，升级成功。随后 restore.sh --yes 校验源备份/主密钥，只替换该恢复实验数据库并等待健康，退出 0；原目标数据可通过保留的 V30 备份和对应镜像恢复。未覆盖源数据库或用户业务环境。

恢复后 verify 通过：原构建 ID/digest 与成功状态保留；确认 ID/认证用户/时间/有效期保留；重放同请求不会增加记录，已撤销状态保持；部署为零。重新签名上报原构建被接受，验证恢复后的加密回调密钥可用于验签，且不新增流水线记录。既有持久化验证确认登录、应用变量解密/脱敏、初始化配置、真实 Agent ONLINE 及至少五条容器快照；V30 签发验证确认审计保留、同一加密结果可重放、新 Token 注册接受、旧 Token 拒绝。

备份日志 `/tmp/devpilot-v32-backup.log`，目标升级日志 `/tmp/devpilot-restore-v32-upgrade.log`，恢复日志 `/tmp/devpilot-v32-independent-restore.log`。两个实验环境现均为 V32。本轮使用合成构建证据，未调用 GitHub、GHCR 或部署 Provider；已消费确认、实际部署/回滚历史的恢复仍未覆盖，不宣称完整真实发布验收完成。

## 2026-09-06：GitLab 模板接入人工确认（本地兼容验证）

修正 GitLab 模板仍上报 sha 标签且没有来源构建的问题：image 使用 Buildx metadata-file 保存原 digest 及项目/流水线/job/commit/分支/仓库制品；build_report 使用独立派生密钥上报成功构建；production 依赖 build_report 并核对同一制品，明确要求手动作业和 DEVPILOT_MANUAL_APPROVAL_ID，签名回调包含来源构建、确认 ID、原 digest 和 CI 发起信息。生产不重建、不重新解析标签。未配置双架构或失败/取消自动上报，不宣称这些能力已完成。

RepositoryOnboardingClient 为 GitLab 配置四个变量，构建 URL/派生密钥限定 build-status-应用编码，生产 URL/密钥限定 production-应用编码，均 protected/masked/raw。测试验证四个写入及独立作用域，并验证未保护分支在写入前拒绝。确认面板按实际仓库类型展示 GitHub 或 GitLab 操作说明，未知 Provider 不给出虚假的可发布指引。

前端 16 项通过：四种 runtime 保留人工 gate、digest、制品和独立构建密钥；从生成模板提取两个相同 jq 校验条件，实际执行正确及错误 project/pipeline/commit/branch/repository/digest/job 输入。生成 NODE GitLab YAML 通过本地 Ruby YAML 解析，全部作业 script 条目为字符串；此检查不替代 GitLab CI lint/Runner 执行。类型检查和生产构建退出 0，日志 `/tmp/devpilot-gitlab-approval-web-build.log`。后端专项 11 项通过，随后全量 72 项零失败/错误/跳过，退出 0，日志 `/tmp/devpilot-gitlab-approval-full-regression.log`。

依据 GitLab 官方手动作业/变量文档和 Docker 官方 Buildx metadata 文档补充使用说明，链接位于 automatic-onboarding.md。未写入真实 GitLab 变量、提交 workflow、触发 Runner 或重建实验 V32 镜像；本轮修改仍是源码，GitLab 远端链路仍未验收。

## 2026-09-06：备份/升级/恢复互斥

三个维护脚本新增按安装目录的 Linux flock，使用 `.devpilot-maintenance.lock`，冲突立即非零退出。升级通过继承 descriptor 9 让内部备份复用同一 inode 的锁，没有环境变量或公开参数绕过；子备份不主动 unlock，父进程继续持锁到健康检查结束。拒绝锁路径为符号链接；内核释放锁后文件保留，不以删除文件实现解锁。安装预检增加 flock 依赖。

test-maintenance.sh 独立持有同一目录锁，逐一调用 backup/restore/upgrade 均拒绝，Docker 调用日志为空、环境文件校验不变、被拒绝备份目录未创建；释放后正常恢复通过。原损坏归档、SQL 导入失败、健康失败等测试保留。test-install-upgrade.sh 证明父升级内部备份可以完成，备份失败后不改写配置、不启动服务，后续操作能再次取得锁。两组 Linux 容器内模拟 Docker 测试退出 0，日志 `/tmp/devpilot-maintenance-lock-tests.log`、`/tmp/devpilot-maintenance-lock-upgrade-tests.log`；脚本语法和 diff 检查通过。

源真实 MySQL 实验环境执行新 backup.sh 成功，归档 `/opt/stability-v32-locked-backups/devpilot-20260906T011646Z-6GAgBJ.tar.gz` 及校验文件保留，日志 `/tmp/devpilot-locked-real-backup.log`。再由独立 descriptor 8 持锁运行备份，确认收到冲突并且目标备份目录未创建。未进行真实数据库并发恢复，以模拟 Docker 验证危险入口的拒绝行为。

边界：锁只覆盖新版三个维护脚本，不约束直接 Docker/SQL、旧脚本、首次安装或卸载。尚未覆盖宿主突然断电/内核重启验收；不能把锁测试当作完整故障恢复证明。维护文档说明冲突后的补跑及不要删除锁文件。实验运行镜像尚未重建，新的维护脚本来自工作区只读挂载。

## 2026-09-06：首次安装保留配置并继续

install.sh 新增 --resume，仅允许新版完整生成配置但尚未健康完成的安装。生成配置前使用 mkdir 原子占用目标目录，避免两个相同目标安装在预检后相互覆盖；之后持有安装目录的维护锁。配置生成完成保存三份文件的校验清单，启动健康完成才写完成标识。失败保留配置和卷并给出可执行的恢复提示；配置生成未完成则明确不支持自动继续。

继续模式在锁内重新检查完成状态和文件校验，拒绝覆盖端口/URL/镜像参数，不生成新密钥。Compose 子进程清除原配置文件中各键对应的 shell 环境覆盖值，确保已保存密钥/镜像优先，同时保留 Docker 连接环境。--offline 不拉取镜像且 up 使用 --pull never，避免缺失本地镜像时隐式拉取。

扩展 test-install-upgrade.sh：模拟首次健康失败后校验原 env/compose，覆盖参数拒绝、锁冲突拒绝、Nginx 文件变化拒绝且无 Docker 调用；恢复原文件后继续成功、env/compose 校验不变，注入的 shell 镜像/主密钥覆盖值未传入 Compose；离线不执行 pull，带 --pull never；完成后再次 --resume 拒绝。原安装/升级及备份失败场景仍通过。Linux 隔离容器测试退出 0，日志 `/tmp/devpilot-install-resume-tests.log`；bash 语法及 diff 检查通过。

没有在真实运行实例伪造未完成标识或重装平台。真实镜像拉取中断、系统断电及不同安装路径竞争同一 Compose 项目尚未验证；不把本轮模拟测试算作完整首次安装灾难恢复验收。当前运行 V32 镜像尚未包含新安装脚本。

## 2026-09-06：真实 Registry 失败后的离线安装继续

新增测试专用 scripts/fixtures/install-lab.Dockerfile，基于本地 docker:28-dind 安装 bash/curl/openssl/iproute2/flock/jq 等依赖。新实验容器 devpilot-install-resume-lab 无外部网络、无宿主发布端口、无宿主 Docker socket，工作区只读，独立 daemon 卷 devpilot-install-resume-lab-data，限制 2 CPU/2 GiB。没有复用或改动已有业务 daemon 数据。

在干净实验 daemon 调用当前 install.sh，将应用镜像指向内部 127.0.0.1:9，真实 Docker pull 连接拒绝后安装退出 1，输出 --resume 恢复指引。配置清单校验三文件一致，无完成标识。通过 docker save/load 导入 V32 server/web 和 mysql:8.4、redis:7.4-alpine、nginx:1.29-alpine，并给应用镜像添加原配置要求的本地标签；未改写任何安装配置或密钥，也未连接真实 Registry。

执行 install.sh --resume --offline，真实 Compose 以 --pull never 启动五个服务并等待健康，退出 0，写入完成标识；原三文件校验仍一致。通过正式 HTTP 完成管理员初始化、登录、服务器注册记录创建与读取，完成后再次 --resume 被拒绝。拉取失败日志 `/tmp/devpilot-real-install-pull-failure.log`，继续安装日志 `/tmp/devpilot-real-install-resume.log`，私有管理员/服务器夹具保存在实验容器 `/opt/install-resume-evidence`，没有输出凭据。

随后重启整个外层实验容器及其 Docker daemon；内部五个容器自动启动。只读等待 API 就绪后，setupRequired=false，原管理员登录与服务器身份验证通过，三份配置校验保持一致，五服务均健康。这是 Linux 容器/daemon 重启，不是宿主内核重启或断电证据；Registry 测试是连接失败，不是已传输部分镜像层后的断流。

本轮真实实验使用 V32 应用镜像和当前挂载的安装脚本，未将源码打成新正式发行镜像。实验结束停止新实验容器以释放运行资源，保留容器、独立卷和私有夹具便于复核，不删除数据。原两个稳定性实验环境和 Dokploy 业务环境未停止。

## 2026-09-06：维护操作固定保存配置来源

将安装继续中防止 shell 覆盖的处理扩展到 backup/restore/upgrade 的全部 Compose 调用。子 shell 读取安装器 `.env` 的变量名并清除对应 shell 值，然后显式传递该安装目录的 env-file 与 Compose 路径，不 source 文件、不修改文件，保留 Docker 连接环境。升级的明确镜像参数仍负责更新已保存引用，不能被终端残留 DEVPILOT_SERVER_IMAGE 覆盖。

模拟 Docker 验证增加错误 MYSQL_DATABASE、DEV_PILOT_MASTER_KEY、DEVPILOT_SERVER_IMAGE 环境值，所有对应 Compose 子进程必须不存在这些覆盖值。两组 Linux 测试退出 0，原损坏归档、锁冲突、嵌套备份、SQL 导入失败、健康失败和安装继续测试仍通过，日志 `/tmp/devpilot-maintenance-environment-tests.log`、`/tmp/devpilot-upgrade-environment-tests.log`。语法与 diff 检查通过。

源真实 MySQL 实验环境在注入错误数据库名、主密钥、镜像变量后执行新备份成功，归档 `/opt/stability-saved-environment-backups/devpilot-20260906T012805Z-GYL8NF.tar.gz` 及 SHA256 文件保留，日志 `/tmp/devpilot-saved-environment-real-backup.log`。本轮未再次恢复或升级真实数据库，不能将真实备份成功外推为所有维护入口已完成真实异常验收。新版脚本尚未打入运行 V32 镜像。

## 2026-09-06：卸载互斥与退出状态

uninstall.sh 取得与安装/备份/升级/恢复相同目录的内核锁后才验证 Compose 并执行 down，锁占用时明确拒绝。Compose 使用保存的 env-file，清除同名 shell 覆盖值。保留原人工确认和显式 --purge 约束，不删除配置文件。修正脚本末尾以 false 条件结束导致默认保留卷卸载返回非零的问题；成功时明确卷保留，Docker down 失败不打印成功移除提示。

test-maintenance.sh 增加默认卸载不含 --volumes 且成功退出、人工取消不调用 Docker、持锁时 --purge --yes 也被阻断、显式 purge 命令正确且环境文件不变、down 失败不打印成功等断言。测试使用本地 Docker 替身，没有对真实环境执行 down 或删除任何卷。修复前测试首先因 shell 覆盖泄漏返回 19；修复后全部维护场景通过，安装/升级回归也通过。日志 `/tmp/devpilot-uninstall-before-fix.log`、`/tmp/devpilot-uninstall-maintenance-tests.log`、`/tmp/devpilot-uninstall-lifecycle-regression.log`；脚本语法及 diff 检查通过。

本轮未真实执行卸载，不声称已经验证真实 Docker 删除结果。原实验和业务容器、数据卷不变；运行 V32 镜像尚未重建为当前脚本版本。外部发布验收的临时 Key/回调隧道/测试仓库 Secrets 授权仍未得到明确回复，没有实施这些外部变更。

## 2026-09-06：通知失败重试与成功记录防重发

AutomationWebhookService.retry 原先可对任意状态重置排队，现仅允许仍为 FAILED 且读取时尝试次数未改变的记录，通过条件 UPDATE 重新排队；并发或重复请求仅一个成功，其余返回 409。清除旧 response/error/sentAt，保留事件 ID 与 payload，显式人工重试开启新的最多五次自动投递窗口。成功或已排队记录不能被再次重发，删除订阅仍拒绝。

新增 H2 + MockMvc + 真实本地 HttpServer 集成测试：接收端先返回 503，记录 FAILED、attempt=1、sentAt 空且 nextAttemptAt 晚于 updatedAt；未到期再次调度没有 HTTP 请求。两个并发认证重试分别返回 200/409，后续重复点击仍 409；接收端改为 204 后原 payload 与 X-DevPilot-Delivery 不变，状态成功、sentAt 非空、响应码 204、error 清除。成功后重试被拒绝，再次调度没有新增 HTTP 请求，数据库始终一条记录。

专项两项通过；加入并发断言后全量后端 73 项、零失败/错误/跳过，进程退出 0，日志 `/tmp/devpilot-notification-retry-regression.log`。diff 检查通过。文档更正自动投递上限为包含首次共五次，而非首次之外再重试五次。

边界：这是本地 HTTP 接收端与测试数据库，不是私有 GitHub/Dokploy 真实发布产生的通知。多工作进程并发领取的完整防重仍待实现/验收；接收方处理成功但响应丢失时仍可能收到相同事件，应按事件 ID 幂等。不能以手动重试防重复替代端到端 exactly-once 或多实例投递安全声明。运行 V32 镜像未包含本轮后端修改。

## 2026-09-06：通知数据库领取与过期恢复

新增 V33 claim_token/claim_expires_at 及索引。调度先以条件 UPDATE 原子领取待发送或过期记录，标为 SENDING，增加尝试计数并给出五分钟租期；未取得记录的工作进程不发送。每次领取 UUID 独立，回写仅允许 id/status/claim_token 匹配，旧工作进程无法覆盖新领取者的数据库结果。领取时清除旧响应/错误/送达时间，退避与送达时间以 HTTP 完成时计算，而非批次开始时间。

次数在领取时增加，因此进程在领取后中断也消耗一次尝试。小于五次的过期领取可继续，达到五次且过期则转 FAILED、清除领取信息并显示接收方结果未知，不无限自动重试。事件 ID 和 payload 保留，仍要求接收方去重，不承诺端到端 exactly-once。

扩展真实本地 HttpServer 测试，利用 latch 保持请求发送中：第二工作进程调度无额外 HTTP 请求，人工重试 409，数据库 SENDING 有领取信息而无旧 response/error/sentAt。释放接收端后成功。通过测试 SQL 将领取时间设为过期来模拟进程丢失，第四次后可再领取成功、事件 ID 不变；第五次领取过期后无 HTTP 请求且 FAILED/结果未知。不是实际杀进程，也没有等待五分钟墙钟时间。

后端全量 73 项、零失败/错误/跳过，退出 0（`/tmp/devpilot-notification-claim-regression.log`）；随后增加领取时清空旧响应并加强断言，最新通知专项两项通过（`/tmp/devpilot-notification-claim-final-tests.log`）。diff 检查通过。V33 仅在 H2 测试数据库执行，运行实验镜像/真实 MySQL 仍为 V32。真实多实例领取、旧工作进程迟到回写及 MySQL 迁移/恢复还需要进一步验收，不能用本地并发测试替代完整多实例可靠性证明。

## 2026-09-06：迟到响应防覆盖与源 MySQL V33 升级

新增真实本地 HttpServer 双工作线程测试：第一个发送请求等待响应期间，通过测试 SQL 将领取时间提前为过期；另一个发送进程领取并发起第二个请求，确认领取 UUID 已更换。第二个请求也保持未响应时，释放旧请求返回 204，旧进程结束后数据库仍为新进程的 SENDING，状态/次数/响应/送达时间/领取 UUID/时间戳和重试时间与旧响应前完全一致。随后第二请求返回 503，最终正确记为 FAILED、attempt=2、response=503，而不是旧响应的成功。两次载荷一致。

首版迟到成功不覆盖新失败结果场景通过后，加强为新进程仍持有领取权时验证 token 防护，最新三项通知专项通过、无失败/错误/跳过，退出 0，日志 `/tmp/devpilot-notification-late-owner-tests.log`。这是两个本地线程、真实 HTTP 和 H2，不是两个真实服务实例或实际暂停五分钟。

构建当前应用及脚本为实验 V33 镜像，经 save/load 导入源隔离 Linux 内部 Registry；server digest `sha256:fc050dde7bf350364edee6669ba92792bf76d5002389e4573bd495ea6fc3182c`，web digest `sha256:500475ec299f09f409a62a061e4d128b48a892ae4020340789552d5c99f23f7a`，仓库前缀仍为内部 `127.0.0.1:15000/devpilot/server`、`web`。审阅 V33 新增字段/索引，升级前原配置、Agent、确认及撤销历史验证通过。

通过当前带锁和保存环境隔离的 upgrade.sh，保留升级前备份 `/opt/stability-v33-upgrade-backups/devpilot-20260906T014225Z-YL7lhC.tar.gz` 与校验文件，再升级到精确 digest，退出 0。真实 MySQL 日志显示从 32 应用 33，成功到 v33，服务健康。升级后管理员、变量解密/脱敏、初始化配置、真实 Agent ONLINE/至少五条快照、构建/确认/撤销记录及回调验签、旧签发结果解密重放/新 Token 接受/旧 Token 拒绝均通过。

日志 `/tmp/devpilot-server-v33-build.log`、`/tmp/devpilot-web-v33-build.log`、`/tmp/devpilot-v33-upgrade.log`。源环境现为 V33，独立恢复环境仍为 V32；尚未验证 V33 通知队列真实多实例领取及备份恢复。没有向 GHCR 推送、创建外部 Key/Secrets 或变更 Dokploy 业务环境。

## 2026-09-06：真实 MySQL 通知失败与人工重试验收

新增 `scripts/test-notification-api.sh`（seed/verify/retry）及仅供验收的 Go 接收端 `scripts/fixtures/notification-receiver.go`。脚本限定回环 API，复用私有管理员夹具，创建独立应用和 BUILD_FAILED 订阅，通过真实签名构建回调产生事件，不直接插入数据库，不触发部署。verify 重放同一失败构建后要求通知记录仍只有一条且 ID/eventId 保持不变；成功记录再请求人工重试必须 HTTP 409/code 40973。

在源 V33、真实 MySQL 和后台定时调度下执行成功。接收端为静态 Linux ARM64 程序，运行于实验后端网络命名空间，仅监听 127.0.0.1:18889，无主机或公网端口。启动接收端前连接失败消耗四次尝试，第五次接收端验签成功并返回 503；随后改为成功模式并通过认证 API 人工重试，收到 204，数据库变为 SUCCEEDED/responseCode 204/sentAt 非空/errorMessage 空。

接收端记录恰好两次真实 HTTP 请求，均签名有效，eventId 与 payload SHA256 一致：事件 `7d2f50df-110a-44e9-96b3-9b2b437d1d54`，载荷摘要 `31b9f2014ae97cb996c6a213015d0f0f19166bc268048baaad0eb66b9333c8db`。认证回调重放仍只有一条通知、零部署，成功重试被拒绝。私有证据保留于源实验容器 `/opt/stability-v33-notification-evidence`，含 API 响应及 received.jsonl，不输出密钥或完整载荷。

后端完整 Maven 回归退出 0，Surefire 共 74 项、零失败/错误/跳过。Go 接收端交叉编译成功，脚本语法及 diff 检查通过。临时接收端 PID 602 已精确停止并确认无匹配进程，保留证据和夹具供下一步独立恢复比对。原平台、Agent 和 Dokploy 业务容器未重启或删除。

范围限制：本轮是单真实后端实例与真实 MySQL 的通知投递验收，不是外部 GitHub 工作流失败或真实多实例领取；V33 队列和通知凭据的独立备份恢复尚待完成。没有创建外部 Key、隧道、Secrets 或向 GHCR 推送。

## 2026-09-06：V33 通知队列与加密凭据独立恢复

扩展通知验收脚本 enqueue-recovery/retry-recovery：通过另一个签名失败构建生成恢复专用通知，按订阅及构建 subject 核对记录，保存原 ID/eventId，恢复后只允许对同一 FAILED 记录人工重试。原成功通知仍核对重放幂等与成功重试 409。未直接写业务数据库。

源环境在接收端已停止时创建恢复事件，使用 backup.sh 生成 `/opt/stability-v33-recovery-backups/devpilot-20260906T015646Z-czR4Kx.tar.gz` 和校验文件。源与目标归档 SHA256 均为 `31deeda27d04a705e74ef3041a8efbf18d42e89ababf60cddeae027daf583894`。另有此前只含成功通知的备份 `/opt/stability-v33-backups/devpilot-20260906T015544Z-oYF7Du.tar.gz` 保留，不混为同一验收输入。

将已有 V33 server/web 镜像通过 save/load 导入独立恢复实验环境的内部 Registry，实际 push 返回 digest 与源 V33 一致。审阅 V33 字段/索引增量后，通过 upgrade.sh 先保留目标原数据库及配置备份 `/opt/restore-lab-pre-v33-backups/devpilot-20260906T015646Z-71CbR9.tar.gz`，再升级目标 V32 至 V33，健康检查成功退出 0。升级期间服务停止导致备份状态回报连接失败，脚本明确警告但备份归档有效；这不是升级失败，也不代表状态上报已成功。

在专用于恢复验收的目标环境执行 restore.sh --yes，校验归档及主密钥、替换其测试数据库并启动健康检查，退出 0。未覆盖原 Dokploy 业务环境或源数据库。目标现在也使用 V33 精确 digest。恢复后真实管理员登录、初始化状态、变量解密/脱敏、原服务器身份、Agent ONLINE/至少五条容器快照、构建 digest、确认人与时间、确认撤销历史、轮换审计及旧签发结果解密重放、新 Token 接受/旧 Token 拒绝均通过。

恢复专用通知在目标数据库中保持原 ID/eventId，处于 FAILED。经认证 API 人工重试，在目标后端回环网络启动独立接收端：实际收到一条 HTTP 请求，返回 204，使用源夹具保存的原订阅密钥验签成功；数据库同一事件成为 SUCCEEDED/responseCode 204/sentAt 非空/errorMessage 空。这证明恢复后的后端能够解密订阅地址和签名凭据并继续发送，而不是仅展示数据库记录。原成功事件仍保持成功且禁止重发，业务部署始终为零。

目标私有证据 `/opt/stability-v33-notification-evidence` 保存恢复前后 API 结果、原事件身份及目标接收 received.jsonl；源同路径保留其原接收证据。目标临时接收端 PID 120 已停止并确认无匹配进程，源接收端仍停止；归档、校验、实验数据保留。脚本语法与 diff 检查通过。本轮未修改后端运行代码，上一轮 74 项全量回归结果仍为最近一次全量结果。

尚未覆盖真实多实例并发领取、持有 SENDING 租约时进程崩溃后的跨环境恢复，亦非真实外部 CI 构建通知或云主机重启验收。外部临时 Key/回调隧道/Secrets 未变更，稳定版完整目标仍未完成。

## 2026-09-06：取消上报分类与发布指引修正

检查发现 GitHub build reporter 原先将 cancelled/skipped 的测试和扫描均报告为 FAILED，最终状态也统一失败。模板现保留真实 failure 优先，否则存在 cancelled 则报告 CANCELLED；取消/跳过门禁为 SKIPPED。全成功仍要求有效 digest，改为显式错误退出以避免依赖 shell 的隐式失败退出语义。生成脚本的状态计算片段通过本地 Bash 实际执行，覆盖质量取消、镜像取消、失败与取消并存、全跳过、全成功及非法 digest。首轮新增测试发现非法 digest 未按测试预期非零退出，显式拒绝后通过。

发布中心抽出 releaseGuidanceFor 并接入页面：待确认先引导 DevPilot 人工确认，再按 GitHub/GitLab 给出不同步骤；修正直接去 GitHub 的旧提示。取消不再说明为测试失败；失败不承诺旧健康版本仍运行，历史 HEALTHY 要求核对当前状态与更新时间；补充回滚失败/恢复与队列重新校验提示。两项纯函数测试验证指引及证据边界。

前端 19 项测试全部通过，vue-tsc/Vite 生产构建成功。后端扩展 buildOnlyKeyCannotDeployAndLateEventsCannotRegressBuild：CANCELLED 后迟到 RUNNING 和重复取消仍返回 CANCELLED/SKIPPED/TERMINAL_REPORTED，零部署；专项 Maven 测试退出 0。本轮不是后端全量重跑。diff 检查通过。

边界：尚未真实在 GitHub 取消工作流，整个 workflow 被取消仍可能跳过最终报告，因此不能声称解决所有 Running 停滞或已实现主动平台状态核对。本轮未修改远端仓库工作流、Secrets 或运行 V33 实验镜像；UI 为源码及生产构建验证，未新增浏览器验收。

## 2026-09-06：发布列表状态筛选补齐

修正 CicdView 筛选仅按部署状态判断造成的遗漏：原 FAILED 视图漏掉 BUILD_FAILED 和未开始部署的 CI 失败，ACTIVE 漏掉 BUILDING。新增可独立测试的 matchesPipelineFilter，并接入实际列表。失败/回滚、取消、待确认、状态未知分开展示；STALE 只归入全部/未知，不视为确知正在执行或失败；取消不归入失败。待确认须同时有 SUCCEEDED 与 AWAITING_APPROVAL，健康筛选明确标为历史“健康发布记录”。筛选控件添加可访问名称。

三个新增测试覆盖构建及部署失败、取消排除、STALE 排除、待确认门槛、持久队列与部署执行状态。前端总计 22 项通过，生产构建通过。随后修正步骤条在无任务、取消/跳过或 STALE 时仍显示质量门禁 active 的问题，STALE 文案要求核对，BUILD_FAILED 纳入失败显示；最新源码类型检查通过。步骤条后续修改尚未再跑 Vite 构建或浏览器验收，不能将函数测试视为页面交互验收。

本轮仅修改前端源码、测试及记录，未更新运行镜像或外部 CI 配置。主动查询 CI 终态、完整外部链路及稳定版总体验收仍待完成。

## 2026-09-06：镜像漂移判断增加观测证据

原发布中心仅比较期望镜像与 Application.dockerImage 字符串，不同即显示漂移，也未检查 Agent 是否在线或清单是否过期。ApplicationResponse 新增 agentStatus、containerObservedAt，直接来自服务器状态和绑定容器 lastSeenAt，无数据库迁移；前端字段可选，连接旧后端时保守显示证据不可用。

新增 imageDriftFor 并接入发布中心：要求 Agent ONLINE、容器观测时间在最近 60 秒且不超前超过五秒；UTC 无偏移时间按 UTC 解析。只有双方包含完整 sha256 digest 才比较，镜像库路径不同但 digest 相同可认定摘要一致；标签和本地 image ID 不作为 digest 证据。无/过期/离线证据显示未知，不声明业务故障；一致提示明确不等同于应用健康。页面每 15 秒刷新证据时钟，即使网络刷新失败、旧数据对象未变，也会让过期清单失去一致状态。

两项新增函数测试覆盖同/不同 digest、不同镜像路径、标签、本地 image ID、缺失/非法/过期/未来时间、Agent 离线及旧接口缺少字段。前端 24 项全通过，最后时钟修改后的 vue-tsc/Vite 构建成功。后端 replacement-container 专项增加响应 agentStatus ONLINE 和 containerObservedAt 非空断言，最新专项退出 0；不是后端全量回归。diff 检查通过。

边界：没有扩展 Agent 去解析任意标签的 Registry digest，也没有把多架构 index digest 与平台 manifest digest 当作等价；缺少可比较证据时如实未知。未更新运行镜像，尚需浏览器及实际 Agent 数据验收。稳定版完整交付仍未完成。

## 2026-09-06：应用运行未知状态与只读观测

ApplicationService.toResponse 原先直接根据绑定快照返回运行状态，并在 GET 时改写 application.status/updatedAt。现按 Agent 在线、容器关联及最近 60 秒快照判断观测是否有效；离线、无关联、缺失/过期或异常未来时间返回 UNKNOWN，新增 runtimeObservationMessage 解释未知不代表应用停止。不再因读取改写业务状态和更新时间；健康结果保留为历史检查证据，未伪造新的健康结果。

应用详情展示观测说明、Agent 状态、容器采集 UTC 时间；健康卡明确为“最近健康检查”并附原始检查时间，避免历史 HEALTHY 被当成当前运行保证。未删除历史容器、配置或健康结果。

新增集成测试先使 Agent OFFLINE，再恢复 ONLINE 但将快照设为五分钟前，分别验证 UNKNOWN 和说明；读取前后数据库 status/updatedAt 完全一致。完整后端 Maven 回归退出 0，75 项、零失败/错误/跳过；前端 vue-tsc/Vite 构建通过。diff 检查通过。

尚未更新运行镜像或完成浏览器断网验收。另发现 Dashboard/指标的聚合异常计数仍使用独立 SQL，且 metrics 通过总数减异常数推导健康数，不能用本轮详情改动声称全系统已区分未知与健康；这些聚合口径需继续修正并测试。稳定版总目标仍未完成。

## 2026-09-06：Dashboard 与应用监控指标健康分类统一

ApplicationMapper 新增单次关联查询，对每个应用按服务器状态、容器新鲜度、运行故障和健康探测分类；ApplicationHealthSummary 汇总 HEALTHY/UNHEALTHY/UNKNOWN。Dashboard 与 DevPilotMetrics 均使用该查询，不再将缺失容器算作异常，也不再以总数减异常数推导健康数。Agent 离线、无容器、新鲜度不足均为未知；配置健康 URL 时要求新鲜探测，无 URL 时要求 Docker 明确 healthy，而不将 running 本身当作健康。

Dashboard 响应增加 applicationHealthy/applicationUnknown，应用卡显示异常与未知数量；有未知时给出核对 Agent/时间/探测指引，不显示全部健康。前端连接缺少新字段的旧后端也保守提示待确认。指标增加 devpilot.applications.unknown/unhealthy，healthy 定义改为新鲜健康证据，监控文档同步说明兼容语义及采样成功标记。

新增集成测试覆盖新鲜健康、Agent 离线、过期失败探测、新鲜失败探测、过期容器、未关联容器；同时断言 Dashboard API unknown/healthy/unhealthy 和实际 MeterRegistry gauge，离线时 unknown=1/healthy=0。完整 Maven 76 项、零失败/错误/跳过，退出 0；前端 vue-tsc/Vite 构建成功。diff 检查通过。

边界：本轮数据库验证为 H2，尚需真实 MySQL 分类查询与运行页面验收；未升级运行镜像。服务器/容器汇总及其他旧告警逻辑不能据此声称已完成全范围未知状态治理。稳定版完整交付仍未完成。

## 2026-09-06：观测改动实验镜像升级与真实 MySQL 验证

将近期发布筛选/取消映射/指引、应用观测字段/只读状态及 Dashboard 分类变更构建为 server/web `stability-observation-v33` 实验镜像，数据库版本保持 V33，无新增迁移。镜像经 save/load 导入源隔离 Linux，再推送其内部 Registry，实际 digest：server `sha256:cdbec4e0e17a04bd088d1c38215b87b9a3138788a384c6e3e97f8f5b9dd6327a`；web `sha256:89df7b5f875d4a25b013605b12a1cea2a5c848949c5b02cce36e981cce46a207`，前缀均为 `127.0.0.1:15000/devpilot/`。未推送 GHCR。

通过 upgrade.sh 精确 digest 升级，保留升级前归档 `/opt/stability-observation-upgrade-backups/devpilot-20260906T021658Z-RdUo1u.tar.gz` 及校验文件，服务健康后退出 0。服务停止期间备份状态回报不可达但归档有效，日志明确警告。升级后管理员登录、初始化、应用变量解密/脱敏、真实 Agent ONLINE/至少五条快照、构建与人工确认/撤销身份时间、轮换审计与签发结果重放、新旧 Token 校验、通知原事件重放及成功禁止重发全部通过。

新增 test-observation-api.sh 验证真实 API 的健康/异常/未知分区及观测字段。第一轮断言失败定位到原三个测试应用均未绑定容器，缺少已绑定观测夹具，并非 API 字段丢失；其实际分类 total=3/healthy=0/unhealthy=0/unknown=3。新增显式 --seed-binding 模式，仅创建引用已发现容器的 TEST 应用，不部署、不重启或修改容器。新鲜健康容器绑定后分类 total=4/healthy=1/unhealthy=0/unknown=3，绑定应用返回 containerObservedAt，未绑定应用为 UNKNOWN/有解释。随后不带种子选项再次只读验证通过，不重复创建资源。

私有 API 证据分别保存在源容器 `/opt/stability-observation-evidence`（首轮缺夹具）、`/opt/stability-observation-bound-evidence`、`/opt/stability-observation-readonly-evidence`。脚本语法及 diff 检查通过。源环境已运行新观测镜像；独立恢复环境仍为此前 V33 镜像，原 Dokploy 业务环境未改变。尚未完成新版浏览器交互、多种故障状态的真实 MySQL 全矩阵或外部 CI 全链路验收，不能标记稳定版完成。

## 2026-09-06：运行镜像浏览器观测与筛选验收

新增 test-observation-ui.cjs，使用真实 Chromium 访问源实验环境当前 Nginx/前端与 MySQL 后端。浏览器请求由路由拦截，经 docker exec/curl 转发到隔离容器回环 API，不开启主机监听端口；拒绝外部请求和除认证外的非 GET/HEAD 操作，不伪造 API 响应、不创建或变更业务资源。管理员凭据仅在内存中使用，不输出。

修正测试自身的首页路由假设（实际为 `/`）、带箭头的链接名称匹配及应用下拉异步加载等待后，完整执行退出 0：实际登录；首页展示“部分应用健康状态待确认”；点击核对入口进入真实应用目录及绑定容器详情；详情可见 Agent 状态、容器采集时间和最近健康检查；进入发布中心选择通知测试应用，FAILED 可见原失败构建，CANCELLED/UNKNOWN 无匹配记录，切回 ALL 原构建仍可见。没有浏览器脚本错误或被禁止的业务写请求。

最终截图目录 `/var/folders/0k/bg28r22s5m53w39tn9b5krz80000gn/T/devpilot-observation-ui-INxOnW`，包含 dashboard.png、application.png、cicd-mobile.png。已视觉检查首页、桌面详情和 390px 移动发布页：未知提示、UTC 采集字段和筛选均可见；历史应用健康 UNKNOWN 与当前容器 RUNNING 分开展示。Chromium 在 finally 中关闭，未留下监听服务。

边界：取消/未知筛选本轮验证的是无匹配结果，不是注入取消、STALE 后的浏览器状态矩阵；未做真实断网跨过 60 秒的页面过期验收，也不证明外部 CI 工作流取消、发布或故障恢复。脚本语法、diff 检查通过。稳定版整体目标仍未完成。

## 2026-09-06：刷新失败不伪装为未配置或实时正常

CI/CD loadApplication 原先将配置接口全部异常 catch 成 null，存在网络/403/500 错误被当作未配置并重置表单的风险。新增 missingConfiguration，仅 HTTP 404 且业务码 40440（明确 CI/CD 配置不存在）转为 null，其他异常原样抛出，Promise.all 不进入覆盖配置阶段。测试验证网络错误、403、500、应用不存在 40420、缺业务码 404 及错误 HTTP 状态均不能吞掉。

Dashboard、应用详情、发布中心新增独立 refreshFailed 标记，刷新失败时保留旧数据并明确标注历史快照；该标记不随下一次请求开始消失，只有成功读取才清除。首页/发布活动不再无条件显示 LIVE，改为最近采样或历史记录，避免历史任务被描述为确知当前执行。保留原错误详情，不以一次网络错误触发业务变更。

前端 25 项测试全部通过，vue-tsc/Vite 构建成功；随后调整发布活动最近上报文案及标签，最新类型检查通过，diff 检查通过。本轮未修改后端或升级运行镜像，尚未通过浏览器断网/恢复故障注入验证新增提示，也未验证活动请求失败后的全部局部数据一致性。整体稳定版仍未完成。

## 2026-09-06：浏览器刷新失败与恢复故障注入

扩展 test-observation-ui.cjs：可显式使用当前仓库的生产 dist 静态文件，真实 API 仍转发到源隔离 Linux/MySQL；路径 realpath 限制在 dist 内，不开放监听端口。DEVPILOT_TEST_REFRESH_FAILURE=true 要求此模式。先正常登录并读取真实数据，再仅在浏览器路由层注入指定请求故障，其他 API 不伪造，仍禁止业务写请求。

最新前端完整生产构建后执行成功：Dashboard 请求中断模拟浏览器网络失败，切换时间范围触发请求，历史应用信息保留、刷新失败说明和“历史快照”可见；恢复请求并切回范围后警告消失。CI 配置 GET 注入 HTTP 503，点击刷新后原失败构建与选中应用保留、历史数据警告可见，没有退回空配置；恢复真实请求后警告消失。原应用详情及失败/取消/未知筛选检查也通过，无页面脚本错误或业务写操作。

最终截图 `/var/folders/0k/bg28r22s5m53w39tn9b5krz80000gn/T/devpilot-observation-ui-2z4o3q`，新增 dashboard-refresh-failed.png、cicd-config-503.png，已视觉核对首页失败警告与历史快照标签。进程退出 0，浏览器关闭。脚本语法、diff 检查通过。

边界：测试最新 dist + 已部署真实后端，不是更新后的整套前端 Docker 镜像；运行镜像仍为上轮观测版。故障由浏览器层注入，并非真的停止服务器或切断主机网络；尚未覆盖持续 60 秒过期、应用详情刷新失败和活动接口部分失败等矩阵。稳定版整体交付仍未完成。

## 2026-09-06：发布中心读取归属与整批更新

loadApplication 捕获发起时应用 ID，并使用 latestRequest 世代标记；只有最新且仍属于当前选中应用的请求可写入结果、错误或 loading。卸载时使请求失效。活动列表请求移入同一个 Promise.all，全部成功后同步更新，避免先更新配置/流水线再因活动请求失败留下半套新数据。后台 silent 轮询不再 Object.assign 配置表单，避免每 15 秒覆盖用户未保存输入。

页面记录 loadedApplicationId，切换到尚未成功读取的应用时暂不展示旧应用的操作区，显示读取中/失败重试入口；不会把上个应用的配置、确认或回滚按钮挂在新选择下。相同应用刷新失败仍保留原数据及历史警告。新增 latestRequest 单元测试验证新请求提交后旧响应失效、卸载失效与后续请求生效。

前端 26 项测试全部通过，最终界面保护改动后 vue-tsc/Vite 构建成功。最新 dist + 真实 MySQL 后端的浏览器回归再次通过：首页断网提示及恢复、CI 配置 503 保留旧记录及恢复、应用详情与筛选。截图 `/var/folders/0k/bg28r22s5m53w39tn9b5krz80000gn/T/devpilot-observation-ui-fOECwf`。无业务写请求，浏览器已关闭，diff 检查通过。

边界：本轮尚未在浏览器人为延迟旧应用响应并交叉切换验证竞态，也未验证正在编辑配置时的 15 秒轮询保留；世代单元测试不能替代这些端到端场景。未更新运行镜像，稳定版整体交付仍未完成。

## 2026-09-06：浏览器旧应用响应延迟竞态验收

扩展 test-observation-ui.cjs，在最新本地生产 dist + 真实 MySQL 后端模式下，截留 Notification fixture 的配置 GET 响应；切换到 Approval persistence fixture，并等待其真实构建记录显示，然后释放原应用响应。浏览器断言选中应用仍为新应用、新构建记录仍可见、旧应用构建记录不存在。全程禁止业务写请求，仅认证允许写入。

本轮重新执行脚本退出 0，首页断网保留历史快照及恢复、配置接口 503 保留记录及恢复、跨应用延迟响应、原详情与筛选均通过。截图目录 `/var/folders/0k/bg28r22s5m53w39tn9b5krz80000gn/T/devpilot-observation-ui-Cj090p`，新增 cicd-late-response.png。浏览器 finally 关闭，无残留测试进程；未更新运行镜像、未创建外部凭据或触发部署。

边界：延迟由浏览器层注入；尚未验证旧请求失败分支和配置编辑跨 15 秒轮询保留，不替代外部 GitHub、私有 GHCR 与真实部署验收。整体稳定版目标仍未完成。

## 2026-09-06：未保存配置跨后台轮询保留

浏览器验收在真实 Notification fixture 配置中仅编辑仓库 URL 和分支草稿，不提交保存；逐一等待两轮实际 15 秒计时器触发的九个 GET 响应全部成功接收，确认两个输入值、所选应用及原构建记录仍保留。没有人工刷新、假计时器或业务写请求代替轮询。测试结束仅恢复浏览器中的原输入值并关闭页面。

完整故障/竞态/轮询脚本退出 0，截图 `/var/folders/0k/bg28r22s5m53w39tn9b5krz80000gn/T/devpilot-observation-ui-IYaBbj/cicd-unsaved-draft-poll.png` 已视觉检查。同时为旧响应等待添加 30 秒失败上限，避免运输失败时测试永久等待。测试使用最新本地 dist 与隔离 MySQL 后端，运行前端镜像未更新；没有外部部署。

## 2026-09-06：未知证据不触发告警自动恢复

AlertEvaluationService 原先把服务器指标缺失/过期映射为条件 false，APP_UNHEALTHY 把 UNKNOWN 当健康，容器状态未校验时效，可能误发恢复或使用旧故障证据。现在内部观察使用 true/false/unknown 三态；未知重置未达持续时间的条件计时，但不自动解除已触发/已确认事件。资源从观察列表消失同样不能证明恢复。服务器指标要求在线且 120 秒内，容器/应用健康要求在线且 60 秒内；所有新鲜时间不得超前超过 5 秒。Agent 离线规则保持独立。

新增集成用例覆盖：已触发 CPU 告警经历离线、过期、未来时间、无样本仍保留，异常恢复采集不重复通知，真实正常证据只恢复一次；缺失样本重置持续时长；过期应用故障不新发告警，UNKNOWN/过期 HEALTHY 不解除已有告警，新鲜 HEALTHY 正常恢复。原容器重启风暴用例补入过期快照不误恢复断言。第一次测试因新增断言误写数据库字段 transition（实际 transition_type）失败，修正测试字段后六项告警集成测试通过；未放宽产品断言。

最终 `mvn -q test` 退出 0，汇总 Surefire XML 为 79 项测试、0 失败、0 错误、0 跳过，包含最终应用健康在线检查改动。脚本语法和 diff 检查通过。这些是 H2/MySQL 模式的集成证据，不是已升级运行镜像或真实网络通知恢复验收；仍需继续完成外部交付链路及稳定版总体验收。

## 2026-09-06：跨安装目录的主机级维护互斥

原目录锁不能覆盖不同路径竞争固定 Compose 项目 devpilot 的情况。五个生命周期脚本新增 `/run/devpilot-maintenance.lock`（FD 7），在 Docker 操作之前取得；原目录锁（FD 9）保留。升级子备份继承同一 FD/inode，不重新打开导致自锁。主机锁校验普通文件、非符号链接、root 所有、0600、单硬链接，退出依靠内核释放而不删除锁文件。首次安装也在生成配置及 Docker 预检之前取得锁。

Linux 容器内分别执行 `test-install-upgrade.sh`（maven:3.9-eclipse-temurin-21）和 `test-maintenance.sh`（debian:12-slim），均退出 0。网络禁用、仓库只读挂载，没有主机 Docker socket。新增断言独立持有 FD 6 主机锁后：新安装和 resume 拒绝且无 Docker 调用；原目录及复制目录的 backup/restore/upgrade/uninstall --purge 均拒绝，env 校验不变、备份目录不创建。释放后完整安装继续、升级嵌套备份、恢复及卸载原有模拟场景仍通过。Bash 语法和 diff 检查通过。

边界：真实 Linux flock + Docker 替身验证，不是对运行实验环境执行升级或卸载；测试输出的删除卷仅为替身命令。此锁是本地主机互斥，不是分布式锁，无法协调不同主机/不共享 /run 的容器操作同一个远程 daemon；文档要求统一从目标主机维护，并更新全部五个脚本。稳定版总目标未完成。

## 2026-09-06：维护目标不受终端 Compose 覆盖变量影响

新增测试先向脚本注入 COMPOSE_PROJECT_NAME/FILE/PROFILES/ENV_FILES/DISABLE_ENV_FILE，Docker 替身断言这些变量不应传入维护命令。修改产品前 maintenance 测试以 21 退出，复现原脚本泄漏覆盖变量的问题。五个 compose_saved 现显式清除上述变量，指定 --project-name devpilot 和 -f 安装文件，同时要求安装器原始顶层 name: devpilot，拒绝重命名/自定义项目。安装原先没有显式 -f，也一并补齐。

最终两组 Linux 无网络、仓库只读挂载的生命周期回归退出 0，保留目录/主机锁、备份失败、恢复校验、安装继续、卸载失败等已有断言；新增改名项目卸载拒绝且 Docker 日志为空。没有对真实容器执行 down 或删除数据卷。

另在运行的 devpilot-stability-lab 中仅提取五个脚本的 compose_saved 函数，使用实际 /opt/devpilot 配置和真实 Docker Compose 执行 config --format json。注入上述无关项目覆盖变量及错误镜像环境值后，逐一断言解析结果仍为 devpilot、mysql-data 卷名为 devpilot_mysql-data、后端镜像未被注入值覆盖，五项全部通过。JSON 仅经 jq 断言，不输出配置和凭据。第一次提取命令因 BusyBox sed 不支持地址逗号前的空格失败，修正只读测试命令后通过，未修改产品以适配测试。

验证范围为真实配置解析及模拟生命周期，未执行新一轮真实升级。没有变更 Docker 连接设置，仍应使用文档支持的本地 daemon。总体验收未完成。

## 2026-09-06：可靠性改动打包与真实隔离升级

只读确认 devpilot-stability-lab 无宿主发布端口、仓库只读挂载、Docker 数据使用独立卷。升级前管理员、加密环境变量、平台配置、Agent 在线/容器上报，以及人工确认原身份/时间、撤销历史、构建 digest 验证通过。原 Dokploy 业务演示环境及其他用户容器不在操作范围内。

从当前源码构建 server/web:stability-reliability-v33（包括告警未知证据修复、前端刷新/竞态/草稿保护、五个生命周期脚本），构建成功。通过 save/load 导入隔离 daemon，仅推送其 loopback Registry，未推送 GHCR。固定升级目标为 `127.0.0.1:15000/devpilot/server@sha256:6acee62829f0b2194a1103e49023d3bcb3e938e5c13e430a5347e17faaf78515` 与 `127.0.0.1:15000/devpilot/web@sha256:5d4e5ee9b7d200039bf5ffa997448c9202fda132f73b2c4fd07ad0f7eb73ea1c`；数据库迁移仍至 V33，没有新增迁移。

新版 upgrade.sh 使用 --migrations-reviewed --yes，在停止后端/网关后调用备份，再切换镜像并等待服务健康，退出 0。备份 `/opt/stability-reliability-upgrade-backups/devpilot-20260906T025107Z-xy25uG.tar.gz` 与 SHA256 保留，事后再次校验 OK。后端停机时备份状态回调连接拒绝属于预期警告，不表示归档失败。

升级后重新验证管理员登录、原服务器、环境 revision/解密公开值/密钥脱敏、平台设置、真实 Agent 在线及至少五个快照；人工确认 fixture 原 digest、确认身份时间、撤销历史和零部署约束仍通过。观测 API 总数分区为 4 = 健康 1 + 异常 0 + 未知 3。docker inspect 确认两服务实际使用上述 digest 且 healthy。通过实际网关下载五个维护脚本，逐个 cmp 当前源码全部相同；没有仅检查源代码而遗漏镜像内工具。

实际运行镜像的基础浏览器验收退出 0，截图 `/var/folders/0k/bg28r22s5m53w39tn9b5krz80000gn/T/devpilot-observation-ui-5qL8yp`。新增显式 DEVPILOT_TEST_RUNNING_IMAGE_FAULTS=true 选项，使原故障、竞态、草稿轮询脚本可针对运行镜像执行，不依赖本地 dist。随后运行镜像完整故障验收也退出 0：首页断网和配置 503 的历史提示/恢复、旧应用响应延迟不覆盖新选择、两轮真实轮询保留未保存仓库/分支全部通过；截图 `/var/folders/0k/bg28r22s5m53w39tn9b5krz80000gn/T/devpilot-observation-ui-Kb0IEv`。无业务写请求，测试浏览器关闭。本轮不等同于私有 GHCR/GitHub/Dokploy 整条新版本验收，也未执行独立环境恢复或主机内核重启，总目标未完成。

## 2026-09-06：外部主链路只读现状核对

使用现有 GitHub 登录仅执行读取：miaomeng1/devpilot-e2e-demo 仍为私有仓库、默认 main，最后 pushed_at 为 2026-09-05T03:11:17Z。工作流文件 .github/workflows/devpilot-e2e-demo.yml 的 Git blob SHA 为 dba9198722c8ee715e20ef7269e56b82baa3d337；workflow_dispatch 只有 build_run_id 输入，没有新版 manual_approval_id、构建状态独立回调或新版人工确认绑定 payload。远端旧文件不能直接作为当前模板验收结果。

仓库级和 production-e2e-demo 环境级 Secrets 名称列表均为空（未读取任何 Secret 明文）。最近六条运行均已 completed/success，最新为 33947173964，创建于 2026-09-05T05:25:40Z，commit 829086357948975bdc2417396b6b347b886279e6；进一步读取最新任务，event 为 workflow_dispatch，仅 production success，quality/image/security 全部 skipped。这是复用构建的旧生产调度，不是本次新的 push 构建证据。这些是本轮可靠性升级之前的历史运行，不证明新版门禁、发布或恢复成功，也没有当前活跃运行需要等待。

下一主链路验收需要接入当前工作流和分离的构建/生产回调凭据，并恢复可达回调通道及 Dokploy 测试连接。临时 Dokploy Key、只开放回调路径的临时隧道和测试仓库 Secrets 的本轮授权尚未收到明确答复；本轮没有创建或更新它们、没有推送代码或触发工作流、没有扩大权限或公开仓库/镜像。生产发布仍必须人工确认，目标继续保持 active。

## 2026-09-06：运行镜像依赖扫描未通过（新增发布阻断项）

前端 npm audit --audit-level=high 退出 0，报告 0 vulnerabilities；npm test 26 项全部通过。Agent go test -race -count=1 ./... 重新执行全部包测试通过，没有仅依赖 Go 测试缓存。

将实际刚构建的 server/web:stability-reliability-v33 分别 docker save 为归档，以 aquasec/trivy:0.65.0（与当前 CI 版本一致）image --input 扫描，不挂载 Docker socket。下载当次漏洞库及 Java 库，scanners=vuln、severity=HIGH,CRITICAL、exit-code=1，**不使用 --ignore-unfixed**。两个扫描均完成并退出 1：server 6 CRITICAL + 25 HIGH，web 38 HIGH。计数包含同一 CVE 的不同包与 amd64/arm64 Agent 二进制重复项，不能表述为 69 个独立或可利用漏洞。未执行本轮源码秘密/IaC 扫描。

原始报告（0600）为 `/tmp/devpilot-runtime-scan.vL3BRU/server.json`（SHA256 acbd67f40a8790b3c81af0b4d77b69f6416ec294e817988875dba1db77af1af0）和 `web.json`（SHA256 93b82556c320020a53fbd99878aba12ef485f252d7085e7a42ac90197c278167）；镜像归档和缓存保留供修复后对比。server 命中 Alpine OpenSSL、Jackson、Micrometer、Netty、Tomcat、Spring Boot/Data/Framework；web 命中 Alpine 系统库及内嵌两架构 Agent 的 Docker 模块。扫描器提示新 Alpine 版本不在自身 EOL 列表，且 web 有一条 CVE 详情缺失警告，报告覆盖不能视为完整安全证明。

核对 [Tomcat 官方公告](https://tomcat.apache.org/security-10.html) 后发现扫描器部分 FixedVersion=10.1.58 不能直接作为可下载版本：该候选版本发布投票未通过，官方要求使用 10.1.59 获取对应修复。[Moby 官方公告](https://github.com/moby/moby/security/advisories/GHSA-x86f-5xw2-fm2r) 涉及 daemon 的 archive 接口；Agent 二进制依赖匹配还需结合实际调用/链接代码分析，不能因只使用客户端就未经检查忽略，也不能把依赖命中直接宣称为 Agent 已可被利用。

后续必须核对可取得的受支持版本，修复依赖与基础镜像、重建并复扫，同时保持回归门禁。当前运行镜像仅作为隔离测试版本保留，不标记发布候选；本轮未调整依赖、未添加忽略项、未关闭门禁、未创建外部凭据。安全修复是新增明确未完成事项，稳定版目标保持 active。

## 2026-09-06：后端依赖与 Alpine 补丁修复、复扫

读取 Maven Central 实际元数据后选择已存在的 Spring Boot 3.5.16（3.5.17 不存在，探测返回 404）；其 BOM 已管理 Jackson 2.21.4、Micrometer 1.15.12、Spring Framework 6.2.19 等修复版本。显式覆盖 Tomcat 10.1.59、Netty 4.1.136.Final，均核对 Central 的 POM 可取得，避免 BOM 中 Tomcat 10.1.55/Netty 4.1.135.Final 仍遗留命中。此为兼容补丁修复步骤，不据此宣称长期支持审查已完成。

server/web Dockerfile 在切换非 root 用户之前执行 apk upgrade --no-cache，使用 --pull 重建 stability-security-v33 镜像成功，未移除依赖、扫描器或新增忽略规则。镜像构建器 package 跳过测试不作为回归证据；单独执行最终 mvn -q test 退出 0，Surefire 汇总 79 项、0 失败/错误/跳过。

采用与上一轮相同 Trivy 0.65.0、缓存漏洞库、vuln 扫描器和 HIGH/CRITICAL 严重级别，仍不使用 --ignore-unfixed。server-patched.tar 的 Alpine 与 Java 两个目标均 0 命中，扫描退出 0；web-patched.tar 的 Alpine 目标 0 命中，但两份内嵌 Agent 二进制仍各有 CVE-2026-41567、CVE-2026-42306 的 github.com/docker/docker v28.3.3+incompatible 依赖命中，共 4 HIGH，退出 1。未将该残留当作可接受例外或删除 Agent 下载产物绕过扫描。

报告保留在 `/tmp/devpilot-runtime-scan.vL3BRU/server-patched.json` 和 `web-patched.json`（0600），对应归档及前一轮报告仍保留。新镜像仅在主机 Docker 构建，尚未推送内部 Registry/GHCR或升级隔离服务；当前运行环境仍为上一轮 reliability 镜像。下一步需处理 Agent Docker 客户端依赖与新版运行回归，整体安全门禁与稳定版目标继续未完成。

## 2026-09-06：Agent 独立 Docker SDK 迁移与镜像复扫

Agent 从 github.com/docker/docker v28.3.3+incompatible 迁移到 github.com/moby/moby/client v0.6.0 与 github.com/moby/moby/api v1.56.0；go.mod/go.sum 不再包含旧 Docker 大模块。适配新的列表结果、Inspect、Stats、控制选项、netip 地址与模板端口类型。使用 client.New(client.FromEnv) 默认 API 协商，保留原有功能和操作约束。

模板镜像拉取使用 SDK Wait 检查流内错误，避免 HTTP 200 中的拉取失败被当作成功。核对该版本 internal/jsonmessages.go：Wait 消费并关闭流，Close 由 sync.OnceValue 保证底层只关闭一次。新增 sdk_requests_test.go 使用真实 SDK 编解码与内存 HTTP transport，验证 START/STOP/RESTART/REMOVE 路径、10 秒停止超时、非强制且保留卷的删除参数、409 错误传播、未知操作不请求 Docker；拉取流内 403 和损坏 JSON 均失败，且没有后续卷/容器创建、启动或清理请求，流仅关闭一次。这些是请求级测试，不宣称真实启停或模板部署已验收。

新增显式 opt-in 的 sdk_integration_test.go，交叉编译 Linux arm64 后，在既有隔离 devpilot-stability-lab 中执行：

```bash
docker exec -e DEVPILOT_TEST_DOCKER_READONLY=true \
  -e DEVPILOT_TEST_LOG_CONTAINER=devpilot-nginx-1 \
  devpilot-stability-lab /tmp/devpilot-docker-sdk.test \
  -test.run '^TestDockerSDKReadOnlyCompatibility$' -test.v
```

实际 Docker 28.5.2 协商成功，容器清单 6 项、读取 nginx 日志 100 行，通过；不输出日志正文，不执行任何启停部署。最终 go test -race -count=1 ./... 全部包通过；默认测试会跳过 opt-in 实测，以上显式运行提供其独立证据。git diff --check 通过。

Web 镜像 --pull 重建成功，包含 amd64/arm64 Agent，标签 devpilot/web:stability-sdk-v33，构建 manifest list SHA256 79a5e6c41f06266875126c2871ec33648c78e184c6f2e8dd85acda015b556a64。归档 web-sdk.tar 使用同一 Trivy 0.65.0、缓存漏洞库、vuln/HIGH,CRITICAL/exit-code=1 参数复扫，不使用 ignore-unfixed，不移除下载产物：Alpine 与两份 Agent 三个目标均 0 命中，退出 0。扫描器仍提示 Alpine 3.23 不在其 EOL 列表，不据此宣称支持周期或整体安全已证明。

报告 `/tmp/devpilot-runtime-scan.vL3BRU/web-sdk.json` 权限 0600，SHA256 fddbedbb3bf62fe823f2159e0fc596b398c4e572579ceafcad4d0da18b9e8e08。旧报告、归档与隔离服务均保留。尚未升级运行中的 Agent/server/web，未推送镜像或源码，也未创建临时外部 Key、隧道或 Secrets。后续须验证新运行镜像升级、实际控制/模板成功路径，再继续其余稳定版验收，目标保持未完成。

## 2026-09-06：真实 Docker 控制及安全补丁运行升级

新增 TestDockerSDKControlCompatibility，默认跳过，必须显式设置 DEVPILOT_TEST_DOCKER_CONTROL=isolated-lab-only 和测试镜像。测试自己创建唯一名称容器，不接受既有容器 ID；network=none、只读根文件系统、无挂载、丢弃全部 capabilities、no-new-privileges。操作仅作用于 Create 返回的 ID，兜底清理还检查测试所有权标签。

交叉编译 Linux arm64 后，在无宿主端口的 devpilot-stability-lab 中以现有 nginx:1.29-alpine 作 shell 测试镜像执行，20.32 秒通过：创建后停止状态、START 后运行、运行中 REMOVE 被拒且仍运行、RESTART 后 StartedAt 改变、STOP 后停止、REMOVE 后 Inspect=NotFound。测试容器已删除，没有数据卷需要删除，没有操作既有容器。最终 go test -race -count=1 ./... 全包通过（两个显式真实验收测试在默认套件中跳过，分别另有真实运行证据）。

升级前再次验证管理员、保存配置、应用变量解密/脱敏及旧 Agent 在线快照。将已扫描的 server:stability-security-v33 与 web:stability-sdk-v33 save/load 至隔离 daemon，仅 push 其 127.0.0.1:15000 Registry。运行目标 server digest=6f7158a39d2a042c06805d96e225cf989ff35ba2fe4f5086be45c931acdecde1，web digest=c7b63999eb003f4586749490a55df327d7683a23360dd810c007586a4a8eafdb，路径前缀分别为 127.0.0.1:15000/devpilot/server 和 web。

升级前读取旧运行 jar 和新镜像 jar 的 BOOT-INF/classes/db/migration/*.sql，连接内容 SHA256 均为 a5558c12e3d79784a460f32272174bf227bf0c8e83a6f7688d49d09b387bedcb。使用 upgrade.sh --migrations-reviewed --yes、两个精确 digest，先备份后升级，退出 0，server/web/nginx 健康。实际启动日志验证 33 个迁移、当前 V33、无需迁移。备份保留 `/opt/stability-sdk-upgrade-backups/devpilot-20260906T032008Z-ESPvhu.tar.gz` 与 sha256；首次从错误工作目录校验报归档相对路径不存在，切换至备份目录后校验 OK。停机期间状态回报连接拒绝仍为已知警告，不表示归档失败。

升级后 test-persistence-api.sh verify（要求 Agent 在线）通过；test-release-approval-api.sh verify 验证原构建 digest、确认身份时间、幂等重放、撤销历史、0 部署；test-observation-api.sh 新证据目录 `/opt/stability-sdk-observation-evidence` 验证实际分类 total=4、healthy=1、unhealthy=0、unknown=3。此确认测试不替代真实生产部署。

从已运行 Web 下载 linux-arm64 Agent 与 SHA256SUMS，核对二进制 SHA256 cff769a8a31d4b55240236c44a5b41bdb1b7cdb77208a31a89239131cf30580f。仅在确认旧 PID 2476 的 /proc/exe 为 /opt/stability-evidence/devpilot-agent 后 TERM，原二进制与原 agent.yaml 保留；以新文件 /opt/stability-evidence/devpilot-agent-sdk、原配置启动，新 PID 33017 的 exe 经核对，旧 PID 已消失。再跑持久化检查通过，原服务器新心跳 2026-09-06T03:21:25.275884，真实快照继续可用。此为隔离容器内进程升级，不宣称 systemd 或主机重启已验收。

未更新独立恢复实验环境、原业务 Dokploy 环境、外部 Secrets 或 Key；未推送 GHCR/源码或触发生产发布。模板真实成功/重试路径、独立恢复新版验证及其余稳定版验收仍待完成。

## 2026-09-06：模板启动超时保留资源及重试归属检查

检查模板成功路径时发现：ContainerStart 返回错误后原逻辑立即 Force Remove，若远端已启动但响应超时，会误删实际启动的容器。本轮改为返回明确的“启动未确认、资源已保留、检查后以相同配置重试”错误，不隐式清理容器/卷。重试通过稳定容器名重新 Inspect，只有归属标签、模板、实例、镜像、loopback 端口、时区匹配且有有效 ID/State 时才复用；已运行不再 Start，停止则尝试启动。不匹配时拒绝并要求核对，不自动覆盖旧配置。

Docker VolumeCreate 可能返回既有同名卷。本轮增加返回卷 Name 与 managed/template/instance 标签检查，归属不符时不创建容器、不挂载或删除原卷，防止误用无关数据。该保护不代表任意拥有 Docker 权限的外部进程无法篡改标签，也不替代完整配置漂移检查。

新增 SDK 请求回归：模拟首次 Start 在远端运行后返回 context.DeadlineExceeded，第二次重试返回原 ID；断言 pull/volume-create/container-create/start 各一次、delete 零次。归属、实例、镜像、端口、时区或 State 不匹配的六种场景全部只读拒绝；同名外来卷场景拒绝且无容器创建或删除请求。最终 go test -race -count=1 ./... 全部包通过，git diff --check 通过。测试使用真实 SDK 和内存 HTTP transport，不冒充实际 Registry 拉取或模板运行实测。

本轮仅修改 Agent 源码及测试，运行中的 stability-sdk-v33/Agent 仍为上一轮构建，尚不包含这些模板修复。下一步须补模板真实成功与重试实测、重新构建扫描并升级 Agent，然后继续独立恢复与外部 CI/CD 验收。未删除任何既有数据或修改外部凭据；整体目标未完成。

## 2026-09-06：模板真实安装、同实例重试与新构建复扫

新增显式 opt-in 的 TestTemplateRealInstallAndRetry，交叉编译 Linux arm64 后在源隔离 daemon 执行。首次直连官方 louislam/uptime-kuma:2.5.0 拉取因隔离环境 network unreachable 失败，测试退出 1；核对该次唯一实例 sdk-kuma-1788665173987380376 没有容器或数据卷。没有为此放开隔离网络或绕过生产拉取逻辑。

宿主 Docker 下载官方 2.5.0，索引 digest a8610b3b4c38077922ba51b036691e06887d7cefd91fe620fd3d6d23d03dc240，通过 save/load 导入隔离 daemon。对比导入前后 RootFS.Layers、Architecture、Config 完全一致，再推送隔离 loopback Registry，固定测试镜像 `127.0.0.1:15000/acceptance/uptime-kuma@sha256:f3f722affb4d5c245849a81eed1bc972e2a3e7942165a52e47c01d69b4e3936d`。只有测试二进制允许该受限 loopback digest 覆盖，生产 Agent 模板镜像不变。这验证离线镜像中转下的模板能力，不是私有 GHCR 拉取或公网直连验收。

第二次真实运行实例 sdk-kuma-1788665327768039086，宿主（隔离外层容器）loopback 39465 → 容器 3001，2.53 秒通过：容器运行、仅绑定 127.0.0.1、命名持久卷挂载 /app/data、HTTP 200、运行中重试返回同一 ID、停止后重试仍返回同一 ID、修改时区拒绝。测试退出时只清理此次返回 ID 且标签归属匹配的测试容器；事后确认容器不存在，数据卷 `devpilot-sdk-kuma-1788665327768039086-data` 及归属标签保留。该卷是测试数据，不是业务备份，可供后续显式清理。没有删除既有应用、镜像或数据卷。

从包含模板修复的源码 --pull 重建 devpilot/web:stability-template-v33，含两个架构 Agent，构建 manifest list c11f7e001903706f577fe2baafe22ab5cfff0419b1caaedc653f7d81e0e39a42。仍使用 Trivy 0.65.0/vuln/HIGH,CRITICAL/exit-code=1，不使用 ignore-unfixed：Alpine 和两份 Agent 均 0 命中，退出 0；Alpine EOL 列表警告仍在。报告 `/tmp/devpilot-runtime-scan.vL3BRU/web-template.json` 权限 0600，SHA256 1d45b46159b05bfa0044a48e71189a259277814f60538300440f25dd34215a7d。此扫描针对 DevPilot Web/Agent，不包含此次第三方 Uptime Kuma 镜像安全验收。

最终 go test -race -count=1 ./... 全包通过（真实验收默认跳过，以上显式运行另计），git diff --check 通过。新 Web/Agent 镜像已构建扫描但尚未升级到平台；实际运行仍为 stability-sdk-v33。独立恢复与外部交付主链路仍待继续，稳定版未完成。

## 2026-09-06：模板修复实际升级及新版独立恢复

源平台升级前持久化检查通过。导入已扫描的 stability-template-v33 Web 并仅推送内部 Registry，digest `88aaed8b7f6d49136626c22c2e05cbb9541bd280a9b05404ce29bac7e4bec857`；后端保持 `6f7158a39d2a042c06805d96e225cf989ff35ba2fe4f5086be45c931acdecde1`，没有数据库迁移变化。upgrade.sh 精确 digest 升级退出 0，网关/后端/Web 健康；前置备份 `/opt/stability-template-upgrade-backups/devpilot-20260906T033029Z-JCoK7f.tar.gz` 校验 OK，停机期间备份回报仍出现已知警告。

从已运行 Web 下载 Agent，SHA256 `45153ee4a7312b340c24c84f34d5779f171f902200e9362f26d1a372b20759f4`，校验后以新文件 devpilot-agent-template 运行。仅在 /proc/33017/exe 符合旧 SDK 文件路径后 TERM；旧二进制保留，新 PID 41033 的 exe 核对通过，仍使用原配置。升级后真实 Agent 在线/至少五条快照、登录、配置解密/脱敏、构建 digest、人工确认/撤销历史、幂等重放及零部署断言通过。

源平台健康时生成新备份 `/opt/stability-template-recovery-backups/devpilot-20260906T033155Z-ZmflRV.tar.gz` 和校验文件，本次无停机回报警告。只传输这两个文件至独立 devpilot-stability-restore-lab 的 0700 目录，校验 OK。独立外层无宿主发布端口，自己的 Docker daemon/数据库/Registry 保留；在其内部导入并推送相同 server/web，得到与源一致的 digest。

先用 upgrade.sh 保留目标原数据备份 `/opt/restore-lab-pre-template-backups/devpilot-20260906T033207Z-R28YFI.tar.gz`（校验 OK），升级目标到同一运行版本并等待健康退出 0。随后只对该独立验收数据库执行 restore.sh --archive 上述源归档 --yes，严格归档/主密钥预检后替换目标数据，服务健康退出 0。源数据库未被覆盖，目标原数据可从前置备份及其对应镜像恢复。

目标原 Agent 的 /proc/1922/exe 显示旧文件已 deleted；本轮先复制该运行二进制为 devpilot-agent-pre-template 留作恢复，再停止该精确 PID。下载并校验相同新版 Agent，以原目标配置启动，PID 26414 的 exe 核对通过。恢复后 test-persistence-api.sh verify（要求 Agent 在线）与 test-release-approval-api.sh verify 均通过：管理员、服务器身份、变量解密与秘密脱敏、初始化配置、实际快照、原构建 digest/确认人时间/撤销记录/幂等重放/零部署均成立。目标 Flyway 实际日志 33 个迁移有效、当前 V33、无需迁移。

本轮未调用业务部署平台、未公开或推送 GHCR、未创建外部凭据；没有执行主机重启。确认历史仍是隔离测试记录，并不证明真实部署/回滚历史恢复；新版外部发布与故障恢复主链路、安全审查、最终交付仍未完成。

## 2026-09-06：回调请求耗时边界与主动核对缺口

读取 CicdService.listRuns/receiveEvent、CicdPipelineRunMapper、CicdConfigurationEntity 及调度入口：列表读取已保存记录；现有定时任务处理部署健康/队列与 Preview，不查询 GitHub Actions 终态。配置中部署平台 Token 不是 GitHub 仓库凭据，接入任务的短期加密请求也不能未经明确设计改作长期查询凭据。由此确认“回调彻底丢失后主动核对”仍是缺失功能，现有 STALE 展示与本轮改动不算实现该要求。

同时发现 workflowTemplates.ts 多个 Preview、发布及 GitLab/Woodpecker 回调 curl 只有 --retry 2，没有连接/单次传输耗时边界。本轮统一生成 --connect-timeout 5、--max-time 30、--retry 2、--retry-max-time 90、--retry-connrefused；保留 HTTP 失败退出，不使用 retry-all-errors 或忽略错误。retry-max-time 限制重试调度窗口，不把它错误描述为严格 90 秒的整个进程截止时间，末次请求还受 max-time 限制。

新增覆盖 GitHub/GitLab/Woodpecker 且开启 Preview 的生成内容测试，逐个检查所有 curl --fail 请求具备边界和有限重试、不吞错。npm test 27 项全部通过，npm run type-check、git diff --check 通过。此为模板内容回归，不是实际 CI 网络故障实测；GitLab/Woodpecker 仍未真实验收。

变更尚未构建部署，也不会自动更新已合并到外部仓库的 Workflow。下一步主动核对必须明确查询凭据生命周期、仓库/commit/run-attempt 绑定、并发回调优先级，并保留“GitHub success 不足以证明镜像 digest 与门禁”的限制；不能直接把远端 success 改写为待发布。外部仓库/Secrets 本轮未改，整体目标继续未完成。

## 2026-09-06：GitHub 主动核对的身份与结果校验基础

依据 [GitHub Workflow Runs 官方 API](https://docs.github.com/en/rest/actions/workflow-runs#get-a-workflow-run-attempt)，新增 GithubRunEvidence，按 build:github-RUN_ID-ATTEMPT 派生指定 attempt 的 API 路径，不使用回调 runUrl 作为请求目标。仅接受 HTTPS github.com 的明确 owner/repo；拒绝用户信息、端口、query/fragment、编码路径与遍历路径，不在错误中回显输入。

结果必须绑定 id、run_attempt、head_sha、head_branch、repository.full_name、head_repository.full_name 和 push event；不同仓库/分支/提交/尝试或字段缺失均拒绝。仅明确 failure/timed_out/startup_failure 映射失败、cancelled 映射取消；success 映射 SUCCESS_AWAITING_BUILD_EVIDENCE 而非可发布，未知结论以及互相矛盾的进行中/终态字段映射 UNKNOWN。此基础限定当前 GitHub push 构建，不声称支持 workflow_dispatch/PR/其他平台。

新增 GithubRunEvidenceTests 三项方法覆盖上述身份和状态矩阵。仓库无 mvnw，首次 wrapper 命令报不存在后使用安装的 Maven；JAVA_HOME 指向本机 OpenJDK，mvn -q -Dtest=GithubRunEvidenceTests test 退出 0，git diff --check 通过。只有 Lombok/Unsafe 弃用警告，未改变测试门禁。

本轮为纯校验组件与单元测试：尚未连接 Controller、查询客户端、持久化凭据或定时调度，不改变数据库状态、不进行外部 GitHub 请求或发布。它是主动核对的基础步骤，不是 running 卡住问题已解决的证据；完整查询/对账功能仍待实现及集成验收。

## 2026-09-06：GitHub 只读 attempt 查询客户端

新增 GithubRunClient，调用前通过 GithubRunEvidence 构建身份，从固定 https://api.github.com 派生 attempt-specific GET，不接受任意查询主机，不跟随 HTTP 重定向。Token 仅作为此次 Authorization header 使用，不持久化，不回显到异常；客户端不含创建、取消、重跑或部署 API。5 秒连接超时、15 秒请求超时与 20 秒异步完成等待，未完成任务取消；响应订阅器在累积超过 1 MiB 前取消，避免无界响应。线程中断保留标记并取消在途任务。

401/403、404、限流、其他 HTTP 异常和网络/超时分别返回固定错误码，不传播上游正文或异常 cause。429 或含限流头的 403 返回限流错误及 60–3600 秒的重试建议，不在客户端内部循环重试；成功响应仍必须经身份/状态校验，success 不产生发布资格。重试建议尚未连接调度器，不宣称实际退避已运行。

新增客户端测试六项，加已有身份校验三项，最终 mvn -q -Dtest=GithubRunClientTests,GithubRunEvidenceTests test 退出 0。覆盖固定 GET 目标/Authorization/超时、301/302 不作成功、错误不泄密、限流边界、损坏/错配 JSON、有效成功/失败映射、中断取消、超大响应订阅取消。测试使用注入的内存 transport，没有真实外部调用；HTTP 重定向禁止由 production HttpClient 配置保证，尚未做真实网络故障注入。git diff --check 通过。

尚未接入凭据保存/撤销、配置界面、Controller、定时状态合并及并发回调协调；运行环境也未部署该代码。整体主动核对和稳定版目标仍未完成，外部账号权限与 Secrets 未变。

## 2026-09-06：显式 GitHub 构建核对 API

新增 POST `/api/cicd/applications/{applicationId}/builds/{buildId}/github-check`，ADMIN/DEVELOPER 可用，VIEWER 不可用。请求为 repositoryToken（write-only、格式验证、toString 脱敏），仅用于此次查询，不存入配置或复用 onboarding 临时凭据。服务先验证构建属于本应用、配置为 GitHub，再调用前述身份限定客户端；返回 state/message/retryAfterSeconds/checkedAt，只读诊断，不更新 Pipeline 状态、不生成发布资格、不调用部署平台。

网络、权限、限流或证据不匹配返回明确中文指引和固定状态码；GitHub success 仍为 SUCCESS_AWAITING_BUILD_EVIDENCE。此 POST 是为了避免 Token 进入 URL，不表示部署写操作；现有审计记录动作并将 repositoryToken 脱敏。响应不包含 Token，结果仅代表 checkedAt 时的远端观察，不宣称与稍后到达的回调形成事务一致性。

Spring Boot/MockMvc/H2 集成测试新增实际构建记录，模拟 GitHub 返回成功，验证未登录 401、VIEWER 403、跨应用构建 404、正常查询返回结果、客户端仅调用一次、本地状态仍 RUNNING、部署数为 0、审计参数没有测试 Token 明文。客户端和身份测试同时执行，最终定向 Maven 测试通过。没有访问真实 GitHub 或修改外部账户。git diff --check 通过。

该 API 尚未部署到运行环境或接入 UI；长期查询凭据配置/撤销、定时核对及安全合并仍待实现。显式只读入口不是完整自动状态修复的替代品，整体目标继续未完成。

## 2026-09-06：发布中心 GitHub 只读核对面板

新增 GithubBuildCheck.vue 并接入 CicdView，只对 ADMIN/DEVELOPER、GitHub 配置且存在规范 build:github-RUN-ATTEMPT 记录显示。用户明确选择构建并输入 Actions read 凭据，点击只读核对；显示结果、时间与退避建议，不提供发布捷径。界面说明不会修改本地状态或重跑；失败显示固定提示，不回显可能含请求凭据的异常。

凭据仅留在组件内存，发送后、关闭、切换构建/应用/账号、卸载组件均清空；不写 localStorage/sessionStorage。关闭/切换时 AbortController 取消并递增请求代次，迟到结果不渲染；返回 buildId 不符也拒绝显示。选择旧应用的结果不能进入新应用组件。长期开启和自动查询能力仍未实现，不以此面板替代。

新增 scripts/test-github-check-ui.cjs：编译实际 Vue SFC，在无外部 HTTP 的 Playwright 浏览器中使用内存接口替身，实际操作展开、选构建、输入、提交、关闭、迟到响应、再次提交；断言发送清空凭据、忙时按钮禁用、仅一次请求、浏览器存储为空、迟到结果不显示、正常结果显示证据不足且没有发布按钮，退出 0。此为真实组件交互而非真实后端/GitHub联调，不宣称全部应用切换/账号切换矩阵或视觉验收已覆盖。

npm test 27 项通过，npm run build 通过；随后增加账号切换与返回 buildId 防护，并实际组件测试通过。源码尚未构建到发布镜像/运行平台，整体目标仍未完成。

## 2026-09-06：自动核对凭据的显式配置基础（V34，未部署）

新增 V34 github_observer_configuration，按应用保存配置版本、绑定仓库/分支、加密凭据、有效期、启用标记。管理员 GET/PUT/DELETE `/api/cicd/applications/{applicationId}/github-observer`；保存需 consent=true 且有效期在未来 30 天内，不自动使用接入临时 Token。凭据 AES-GCM 加密，返回只包含状态/版本/有效期/是否配置，不返回密文或明文；关闭清除密文，保留新版本标识防止旧请求覆盖。保存/关闭在应用行锁下校验 revision，过期清理任务删除已过期密文。

状态区分 DISABLED、EXPIRED、CONFIGURATION_CHANGED 与 SAVED_UNVERIFIED。仓库/分支变化后旧配置不可视为已验证，保存动作本身不查询 GitHub、不证明 Token 权限，也不触发构建或部署。此版本尚未提供自动查询调度，enabled 仅记录显式意愿，不宣称已开始监控。

新增 MockMvc/H2 集成测试：拒绝未同意保存、保存后数据库非明文且可解密、响应不泄露 Token、旧 revision 再保存冲突、关闭后密文 NULL、审计无测试 Token、GitHub 客户端零调用。首次测试编译缺少 delete 静态导入，改用完整方法名后定向测试退出 0，H2 已应用 V34。过期清理、角色矩阵及 MySQL V34 升级/恢复仍需进一步实测，不以单项用例覆盖全部生命周期。

当前源与恢复平台仍运行 V33，未执行 V34 数据库升级；无真实凭据写入、外部权限变更或仓库修改。下一步接入调度与结果安全合并、管理员 UI 和完整生命周期回归；整体目标保持未完成。

## 2026-09-06：自动 GitHub 核对调度与条件合并（V35，未部署）

新增 V35 保存配置级 next_check_at/check_lease 与运行级 github_checked_at/github_observation。GithubRunReconciler 每 30 秒选最多五个已启用、未过期、仓库分支匹配的配置；通过数据库条件更新取得 120 秒查询占用，每个配置一次查询一个待定 GitHub 构建，按观测时间轮转。外部读取不持有事务，成功后至少间隔 60 秒；限流使用客户端建议退避，其他配置异常退避 300 秒。没有候选运行时保持占用至下次窗口，不忙轮询。

结果在短事务内锁应用行，重新检查配置 revision/lease/有效期/仓库分支，再核对原记录仍 RUNNING 且 updatedAt、commit、externalRunId 未变。撤销、轮换、过期或新回调使结果不再合并。FAILED/CANCELLED 更新构建终态及 BUILD_FAILED，并调用既有构建失败通知路径；其余结果仅保存观测，不调用部署。success 仍不足以放行，列表显示缺少原始构建证据；新鲜 ACTIVE 可解除旧回调造成的过期提示，其他非终态观测展示未知。

定向 MockMvc/H2 集成测试已通过：保存显式查询配置后模拟 success 保持 RUNNING 且列表 STALE；占用期间第二次调用没有外部请求；模拟 failure 更新失败及最新观测；模拟查询途中撤销配置后迟到 cancelled 不改记录；全过程部署数为零。仅测试数据库占用的顺序排他与撤销插入点，真实多实例并发、回调竞态、限流调度与 MySQL V35 仍待实测。

末尾进一步将其他非 ACTIVE 查询结果统一标为未知，已启动全后端回归验证，尚不能把未完成的全量运行记作通过。当前实际平台仍 V33，管理员配置 UI、V34/V35 真实升级恢复、真实 GitHub 核对及安全审查仍未完成；未保存真实凭据或修改外部权限。

## 2026-09-06：核对生命周期与回调竞争回归

续接上一轮全后端 Maven 运行（会话 88426），确认实际退出 0，而非因长时间无输出重启。日志仍有既有告警通知测试的后台 ConnectException 警告，不代表外部通知验收成功。

随后扩展自动核对集成用例：在客户端模拟的远端读取期间，通过实际 MockMvc 构建回调入口提交有效签名 CANCELLED；查询随后返回 FAILED，合并后仍保持回调 CANCELLED，证明该插入点的新终态不被旧查询覆盖。另新增凭据生命周期用例，先创建真实 RUNNING 构建，再更改仓库配置分支：状态 CONFIGURATION_CHANGED、客户端零调用；恢复分支并把有效期设为过去：状态 EXPIRED、客户端仍零调用；执行 clearExpired 后密文 NULL、版本变化。

首次新增两项定向测试一项失败：重新设置 Mockito when(...).thenAnswer 时执行了旧带副作用回调，使后续撤销场景提前变为 CANCELLED。改用 doAnswer(...).when(...) 避免设置阶段执行旧行为，没有移除断言或改业务状态逻辑；重跑两项定向测试退出 0，git diff --check 通过。测试证明的是受控回调/撤销插入点，不等同真实多实例并发或 MySQL 锁竞争验收。

本轮没有修改运行平台、外部凭据或迁移实际数据库。后续仍需管理员配置 UI、调度状态展示、限流与并发覆盖、V34/V35 MySQL 升级恢复和新版外部 CI/CD 主链路，整体目标未完成。

## 2026-09-06：管理员自动核对配置面板

新增 GithubObserverSettings.vue 接入发布中心，仅 ADMIN/GitHub 可见。明确说明读取待定构建、不重跑或发布；提供 1/7/30 天保存期限、Actions read 凭据、未预选的显式保存同意、刷新状态与关闭清除入口。状态显示已保存未验证/过期/配置改变/关闭，不把保存当授权有效。说明期限仍受 Token 自身有效期限制，以及关闭仅删除平台副本、GitHub Token 需另行撤销、备份可能含加密副本。

保存时绑定已读取 revision；发送后清空凭据与同意，不写浏览器存储。关闭/切换应用或账号清空组件状态并使迟到结果失效。保存或删除结果不确定时清除可操作状态，要求先 GET 读回，不自动重发写操作。该行为不撤销已经到达后端的请求，面板也没有错误承诺可取消保存。

新增 scripts/test-github-observer-ui.cjs，编译真实 SFC 在 Playwright 中以纯内存 API 替身验收：不勾选不能保存、保存时 consent=true、发送后凭据/勾选清空、local/session storage 为空、关闭清除成功、模拟远端保存后响应丢失时禁止直接重提、刷新读回已保存配置且写请求次数没有增加；退出 0。类型检查通过。此为组件行为实测，尚未做真实管理员 API 浏览器联调、完整角色/账号切换矩阵或视觉验收。

本轮源码未部署至平台，无真实 Token 写入。当前配置状态仍以 SAVED_UNVERIFIED 表示保存，后续需展示最近调度验证结果和时间；并发/限流、V34/V35 MySQL 升级恢复、真实主链路仍未完成。

## 2026-09-06：双线程核对租约与并行签名回调

新增 concurrentGithubObserversClaimOnceAndPreserveCallbackDuringRemoteRead：两个线程使用同一应用；第一个停在受控客户端读取处，第二个核对必须在第一个释放前返回。随后另一线程通过实际 MockMvc 签名构建入口提交 CANCELLED，并要求在远端读取释放前完成；第一个查询最后返回 FAILED。断言客户端总计一次调用、最终状态仍 CANCELLED、旧查询观察字段没有写入、部署记录为零。所有等待均有超时，finally 释放信号并终止线程池。

定向 Maven 测试退出 0。该测试使用实际服务和 H2 数据库、模拟 GitHub 客户端，补充了真实线程重叠的租约和回调竞争证据，但不是多进程、真实 MySQL 或 GitHub 网络验收。没有修改运行平台或外部资源。

此前前端构建会话句柄已不存在，不能凭 dist 文件断言该次成功；重新执行 npm run build，明确退出 0（vue-tsc 与 Vite 构建通过）。git diff --check 通过。完整稳定版仍未完成。

## 2026-09-06：V33 → V35 隔离 MySQL 实际升级

确认 devpilot-stability-lab 和 devpilot-stability-restore-lab 正在运行且无宿主机发布端口。审阅 V34 新建凭据表、V35 增加可空调度/观察字段，没有删除已有业务字段。构建 stability-observer-v35 Server/Web 成功，使用与现有 CI 相同的 Trivy 0.65.0 对导出的运行镜像扫描（HIGH/CRITICAL，exit-code 1，未忽略 unfixed）：两项退出 0，JSON 漏洞数组计数均为 0。Server 包含 JAR 扫描，Web 包含两个 Agent 二进制扫描。扫描器仍有 Alpine EOL 列表未知警告，零结果不等于完整安全审查通过。

私有报告位于 /tmp/devpilot-runtime-scan.vL3BRU，SHA-256：

- server-observer-v35.json：79fa1c07007fa5bfa66f4e25551ef7517e2314044ef0537e15b17bb610305e2e。
- web-observer-v35.json：8ad4d79dc0f43c2bbe510284d3272f5143275fdd8ecde833665f714529c14dcd。

导入源隔离 daemon，并仅推送该环境内部的 loopback Registry。实际升级命令：

```bash
docker exec devpilot-stability-lab bash /workspace/scripts/upgrade.sh \
  --install-dir /opt/devpilot --backup-dir /opt/stability-observer-upgrade-backups \
  --server-image 127.0.0.1:15000/devpilot/server@sha256:52fa72d1f83a034d2633feee2076e1056f204978498694e9652f5090de4cf616 \
  --web-image 127.0.0.1:15000/devpilot/web@sha256:c04574f1ecfd6d56fe30b3151c0d08fd9a744e7f688004cb12458ce9b6eeb724 \
  --migrations-reviewed --yes
```

退出 0，升级前备份 devpilot-20260906T041035Z-cFUs1q.tar.gz 和 sidecar 保存在上述 0700 目录。停止网关后的备份状态回报仍发出连接警告；归档成功，不代表状态回报成功。实际 MySQL 查询显示 35/34/33 success=1，新增表和调度字段存在。升级后 test-persistence-api.sh verify（REQUIRE_AGENT=true）及 test-release-approval-api.sh verify 均退出 0：管理员登录、原服务器身份、公开变量解密/敏感变量脱敏、初始化配置 revision、Agent ONLINE 和至少 5 个真实容器快照、原构建 digest、审批身份时间、幂等重放、撤销记录和零部署均通过。

本轮只升级源验收环境，独立恢复环境保留 V33；未创建真实 GitHub Token、未推送 GHCR、未修改原 Dokploy 业务。尚需升级前备份恢复及 V35 新表数据恢复、真实 MySQL 调度竞争、真实 GitHub 状态和新版发布链路验收。

## 2026-09-06：V35 升级前 V33 数据库的独立恢复

先确认独立恢复平台的 Server/Web 为匹配 V33 的既有 digest 且 healthy，备份其当前数据至 /opt/restore-lab-pre-observer-recovery-backups/devpilot-20260906T041215Z-YRiN6g.tar.gz（sidecar 校验通过）。仅向该隔离环境 /opt/observer-v33-recovery-input 复制源平台升级前 devpilot-20260906T041035Z-cFUs1q.tar.gz 及 sidecar，没有复制 MySQL 卷或覆盖源平台。

实际执行 restore.sh --install-dir /opt/devpilot --archive /opt/observer-v33-recovery-input/devpilot-20260906T041035Z-cFUs1q.tar.gz --yes，退出 0；五个服务健康。MySQL 最新 Flyway 版本 33、success=1。test-persistence-api.sh verify（REQUIRE_AGENT=true）与 test-release-approval-api.sh verify 均退出 0，验证管理员、加密变量、初始化配置、Agent 实际上报、原构建与审批身份/时间、撤销历史和零部署。此次替换的是独立测试数据库；替换前数据库可由上述目标备份恢复。

这验证了“升级前数据库备份 + 匹配旧镜像”的恢复，不是将 V35 数据库直接降级，也不证明业务数据卷或数据库迁移可自动回滚。源平台保持 V35，原 Dokploy 业务未变更。

另新增 scripts/test-observer-persistence-api.sh，通过实际源 V35 HTTP API 创建独立 observer-persistence 测试应用及合成无效查询凭据（有效期一天），没有创建待查询构建或部署。seed、verify 和 bash -n 通过；实际 MySQL 查询确认该样本一条 enabled=1、v1: 密文记录。API 读回的 revision/expiry 与原保存响应一致，凭据仅返回 configured，构建/部署均零。样本用于后续 V35 新表恢复；尚未执行该样本恢复，也不证明 Token 解密或真实 GitHub 授权。私有样本在源环境 /opt/stability-v35-observer-evidence（0700），不得提交 Git。

## 2026-09-06：V35 新增配置的独立数据库恢复

源 observer 样本 verify 通过后，实际备份至 /opt/stability-v35-recovery-backups/devpilot-20260906T041432Z-tMKIgc.tar.gz。复制归档与 sidecar 至独立恢复环境 /opt/observer-v35-recovery-input，复制私有 observer 验收样本至该环境 /opt/stability-v35-observer-evidence，均未复制数据库卷。归档 sidecar 校验通过。

向独立 daemon 导入已扫描的 V35 镜像，在其内部 loopback Registry 获取与源环境相同的 Server digest 52fa72d1f83a034d2633feee2076e1056f204978498694e9652f5090de4cf616、Web digest c04574f1ecfd6d56fe30b3151c0d08fd9a744e7f688004cb12458ce9b6eeb724。实际 upgrade.sh --migrations-reviewed --yes 退出 0，并先生成目标旧 V33 备份 /opt/restore-lab-v35-upgrade-backups/devpilot-20260906T041506Z-00OIq9.tar.gz，sidecar 校验通过。停网关后备份状态回报的既有警告仍存在。

随后实际执行 restore.sh --install-dir /opt/devpilot --archive /opt/observer-v35-recovery-input/devpilot-20260906T041432Z-tMKIgc.tar.gz --yes，退出 0、五服务 healthy。该操作替换的是独立测试数据库，旧数据保留于上述 V33 备份；源环境及原 Dokploy 业务未改动。MySQL 最新 Flyway 为 35、success=1，observer 样本 enabled=1 且 v1: 密文记录数为 1。

三组 API 验证均退出 0：test-persistence-api.sh verify（REQUIRE_AGENT=true）、test-release-approval-api.sh verify、test-observer-persistence-api.sh verify。覆盖登录、原服务器与 Agent、加密环境变量读取、初始化配置、原构建/审批/撤销记录、observer revision/expiry 完整保留和凭据接口脱敏。observer 无构建、无部署。该样本仅证明配置/密文保存与恢复，没有证明其合成 Token 可以解密后访问 GitHub，亦无真实构建调度、通知或生产发布证据。

源环境只读查询观察到调度已写入 check_lease 和 next_check_at；这仅说明无待定构建时的数据库调度领取路径运行，不能据此标记 GitHub 对账已验收。两套平台现均为 V35，整体稳定版目标继续保留。

## 2026-09-06：自动核对故障指引与观察时间边界

新增固定文案 GithubObservationGuidance，自动核对信息不再直接展示原始状态码。凭据/权限、限流、运行不可访问、证据不匹配、网络超时分别给出下一步，并明确查询失败不等于构建失败；成功仍要求测试、扫描和原始 digest 证据及人工确认。未知字符串不反射到页面。显示核对时间时注明 UTC。

修复 ACTIVE 观察超过两分钟仍可能被当作当前运行证据的问题：过期或未来时间均显示 STALE、要求重新核对，不改变底层 RUNNING、不发出 GitHub 请求或创建部署；新鲜 ACTIVE 仍明确不是应用在线证明。新增 3 项固定文案单元测试和时间边界集成用例，并扩展成功缺证据 API 文案断言。定向 Maven 执行 5 项测试全部通过（3 项单元、2 项 H2/MockMvc 集成）。随后仅将时间异常提示补充为“已过期或时间异常”，没有改变判断逻辑。git diff --check 通过。

本轮为源码修改，尚未重新构建或部署运行镜像、未做新文案浏览器验证。两套平台仍运行上一轮 V35 镜像；不能把本轮回归当成实际 GitHub 调度或发布主链路验收。

## 2026-09-06：升级备份报告延后提交

backup.sh 新增 --defer-report NEW_FILE 与 --report-only FILE；前者在可信私有目录保留不含密钥的报告，后者仅签名上报，不执行备份/服务变更。upgrade.sh 先停止写入并生成备份/待上报记录，健康检查通过后再上报；上报失败保留健康平台和报告文件，打印确切重试命令。恢复服务前升级失败时报告仍留在备份目录。更新部署文档，明确报告被接受不等于备份恢复已验证。

隔离 Linux 中 test-install-upgrade.sh（模拟 Docker/HTTP）通过，新增断言检查上报发生在启动后、上报失败不触发二次停止、独立重报不执行 mysqldump/up/stop、符号链接报告和覆盖既有报告被拒绝。test-maintenance.sh 通过，原备份/恢复保护保持有效。

实际源 V35 环境执行 backup.sh --defer-report /opt/stability-deferred-report-evidence/report.json，归档 /opt/stability-deferred-report-backups/devpilot-20260906T042212Z-yC7TDA.tar.gz 生成成功。随后对同一报告执行两次 --report-only，均退出 0；实际 MySQL 查询 maintenance_backup_report 该文件记录数为 1，验证真实签名受理与重复提交去重。本次未停止源服务，未执行真实故障升级；真实“升级停服→健康→报告提交”新顺序仍待端到端验收。脚本来自挂载源码，Web 下载包中的脚本尚未重新构建更新。

## 2026-09-06：全量回归与延后上报实际升级

全后端 mvn -q test 会话 87750 明确退出 0；仍有测试后台告警 Webhook 的 ConnectException 警告，不代表真实通知成功。前端 27 项测试、类型检查/生产构建，以及两个 GitHub 核对 Vue 组件浏览器替身测试通过。构建 Server stability-guidance-v35 和 Web stability-reporting-v35，Trivy 0.65.0 扫描 HIGH/CRITICAL、exit-code 1，两项退出 0且报告漏洞数组为零，未忽略 unfixed。Alpine EOL 列表未知警告仍存在。

私有 /tmp/devpilot-runtime-scan.vL3BRU 报告 SHA-256：server-guidance-v35.json 为 bcfa733edfcae295fe2e2c625b927894f1cbda179ca54cc931bb7de7eb2099b8；web-reporting-v35.json 为 a23e2d2ecb3abdfd2143b2280c257bee178637e1d1e00d3f01644723610a3f47。中断后续接镜像导入/内部推送会话 32473，确认完成，没有重复启动或视超时为失败。

仅源隔离环境实际执行 upgrade.sh，目标 Server 127.0.0.1:15000/devpilot/server@sha256:98dd294b510717f13495076eee07f0a9e04c382188603ab99bc591c7bfe3dd70，Web 127.0.0.1:15000/devpilot/web@sha256:60fcdabe0b662b65546e22d643e7c46670a1db9d4a75e9d7a6a9b4745bc6b85c，--migrations-reviewed --yes。升级前归档 /opt/stability-reporting-upgrade-backups/devpilot-20260906T042612Z-R3EHLf.tar.gz，sidecar 校验 OK；待上报文件 .upgrade-report.KN61qI/report.json 保留。

实际顺序为停服务→备份并暂存报告→新服务 healthy→签名报告 accepted，命令退出 0，没有原先停机期间报告连接失败警告。MySQL 该归档 maintenance_backup_report 行数为 1，Flyway 仍为 35/success=1。三组持久化/审批/observer API verify 全部退出 0，Agent ONLINE、至少五容器、加密配置及原审批撤销信息正常；无业务部署。

从源平台 HTTP /tools/backup.sh 和 /tools/upgrade.sh 下载内容计算 SHA-256，与工作树一致，分别 ede77056fe075efebe5aa243616e0c6b107c1bbf929b32ea987b318ca0b630b2、959a992c6cea0f9d009e1857c64429dde5a9475931273c969acb498d89066f64。独立恢复环境未跟随升级，保持 observer-v35；未推送 GHCR 或变更原 Dokploy 业务。完整 GitHub 调度、真实生产发布/故障恢复、当前版本全量安全审查及文档交付仍待完成。

## 2026-09-06：真实测试仓库接续前只读检查

gh repo view、gh run list、仓库与 production-e2e-demo 环境 secret list，以及 workflow contents GET 均成功。miaomeng1/devpilot-e2e-demo 仍为私有，默认 main；最近三次运行 33947173964、33946935997、33941522924 均为历史 workflow_dispatch/completed/success，commit 829086357948975bdc2417396b6b347b886279e6。两处 Secrets 名称列表均为空。没有触发新运行。

远端 workflow 仍只有 build_run_id 发布输入，没有新版 manual_approval_id 和独立构建状态上报任务；源码扫描还使用 --ignore-unfixed，回调 curl 也没有新版超时限制。历史 dispatch 成功不能证明当前 push 门禁或新版人工确认链路通过。接续验收需通过 PR 更新测试 workflow（不削弱现有门禁），恢复限定测试用途的回调入口和 Secrets，并为测试 Dokploy 接入取得新的临时 Key 授权。本轮未创建 Key、隧道、Secrets、PR 或部署，没有扩大权限或更改仓库可见性。

## 2026-09-06：核对凭据轮换回归

外部 Key/回调/Secrets/PR 授权尚未收到，继续本地验证。新增 observerRotationRejectsOldResultAndUsesNewCredentialAfterBackoff，实际 Spring/H2 服务处理中在模拟 GitHub 读取期间轮换凭据：原查询返回 FAILED 被丢弃，本地保持 RUNNING、观察为空；新版密文解密为新测试 Token，revision 变化。等待期内再次检查不调用客户端；将测试数据库的截止时间推进到过去后，新查询实际使用新 Token，记录 ACTIVE，部署仍零。定向 Maven 测试退出 0。

没有修改后端调度策略：轮换不清除已有查询间隔/限流等待，避免重复保存绕过退避。配置面板新增说明，不承诺保存后立即验证。此为本地插入点回归，不是真实 Token/GitHub 网络或真实时钟等待测试。新面板文案尚未部署。

## 2026-09-06：GitHub 凭据接口定向安全审查

按 llm-sast-scanner v1.3.2 的输入到危险操作追踪和二次核验方法，加载 IDOR、认证、SSRF、SQL 注入、XSS、弱加密、信息泄露及信任边界参考。范围限定新增 GitHub 配置/显式核对接口及所调用的加密、认证、审计和组件；不是全仓库 34 类完整审查。

已核对的保护证据：GithubObserverConfigurationController.java:12 限 ADMIN，GithubRunCheckController.java:25 限 ADMIN/DEVELOPER，SecurityConfig.java:25 开启方法鉴权；显式核对服务校验构建所属应用。GithubRunClient.java:44 固定 api.github.com，:31 禁止重定向，仓库和运行身份经 GithubRunEvidence 校验；不存在由该参数改写目标 authority 的已确认路径。配置 SQL 使用绑定参数。SensitiveSettingCipher.java:38 使用 AES/GCM，随机 IV 来自 SecureRandom，派生摘要使用 SHA-256；未发现该路径使用弱算法。审计正文 isSecret 对 Token 字段递归脱敏，损坏 JSON 使用固定占位；异常解析返回固定错误。组件使用 Vue 文本插值，不使用原始 HTML 渲染。

候选鉴权/SSRF/正文泄密风险经复核有上述明确保护，不列为已确认漏洞。未据此认定所有日志、查询字符串、全站 XSS、JWT 跨系统复用或部署拓扑均安全；这些属于后续范围。管理员作为设计内的可信角色，不能把管理员允许的全平台配置访问当成跨租户漏洞。

新增 observerConfigurationRejectsNonAdminsAndMalformedSecretsWithoutDisclosure，通过真实 Spring Security/MockMvc/H2 验证：匿名 GET 为 401；实际创建 VIEWER/DEVELOPER 并登录，配置 GET/PUT/DELETE 均 403；管理员非法字符 Token、对象类型 Token、损坏 JSON、未同意保存均 400。响应不含合成密钥标记，审计 request_params/error_message 无标记，配置表零新增，GitHub 客户端零调用。定向 Maven 退出 0。此次技能推动补充了权限与错误路径回归，没有声明全项目安全验收完成或修改外部授权。

## 2026-09-06：核对调度等待时间展示

配置状态 API 增加只读 nextCheckAt，取现有 V35 next_check_at，无数据库迁移；只对当前有效且仓库绑定未变化的配置返回。面板显示“最早再次核对时间”或“等待调度检查”，明确不是执行时间保证、不是授权验证或上线证据，凭据到期停止，刷新配置后更新。

定向 H2 集成验证初始空值、设置截止时间后的准确读回、仓库变更后隐藏等待时间，且原过期/变更禁止查询用例继续通过。真实 Vue 组件浏览器替身验证时间文案和不保证执行提示，前端类型检查及 git diff --check 通过。持久化验证器仍比较 revision/state/expiry/凭据存在性，但排除会随后台调度变化的 nextCheckAt，不把调度字段变化误判为配置丢失。新 API/面板尚未部署，也未进行真实 GitHub 核对。

## 2026-09-06：当前工作树完整本地回归

注意：下述完整回归发生在后续制品 attempt 绑定和 V36 结果来源变更之前；后续变更的定向测试记录见本文件末尾。

本轮在当前工作树执行后端 `mvn test -q`，使用主机 Java 25，退出 0；Surefire 文本报告汇总 99 tests、0 failures、0 errors、0 skipped，包含最近凭据轮换、权限与 nextCheckAt 集成用例。此为本地 Spring/H2 测试，不替代真实 MySQL 并发或 GitHub 网络验收。

前端 `npm test` 27 项通过，`npm run build` 类型检查及 Vite 生产构建通过。两个实际 Vue 组件的浏览器替身脚本 test-github-check-ui.cjs、test-github-observer-ui.cjs 均通过，不访问外部 HTTP。Agent `go test -race ./...` 退出 0，部分包使用缓存；未据此声称真实 Docker SDK 集成场景全部重新执行。

安装脚本测试误在 macOS 执行时因缺少 /run 退出，未进入实际安装；改在 devpilot-stability-lab 执行又被 5 GiB 空间门槛拦截。只读 df 确认 /tmp 为约 3.9 GiB 内存文件系统，而 /opt 所在磁盘可用约 794 GiB。使用 `docker exec -e TMPDIR=/opt devpilot-stability-lab bash /workspace/scripts/test-install-upgrade.sh` 后退出 0；没有降低空间检查或删除数据。test-maintenance.sh 在同一隔离环境退出 0。两组脚本使用模拟 Docker/请求及独立临时数据，不是再次执行真实平台升级、数据删除或恢复。

补充用户操作指南 docs/github-observation.md 并加入 README 文档入口，说明启用与关闭、凭据轮换、调度等待、异常处理及回调丢失限制。此次没有创建临时 Key、隧道、Secrets、PR 或业务部署，也未将最新 nextCheckAt API/UI 部署到运行环境；真实完整交付链路仍待验收。

## 2026-09-06：制品身份和回调恢复前置条件

GitHub 模板的 release.json 新增 runAttempt、repository、branch、event；上传和下载制品名称绑定 attempt，发布校验所有新增字段。jq 实际执行的负面测试覆盖旧 attempt、缺失/类型错误 attempt、错误仓库/分支/事件/提交/Run ID/镜像与非 digest。GitHub/GitLab/Woodpecker 生成模板移除 --ignore-unfixed，测试保留 HIGH/CRITICAL 与 exit-code 1。前端 29 项测试、类型检查通过；远端模板没有更新，不代表真实 Runner 验收。

检查恢复路径发现：只读核对把 workflow 失败设为终态后，原回调处理会忽略后续有效签名最终证据，无法处理仅回调发送失败的 workflow。V36 新增 build_result_source，新的核对终态标为 GITHUB_OBSERVATION，签名回调标为 CALLBACK。只有前者可以被有效签名最终回调补正；提交身份与门禁照常校验，RUNNING 不能使终态回退，签名最终结果仍不可覆盖。旧数据默认 CALLBACK，迁移不猜测历史来源。

三项定向 Spring/H2 集成测试通过：新增补正路径、原核对竞态路径、原构建密钥隔离及终态幂等路径。新增用例实际经观察器产生 FAILED，再验证错误签名拒绝、换提交拒绝、迟到 RUNNING 不改变状态、成功补报只进入 AWAITING_APPROVAL、签名最终结果不可被覆盖，部署及人工确认记录均零。36 次迁移在 H2 成功；没有验证真实 MySQL V36 升级。完整制品恢复任务、真实网络验收、最新代码部署仍未完成。

## 2026-09-06：构建恢复模板与补报脚本执行验证

GitHub 模板增加 workflow_dispatch operation=release/recover_build（默认 release）；恢复只使用 build-status 环境、actions:read 和构建回调密钥，不 checkout、不重建、不持有生产回调密钥。人工确认 ID 在表单可空以支持恢复，但 production 仅在 operation=release 执行，仍要求合法确认 ID 并由后端校验。两种操作都核对指定 attempt 的分页 jobs，quality/security/image 各须唯一、Run/commit 匹配、completed/success；不能仅看整个 workflow 的 conclusion。恢复校验原 attempt 制品后补报原构建 ID，并要求响应是同 Run、commit、image 的 SUCCEEDED/AWAITING_APPROVAL。

参考 GitHub 官方指定 attempt jobs API 文档，接口读取权限为 Actions read。本地 jq 执行测试覆盖分页、缺失和重复任务、三项任务各自失败/跳过/取消/未完成/错提交/错 Run。实际生成的 Bash run 块通过 bash -n；没有声称完整 YAML/actionlint 已通过。

本轮新增实际执行最终补报 Bash 块的测试，使用独立临时 JSON 制品、真实 jq/openssl 和本地 curl 函数替身，未访问网络。验证请求使用原 commit、digest、build Run/attempt，签名与 Node HMAC 独立计算一致，不含人工确认字段；错误制品或缺密钥在请求前失败，HTTP 错误及响应中不同身份/状态/镜像均失败。发现并补强响应校验的 commit/image 绑定，避免同 ID 的旧成功结果被误认为当前制品恢复成功。前端 31 项测试、类型检查、git diff --check 通过，测试创建的临时文件已由测试清理。

当前实现为用户显式发起一次 recover_build 后自动核对和补报，不是平台后台自动恢复所有历史构建。尚未提交远端 workflow、运行真实 Actions、验证 V36 MySQL 升级或部署最新前后端；稳定版验收仍未完成。

## 2026-09-06：V36 镜像构建与完整后端回归

将最近恢复逻辑与 UI 操作指引纳入本地镜像 devpilot/server:stability-recovery-v36、devpilot/web:stability-recovery-v36，两次 Docker build 均退出 0。Server 使用容器 Java 21 编译，Web 完成类型检查及生产构建；两个 Agent 下载二进制构建层复用缓存。主机 Java 25 另行执行完整 mvn test -q，退出 0，Surefire 汇总 100 tests、0 failures、0 errors、0 skipped；H2 完成 V36 迁移。没有把镜像构建中的跳过测试当作测试通过证据。

Host manifest list：server 52d037f12ab52f4c443d12732688f94b9e74cb8700888eaf5104998a23761d5d，web 67cd9ec3d9119a215da810c46775faafdc8d21f801674c20ff5039cd0c25a4c5。将两者 docker save 至私有 /tmp/devpilot-runtime-scan.vL3BRU 下的 server-recovery-v36.tar、web-recovery-v36.tar；Trivy 0.65.0 image --input、scanners=vuln、HIGH/CRITICAL、exit-code=1 扫描均退出 0，未忽略 unfixed，报告分别为同名前缀 .json。Alpine EOL 列表未知警告仍在；这不是全仓库秘密、IaC 或完整安全审计。

本轮没有推送 GHCR、导入隔离 Registry 或替换运行中的服务；源与恢复实验环境仍为 V35。下一步必须先备份，再执行真实 MySQL V36 升级及登录、凭据、Agent、审批持久性验证，不可仅依据上述构建结果宣称升级通过。

## 2026-09-06：源隔离平台实际升级 V36

确认 devpilot-stability-lab 外层无主机端口绑定，内部六个容器在运行，只向该隔离 daemon 导入已扫描归档并推送 loopback Registry。固定目标 Server digest edc7a11fe8d93237d511749dd82da6e4320b84471ee06678172cfa71dae15f7d，Web digest 5116ac9ca7a59ec208e4896554960f4ecf5485e8cafc715683ef98d1582ec94c（仓库均 127.0.0.1:15000/devpilot/对应组件）。这不是 GHCR 推送。

审阅 V36 只增加来源字段、旧数据默认 CALLBACK 后，执行 upgrade.sh --install-dir /opt/devpilot --backup-dir /opt/stability-recovery-v36-upgrade-backups，传入上述不可变镜像及 --migrations-reviewed --yes。升级会话 71037 退出 0。实际停服务后生成 devpilot-20260906T045718Z-6PsxQn.tar.gz，sidecar 校验 OK；报告 .upgrade-report.oFhvLF/report.json 保留，新服务 healthy 后报告 accepted。

MySQL 读取 Flyway version=36、success=1，现有三条流水线来源 CALLBACK。五个 Compose 服务均 healthy，registry 仍运行。test-persistence-api.sh verify（要求 Agent）、test-release-approval-api.sh verify、test-observer-persistence-api.sh verify 均退出 0：管理员登录、应用配置、加密公开值解密及秘密存在性、初始化配置、Agent 在线与至少五容器快照、构建 digest、原人工确认身份/时间与撤销历史、observer 配置保留。没有触发业务部署；observer 验证仍不证明真实 GitHub 授权或解密。

独立恢复环境与原 Dokploy 业务环境没有变更。V36 真实 MySQL 升级及上述功能验证已完成，但尚需 V36 独立恢复、真实 GitHub 恢复/发布/故障回滚链路及完整验收，不能据此标记稳定版完成。

## 2026-09-06：V36 独立数据库恢复验证

源 devpilot-stability-lab 创建 /opt/stability-v36-recovery-backups/devpilot-20260906T045916Z-FiL4wH.tar.gz；将其与 sidecar 通过容器间 tar 流复制至 devpilot-stability-restore-lab 的 /opt/recovery-v36-input（0700），校验 OK。未输出归档内密钥。恢复目标外层无主机端口绑定，使用独立 Docker daemon 与数据卷。

向恢复环境 loopback Registry 导入并推送已扫描的 recovery-v36 镜像，Server/Web digest 与源平台相同。先执行目标 upgrade.sh，会话 45731 退出 0，保存其原 V35 数据备份 /opt/restore-lab-v36-upgrade-backups/devpilot-20260906T045947Z-BTDTN8.tar.gz，sidecar 校验 OK，延后报告成功；原数据可通过保留备份恢复。

随后 restore.sh --archive /opt/recovery-v36-input/devpilot-20260906T045916Z-FiL4wH.tar.gz --install-dir /opt/devpilot --yes，会话 89182 退出 0。实际替换的是该隔离实验数据库及恢复所需配置；五个 Compose 服务均 healthy。MySQL Flyway V36 success=1，三条历史流水线来源 CALLBACK 与源平台一致。

三组 API verify 均退出 0：管理员登录、应用配置及加密公开值解密、秘密存在性、初始化配置、Agent 在线/至少五个容器快照、原构建 digest、人工确认身份/时间、撤销历史和 observer 配置保留。验证仍不证明真实 GitHub 授权、外部部署连接或业务数据卷恢复；V36 的 GITHUB_OBSERVATION 新数据来源恢复尚未由此三条旧数据覆盖。没有创建业务部署，没有更改原 Dokploy 业务环境、GitHub Secrets 或仓库。完整稳定版目标仍未完成。

## 2026-09-06：完整生成 workflow 静态校验

新增 scripts/test-github-workflow-lint.mjs，固定 actionlint v1.7.7（已核对上游发布页面），使用 stdin 校验实际 generateWorkflow 输出。NODE/JAVA/GO/DOCKER × Preview 开关共八种组合全部通过 YAML 和 Actions 语义检查，未调用外部 shellcheck/pyflakes，不替代 Runner、Secrets、制品下载与部署验收。可以通过 DEVPILOT_ACTIONLINT 指向预装可执行文件，默认 Go run 下载固定版本到 Go 缓存，不修改项目 Go 模块。

加入 make workflow-verify 和项目 quality CI 步骤；同时移除项目自身 .github/workflows/cicd.yml 安全扫描遗留 --ignore-unfixed，保留 HIGH/CRITICAL、exit-code=1，与生成模板一致。更新后的项目 workflow 也通过 actionlint，git diff --check 通过。本轮没有执行全仓库新版安全扫描，不宣称移除跳过选项后真实 CI 必然通过；修改尚未推送，未创建 PR 或触发 workflow。

## 2026-09-06：源码安全门禁实际扫描未通过

执行与当前 CI 相同 Trivy 0.65.0 fs、scanners=vuln,secret,misconfig、HIGH/CRITICAL、exit-code=1，无 ignore-unfixed。工作树只读挂载，报告保存在私有 /tmp/devpilot-runtime-scan.vL3BRU/source-v36.json；扫描会话 84448 完成并退出 1，不是超时。仅输出裁剪后的目标、规则、严重级别摘要，未展示 Secrets 的匹配内容。

报告唯一 HIGH 命中为 scripts/fixtures/install-lab.Dockerfile 的 DS002（默认 root 用户），无所选严重级别的依赖漏洞或密钥命中。扫描器提示默认不包含开发/测试依赖，因此不能宣称所有依赖均无问题；此结论也不覆盖代码逻辑型 SAST。

实际检查该文件为 docker:28-dind 的隔离安装测试 fixture，增加 bash/curl/openssl/iproute2/flock/jq 等工具，需要启动实验 daemon 和验证 root 安装流程。它不是 production server/web 镜像，原设计要求不挂载宿主 Docker socket。但测试用途不自动豁免门禁；本轮没有新增忽略规则、降低严重级别或移除扫描目标。该配置需进一步改进或明确处理，当前完整源码安全门禁保持未通过，不可用此前生产镜像扫描通过替代。

## 2026-09-06：rootless 测试镜像替代方案实测不兼容

核对 Docker 官方 Rootless Docker in Docker 文档：该镜像默认 UID 1000，但仍要求 --privileged，未提供 systemd/cgroup 时有资源限制差异。拉取 docker:28-dind-rootless（manifest digest 7c3e797187e43738220462658f4586572cbd3bf009f728b21e34d9c5c06ce431），新建仅用于探测的 devpilot-rootless-probe，--network none、无主机端口或绑定挂载、2 CPU/1 GiB，未使用已有实验或业务 daemon。

实际确认默认 UID=1000，daemon=28.5.2、SecurityOptions 含 rootless；root exec 通过 /run/user/1000/docker.sock 可访问 daemon。但创建与安装目录约束相同的 root-owned 0700 /opt/root-only-fixture（仅空 marker），使用导入的 alpine:3.22 启动内层容器只读 bind 后，test -f marker 失败；进一步 ls 明确 Permission denied，挂载目录在内层映射为 nobody。不能仅修改 Dockerfile 的 USER 就宣称等价安装验收，否则 root 配置权限会使运行不正确。

启动默认入口时 daemon 在隔离网络内监听 2375 无 TLS并提示无 cgroup；外层 network=none/PortBindings={}，未向主机或外网发布。后续若采用 rootless 应仅启用 Unix socket并重新设计权限/验收边界，不能沿用此次探测默认入口作为正式方案。本轮未修改 fixture 或放宽安装权限。探测结束 docker stop 精确停止该 --rm 容器，容器及其临时文件/匿名卷自动清理，未删除保留的验收备份；下载镜像留在 Docker 缓存。DS002 门禁仍未解决，不将此负面验证表述为 rootless 不可用于所有场景。

## 2026-09-06：开发依赖扫描覆盖补充

Trivy fs 在相同源码只读挂载上增加 --include-dev-deps，保留 vuln,secret,misconfig/HIGH,CRITICAL/exit-code=1；会话 75470 退出 1，私有报告 source-v36-with-dev.json 仍只有测试 fixture DS002，所选严重级别依赖与密钥零命中。npm audit --audit-level=high --json 独立退出 0，报告所有严重级别共零漏洞，包含开发依赖。

Trivy 本次新增警告：部分 POM 依赖版本不能确定，其子依赖不会发现。因此此扫描不能证明全部 Java 测试依赖已覆盖，必须进一步用 Maven 实际解析依赖补足，不能抹去警告或靠零命中标记安全通过。

项目 CI 和三种生成模板均增加 --include-dev-deps，并补回归断言；31 项前端测试、类型检查通过。改动尚未部署或推送，当前运行 Web 镜像仍是先前 recovery-v36。完整源码门禁仍被 DS002 阻断，Java 解析覆盖问题也未关闭。

## 2026-09-06：Maven 解析清单补充扫描

使用项目 Maven 实际执行固定 org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom，includeTestScope=true、outputFormat=json，输出到私有 /tmp/devpilot-runtime-scan.vL3BRU/maven-v36/bom.json。插件成功解析 135 个组件，缺失版本列表为空，确认包含 junit-jupiter 5.12.2、mockito-core 5.17.0、spring-security-test 6.5.11。此步骤不修改 pom.xml，也未执行 Maven deploy。

Trivy 0.65.0 sbom 对该清单执行 vuln/HIGH,CRITICAL/exit-code=1，退出 0，scan.json 漏洞数组为零。保留扫描器关于第三方 SBOM 精度和不支持部分 SHA-384/SHA3 哈希的警告，不能据此称无所有安全风险。该补充路径通过 Maven 明确解析版本并包含测试依赖，弥补前一轮直接 POM 解析的已知缺口，不宣称 Trivy 静态 POM 解析器本身已修复。

项目 security CI 加入 Java 21、生成解析清单、校验版本非空及测试组件存在、扫描 SBOM，再执行原源码/秘密/IaC 扫描。更新后 actionlint 与 git diff --check 通过。未推送或触发真实 CI，DS002 测试镜像告警仍未解决，整体安全验收未完成。

## 2026-09-06：授权后恢复 V36 外部验收环境与 workflow PR

用户确认允许创建临时 Dokploy Key、恢复回调隧道和测试 Secrets、提交新版 workflow PR；不包含代替用户合并 PR 或确认生产发布。

- 私有测试仓库 PR：https://github.com/miaomeng1/devpilot-e2e-demo/pull/2 。分支 codex/stability-build-recovery-v36，提交 e03a8c6ffe0eb2ae13985243e8e58e77eb32d35e。只更新测试仓库 workflow，未提交主 DevPilot 工作区的其他改动。保留 production-e2e-demo 环境，新增 build-status-e2e-demo；回调地址通过 Secrets 配置，模板 fallback 使用不可解析的 .invalid 域名。
- actionlint v1.7.7 通过（不含 shellcheck/pyflakes）。真实 PR run 34015789031 已 completed/success：quality 9s、security 19s、image 34s，配置 linux/amd64、linux/arm64。build_started/build_finished/recover_build/production 按 PR 事件跳过；不能把 PR 成功说成新 push 回调、人工发布或恢复补报验收成功。
- GitHub API 确认仓库 private、GHCR devpilot-e2e-demo 包 private；未变更可见性、未合并、未发起 workflow_dispatch。测试 Dockerfile 仍保留故障注入 DEVPILOT_E2E_FAIL_HEALTH=1，PR 已明确警告不能当作正常健康版本发布。
- 在 devpilot-stability-lab 当前 V36 后端新建独立 e2e-demo 应用 2096481170921701378、服务器 2096481170611322882，未复用旧宿主 8080 后端。关联既有 Dokploy 应用 bDuvB-6sWcbxgW341Ni-d；新 Key 名 devpilot-stability-20260906，一天有效、每分钟 1000 请求。只读平台验证通过。密钥本地权限 0600；创建时 UI 快照的遮罩未覆盖 text 节点，密钥曾进入本任务工具输出，未写入源码/PR/公开日志，后续应撤销该临时 Key，不将任务导出作为无密钥证据分享。
- 为此次外部联调创建专用 Docker 网络 devpilot-v36-e2e，仅连接 source lab 与 Dokploy lab。此前 source lab 没有网络 endpoint，现在恢复测试所需连通性；未发布 source lab 的宿主端口、未连接 restore lab、未挂载宿主 Docker socket。此状态变化需在测试清理时恢复。
- Dokploy lab 启动当前 Agent，私有目录 /opt/devpilot-v36-e2e；连接 http://devpilot-stability-lab:18081。Agent ONLINE，应用健康地址由该 Agent 探测 http://127.0.0.1:18088/health，返回 HTTP 200/v2。保留旧 Agent 和 v2 服务，不执行部署或回滚。
- 专用本地 callback gateway 仅监听 127.0.0.1:18186，精确允许 e2e-demo 的 POST 发布/构建回调，限制请求大小、请求时长、频率；通过 docker exec 请求 V36 API。临时 Cloudflare 隧道已恢复，公网 health=200、管理接口=404、无签名=401、格式合法但错误签名=401。尚未收到真实 push 的正确签名回调。
- 两个 GitHub 环境分别恢复 URL/secret 共四个 Secrets，不在仓库级保存。生产签名和派生构建签名不同。私有运行状态与辅助脚本位于 /tmp/devpilot-workflow-v36.OlTV67，原 Key 私有文件位于 /tmp/devpilot-e2e-rcJew0/dokploy-stability.key。此目录不是公开交付物。
- 06:15 UTC 预检查 ready=true、score=89、blockerCount=0、warningCount=3；平台/Agent/健康通过，正确签名回调、关联容器、候选构建仍待验证。新应用 runs=0、deployments=0，无人工确认记录由本轮生成。

下一步等待仓库所有者合并 PR，形成新 push 的 attempt-bound artifact 和签名构建记录。之后继续补报恢复、人工确认、digest 部署及失败回滚验收；不能自动批准生产。临时 Key/隧道/Secrets/Agent/专用网络需在测试结束后精确清理，保留原 v2 服务、原 Key、两个隔离实验的数据与备份。整体稳定版目标仍未完成，主项目源码 DS002 门禁仍开放。

## 2026-09-06：授权轮换 Key、合并 PR 与新 push 验证

用户明确允许轮换上述临时 Key 并合并 PR #2。已在 Dokploy 撤销 devpilot-stability-20260906，页面确认删除成功；随后使用旧 Key 请求同一个 application.one 接口返回 HTTP 401，旧凭据不可恢复且不能继续访问，未删除任何应用或其他 Key。替换 Key 名 devpilot-stability-0906-r2，一天有效、每分钟 1000 请求，同组织；生成后的完整页面输出被抑制，密钥仅保存于私有 /tmp/devpilot-workflow-v36.OlTV67/dokploy-r2.key（0600）。新 Key 同接口 HTTP 200 且资源 ID 匹配，V36 部署凭据已更新，后端平台验证通过；未轮换构建或生产回调签名，因此已有 GitHub Secrets 保持有效。

按指定 head e03a8c6ffe0eb2ae13985243e8e58e77eb32d35e 合并 PR #2，merge commit 916c3af6d47c9b2e510359e83ea08f4df0df7a85，mergedAt 2026-09-06T06:50:04Z。新 push run 34017532658 启动。后端确实接收 RUNNING 签名回调，创建记录 2096491132800548866、externalRunId build:github-34017532658-1，commit 与 GitHub 一致，初始 deployStatus BUILDING；当时部署数 0。该证据证明真实构建开始回调通过，不等于构建完成或生产发布成功。

该 push run 随后 completed/success：quality 7s、security 18s、image 43s、build_started 3s、build_finished 4s；production skipped。下载 devpilot-release-e2e-demo-1 制品得到 digest sha256:1da5382db94490d8322d78701b2a0dfe8bafc101ac453b7fd91196719108f621，runId=34017532658、attempt=1、repository=miaomeng1/devpilot-e2e-demo、branch=main、event=push、commit 与合并提交一致。后端原记录变为 SUCCEEDED/AWAITING_APPROVAL，测试与扫描 PASSED，镜像 URI 与制品精确匹配。私有制品保存在上述目录 push-evidence/release.json。GitHub 的部分 Actions 仍提示 Node 20 弃用/强制 Node 24 警告；任务成功不等于兼容性警告已处理。

随后仅调度 operation=recover_build、build_run_id=34017532658，无人工确认 ID。真实 workflow_dispatch run 34017614472 completed/success。再次断言后端只有同 ID 的原记录，run/attempt/commit/digest 一致，仍 AWAITING_APPROVAL、审批数 0、部署数 0；证据保存 verified-build.json。此测试证明真实 GitHub 制品校验与补报幂等性，不证明原回调丢失、观察器判失败后再恢复等所有异常场景；未执行 release 或生产确认。

再次确认 GitHub 仓库和 GHCR 包均 private，原 Dokploy v2 /health 返回 {status:ok,version:v2}。当前候选镜像仍是故障注入样例，不能未经提示作为健康版本发布。

发现待修复的验收提示差异：真实 build 回调已成功，但 readiness 的 CALLBACK 检查仍 WARN“尚未收到 CI 有效签名回调”。源码 CicdService 仅在 !buildOnly 时写 callback_verified_at，而 readiness 使用笼统文案，混淆构建回调和生产发布回调。应区分两类证明，不可为了提示变绿伪造生产回调。本轮未改数据库标记、未将此 UI 提示缺陷报告为已修复。整体稳定版及异常恢复/生产发布验收仍未完成。

## 2026-09-06：准备健康 v4（尚未批准部署）

用户确认继续恢复健康版本。测试仓库分支 codex/healthy-v4、提交 1b96feea1cb11e5774a6d3a213829179e8797dd8 将版本改为 v4，Dockerfile 默认 DEVPILOT_E2E_FAIL_HEALTH=0；保留显式运行时故障测试。新增版本/镜像默认值回归检查，并恢复测试前的环境变量状态，npm test 三项通过。README 区分 recover_build 与人工确认发布，提示检查部署环境覆盖。

本地构建 devpilot-e2e-demo:healthy-v4-local 成功。首次立即探测发生服务启动竞争（ECONNREFUSED），不能算通过；随后新建同名 --rm 隔离探测容器，network=none、128 MiB/1 CPU、无宿主端口，使用至多 10 秒的有界就绪等待，实际 /health 返回 HTTP 200/{status:ok,version:v4}。探测结束精确 docker stop devpilot-v4-health-probe，--rm 自动移除容器；未停止原 v2，未删除镜像缓存。

只读 Dokploy application.one 检查未发现开启故障的环境覆盖，不输出其他环境变量。将 V36 e2e-demo 应用关联到 Agent 实际观测的既有 v2 容器（snapshot 2096481843411238915、container 7fe85f15bdfeb62ee4b09decbc141950fcf2269d65d7637dcc4d5d3bfb381186），精确校验 Swarm 服务名前缀和原 v2 digest 后写入，currentVersion=v2；这是运行态关联，不是伪造发布历史。

PR #3：https://github.com/miaomeng1/devpilot-e2e-demo/pull/3 。真实 PR run 34017804256 quality 9s/security 18s/image 32s 通过，随后按确认的健康版本流程合并，merge commit 01607503a5f9b01f3f2e9e860dd6d71abc9c4d32，2026-09-06T06:57:35Z。新 push run 34017861081 已启动；本轮未执行 production 或创建人工审批。

发布前限制：CicdDeploymentService.startRelease 的 previousImageUri 来自 selectLatestHealthy，新应用部署历史为零，关联当前 v2 容器不会自动生成历史健康版本。首次 v4 发布不能声称已具备 DevPilot 自动回滚到 v2 的历史基础；应在版本确认时明确失败恢复授权，保留原 v2 digest，不插入伪造的成功发布记录。

健康 v4 push run 34017861081 最终 completed/success：quality 8s、security 21s、image 45s、build_started 5s、build_finished 4s，production skipped。下载制品并逐项断言与 V36 后端一致：buildId=2096493026977595394、externalRunId=build:github-34017861081-1、commit=01607503a5f9b01f3f2e9e860dd6d71abc9c4d32、image=ghcr.io/miaomeng1/devpilot-e2e-demo@sha256:dec60c4be27500525c439ff11f1686edd589963997cb9fe654cbd6824bd6f9aa；状态 SUCCEEDED/AWAITING_APPROVAL，测试和扫描 PASSED，审批 0、部署 0。只读获取版本绑定的人工确认上下文，保存在私有 v4-evidence/approval-context.json，并未提交批准请求。最后现有服务 /health 仍返回 v2/ok。下一步需用户明确确认将此 v4 发布到本地 Dokploy 测试应用，并明确首次发布失败时恢复已知 v2 的授权；此处停在真实部署之前。

## 2026-09-06：用户确认后的 v4 发布失败，已保留并恢复 v2 目标

用户明确回复“确认发布 v4，失败时允许恢复 v2”。重新验证平台凭据、故障覆盖和版本确认上下文，fingerprint 与此前一致、预检查无阻断后，由已登录测试管理员 stability-admin 记录用户授权（不是用户本人通过 UI 点击）。确认 ID e973c764-3384-4446-860c-2abbdd5c6f5b，绑定上述 v4 build/commit/digest，expiresAt 2026-09-07T07:03:51。请求 UUID 与响应保存在私有 v4-evidence/approval-request.json、approval.json，便于幂等核对。

执行 operation=release、build_run_id=34017861081、上述 manual_approval_id，真实发布 workflow run 34018142088 completed/success。它证明来源验证和签名发布请求成功，不证明 Dokploy 部署成功。V36 创建部署 2096494640018833410，先 TRIGGERED，随后 UNHEALTHY；Provider 日志明确拉取 v4 GHCR manifest 返回 unauthorized，Pulling image failed。previousImageUri=null，因此未宣称平台自动恢复历史版本。

实际 Swarm 服务从未切换到 v4，仍运行 v2 digest 364ff547b1acd6edb107ade413e622e908eba7c4f2c7aa0d2d6c366db6d52895，/health 返回 HTTP 200、{status:ok,version:v2}。根据用户失败恢复授权，仅将 Dokploy application.update 的 dockerImage 从本轮 v4 精确恢复为该 v2 digest，并再次 application.one 读回确认；没有重启健康 v2，也没有伪造 DevPilot 的 ROLLED_BACK/HEALTHY 状态。原失败发布历史保留。证据在 v4-evidence/deployments-current.json 与 v2-restoration.json。

只读检查应用配置的凭据存在性（不输出值）：username/password 均为空，registryId 与 registry 均未配置，只有 registryUrl 有值。这是当前私有镜像拉取失败的直接配置缺口；临时 Dokploy API Key 只授权管理 Dokploy，不授予 GHCR 拉取权限。尚未将本机 GitHub 登录凭据复制到 Dokploy，也未改变包可见性、关闭门禁或重试发布。下一步需为该测试应用提供有效的 GHCR 拉取凭据，再进行受控重试。当前 v4 未发布成功，完整部署链路与稳定版目标仍未完成。

用户继续后补充只读检查：Dokploy registry.all 返回空数组，当前 Dokploy 容器 /root/.docker/config.json 存在但 ghcr.io auth 与 credential helper 均未配置。检查只输出存在性，不读取展示令牌。依据 GitHub 官方 GHCR 文档，准备使用 PAT classic 的 read:packages 权限；未擅自将本机更高权限的 GitHub 登录凭据转存到 Dokploy。已在本地 Dokploy Settings → Registry 打开 Add Registry 表单，预填名称 GHCR DevPilot read-only、用户名/前缀 miaomeng1、host ghcr.io，密码留空，未创建或提交。等待用户创建短期只读 PAT 并在该 Password 字段保存；不要把 Token 发到对话。此时未重试发布，仍保留 v2。

## 2026-09-06：Registry 已配置，但令牌类型不适用于 GHCR

用户回复“已配置”后发现 Registry IwPZxSKqRI-BWJRYarF87，名称 GHCR DevPilot read-only、host ghcr.io、用户名 miaomeng1。通过 application.update 关联该 registryId，再按本项目现有 saveDockerProvider 配置接口将用户提供的凭据设置到精确测试应用的 Docker 拉取配置，始终保留 v2 dockerImage；没有更改其他应用、环境变量或镜像可见性。registry.one 不返回 password；registry.all 返回该字段，处理过程中仅输出存在性与令牌类型，未输出或保存令牌明文到工作区。

实际预检：在 Dokploy 容器内创建专用临时 Docker config，使用用户凭据 login 成功，但指定 v4 digest 拉取返回 manifest unknown；finally 执行该临时 config 的 docker logout，保留原 Docker 登录配置。GitHub 包 versions API 确认该 digest 和 sha-01607503a5f9b01f3f2e9e860dd6d71abc9c4d32 标签存在，因此不误判为镜像被删。使用用户凭据进行只读认证诊断：GitHub /user HTTP 200、账号匹配、x-oauth-scopes=null；令牌前缀分类为 fine-grained PAT（不输出完整值）。GHCR token endpoint HTTP 200，但 manifest 按 digest 请求返回 404/MANIFEST_UNKNOWN、按正确标签请求返回 403/DENIED。login 成功不等于拥有该私有包拉取权限。

GitHub 官方 Container registry 文档明确要求 PAT classic，read:packages 用于下载镜像；该用户创建的 Fine-grained PAT 不满足此认证路径。当前阻塞需要用户换成 Classic PAT 的 read:packages，而不是进一步扩大已有 Fine-grained 权限或公开包。本轮未创建新的发布审批、未 dispatch 重试，v2 /health 仍 ok。已有应用级拉取凭据是本轮用户提供的 Fine-grained PAT，用户更新 Registry 后还需同步应用级配置再做真实拉取预检。证据辅助脚本位于私有 /tmp/devpilot-workflow-v36.OlTV67，测试目标不变。

## 2026-09-06：Classic 只读令牌验证与 v4 受控重试

用户回复“已更换”后，令牌分类确认为 Classic PAT，GitHub /user HTTP 200、账号匹配，x-oauth-scopes 精确为 read:packages。GHCR 按指定 digest 与构建标签读取 manifest 均 HTTP 200，返回 digest 与原批准的 v4 一致，包含 linux/amd64 和 linux/arm64。将用户更新后的 Registry 凭据同步到该应用 saveDockerProvider；凭据未写入工作区或工具输出。专用临时 Docker config 登录、在实际 Dokploy daemon 上拉取 v4 digest 成功，finally logout 清理临时登录。该拉取预检不发布服务。

沿用用户对同一 v4、失败恢复 v2 的授权与“继续重试”意图，核对原批准上下文未变，为新的 release Run 创建独立确认记录 b41c91a3-97b6-41dd-a9f4-070b6bd2876d；不复用已绑定失败 Run 的旧确认。第一次重试 workflow 34019607138 成功送达，但部署 2096502812922167298 UNHEALTHY：登录/拉取均成功，之前由助手增加的 registryId 关联触发 Dokploy 二次推送，产生 refusing to create a tag with a digest reference。原 v2 仍运行。

已承认并修正该关联错误：精确恢复目标 v2、清空助手添加的 application.registryId，保留 username/password/registryUrl 的私有镜像拉取配置；未删除用户创建的全局 Registry。更新证据 v2-restoration-pull-only.json。未改为 tag、未扩大令牌权限、未公开镜像。随后为同一原始 build/commit/digest 创建新的独立确认 668efbfa-2c90-45de-a982-b7ce050fcacb，workflow 34019657199 completed/success，部署 2096503104338214913 从 TRIGGERED 进入 VERIFYING。实际服务 /health 已返回 ok/v4，Agent 已自动从旧 v2 容器绑定到新容器 3b18d1920a1153dc1ca6bfd737a0db33959888ef34f94ba488800de07a0fda3f，镜像 URI 与批准的 v4 digest 一致；此刻仍等待新鲜健康探测，不提前记为最终成功。

最终验证通过：部署 2096503104338214913 状态 HEALTHY，应用 healthStatus=HEALTHY、currentVersion=sha256:dec60c4be275；Agent healthCheckedAt=2026-09-06T07:38:46.599432，运行容器及 deployment 的 imageUri 均精确等于批准的 v4 digest。确认记录 consumedByRunId=github-34019657199-1，断言全部通过，私有 live-success.json 保存应用、部署和审批证据。未修改历史失败记录，也未插入虚假的 v2 成功历史。

本轮完成真实 GitHub push → 测试/扫描/双架构私有 GHCR → 签名构建记录 → 用户确认 → GitHub 发布 workflow → Dokploy 拉取/部署 → Agent 自动重绑定/新鲜健康验证的正向链路。仍是本地隔离 Dokploy 验收环境；未据此宣布完整稳定版目标完成。正常发布后的故障自动回滚、全部 13 项验收、主项目 DS002、安全与 UI 剩余问题仍待处理。保留用户创建的只读 Registry 与成功 v4 服务；临时 Dokploy Key、回调隧道、测试 Secrets、专用网络和 Agent 的精确清理/长期配置仍需后续收尾，不能把临时环境当作已交付的云端长期部署。

## 2026-09-06：将 GHCR 类型错误拦截固化到产品接入流程

新增 RegistryCredentialPolicy 本地兼容性检查：仅针对 ghcr.io 镜像仓库拒绝已知 Fine-grained PAT 前缀，提示 Token (classic)/read:packages、独立于仓库管理授权，并明确仍须验证具体镜像读取权限。未知凭据格式不被宣称有效；不强制把其他 Registry 密码当 GitHub Token。错误不包含令牌值。

接入任务 start 在持久化任务前应用检查；credentials 更新不论是否 EXPIRED 都检查合并后的凭据，且先于远端凭据更新。Provider configure/refreshRegistryCredentials 也做防御性检查，避免旁路调用把已知不兼容凭据发到部署平台。前端新建/恢复两处 Registry 表单与 automatic-onboarding 文档补充类型、最小权限、保存/登录与实际拉取的区别，并解释已构建 digest 不应启用 Dokploy 二次打标签推送。

验证：新增 RegistryCredentialPolicyTests 3 项通过（GHCR host 大小写/空白、错误不回显秘密、其他 Registry 不误拦截、两种 Provider 写入入口在远端任何读写前拒绝）；RepositoryOnboardingClientTests 11 项与 CicdIntegrationTests 38 项均零失败/错误/跳过；前端 31 项测试及类型检查通过，git diff --check 通过。集成回归证明既有流程未被这些测试发现回归，不代表新策略已做所有 HTTP/浏览器路径的真实验收。此轮为源代码和文档更新，尚未构建/部署新版平台镜像或推送主仓库，运行 V36 不包含新策略。

只读复核原 v4 部署仍 HEALTHY，Agent 新鲜健康时间 2026-09-06T08:22:46.526777，实际镜像与批准 digest 一致；未触发发布、回滚或修改业务容器。下一步继续消除手工补脚本依赖、完善真实拉取预检和发布失败指引，并补齐异常恢复及其他稳定版验收；整体目标保持未完成。

## 2026-09-06：发布失败阶段与恢复指引

发布记录增加只读失败指引，按日志线索区分镜像拉取、digest 二次打标签、端口占用、平台完成超时、运行镜像未匹配和健康验证失败；未知原因保持不确定，不从日志拼接命令、链接或令牌。仅失败及恢复相关记录展示，成功、进行中和未知状态不因历史错误文本变成失败。日志分类是线索，不是对远端原因的权威诊断。

下一步文案强调应用级拉取凭据与精确 digest 验证、端口归属、Agent 新鲜数据及平台任务核对。Registry 保存/login 成功不代表具体镜像可拉取；平台观察超时不等于远端已停止，不增加隐式重试或部署动作。回滚进行中、历史恢复成功与回滚失败分别提示，不承诺旧版本仍运行。补齐前端部署类型的 ROLLED_BACK/ROLLBACK_FAILED，发布摘要补充 UNHEALTHY/ROLLBACK_TRIGGERED 指引。

新增四项测试覆盖非失败状态、实际 Registry 日志线索、阶段和操作区分、回滚状态及不回显日志秘密/HTML。前端共 35 项通过、类型检查通过；失败面板及样式的生产构建通过。尚未进行该面板的浏览器视觉验收，也未构建/部署新的平台 Docker 镜像，不把源码验证当作运行环境更新。未触发任何发布、回滚或业务容器变更，完整稳定版及 13 项验收仍未完成。

## 2026-09-06：区分构建和发布回调验证证据（V37 源码）

修复真实联调发现的笼统 CALLBACK 文案：保留 CALLBACK 作为发布签名回调检查，新增 BUILD_CALLBACK 构建签名回调检查。各自展示验证 UTC 时间，明确签名送达不等于构建/部署成功，也不保证当前网络可达；构建回调不能验证发布端点，GitHub 观察器不能替代回调证据。首次发布前发布回调未验证仍为 WARN，不要求跳过人工确认。

V37 增加可空 build_callback_verified_at，不从历史流水线回填；经过签名与请求校验的构建事件仅更新此字段，发布事件仍仅更新 callback_verified_at，并保留当前密文匹配条件。轮换生产密钥同时清除两类验证时间，普通保存不清除。检查接口仍只读，不发送探测回调或创建部署。

最终 CicdIntegrationTests 39 项全部通过，零失败、错误、跳过（本机 Java 25/H2）。新增 HTTP 集成场景验证错误密钥拒绝、构建验证与发布验证分离、普通保存保留证据、轮换清除两类证据、旧密钥不可重新验证以及全程零部署；补充正确签名但非法正文不留下构建验证时间的断言。首次运行两个旧断言因检查项由 11 增至 12、未验证告警增加而失败，已改为按检查 code 核对健康/容量/回调状态，并重跑通过，没有移除未验证告警。git diff --check 通过。

本轮仅改源码和文档。V37 尚未在隔离 MySQL 环境做升级/备份恢复验收，运行中的 V36 平台未升级，真实 v4 业务未做发布或回滚操作；浏览器视觉验收和完整稳定版剩余事项继续保留。

## 2026-09-06：V37 实际镜像构建与独立恢复环境升级

构建 devpilot/server:stability-evidence-v37（容器 Java 21 编译）及 devpilot/web:stability-evidence-v37（类型检查、生产构建、双架构 Agent 下载文件）成功。主机 Java 25 完整 mvn test 退出 0，Surefire 汇总 104 项，零失败/错误/跳过；不把 Dockerfile 跳过测试的打包步骤当作测试通过。

两个实际镜像导出后，用 aquasec/trivy:0.65.0 image --input、scanners=vuln、severity=HIGH,CRITICAL、exit-code=1 扫描，均退出 0、漏洞数组为零，没有 ignore-unfixed。扫描覆盖 Server Alpine/JAR、Web Alpine 与两个 Agent Go 二进制，报告保存在私有 /tmp/devpilot-v37-validation.yB1qRg/server-scan.json、web-scan.json，镜像归档也保留。扫描器提示 Alpine 3.23/3.24 不在其 EOL 列表、部分严重性取其他供应商，并提示新扫描器版本；零发现不等于所有风险消失。此为运行镜像漏洞扫描，不替代全仓库 secrets/IaC/开发依赖门禁，既有 DS002 仍未解决。

仅向 devpilot-stability-restore-lab 内部 loopback Registry 导入并推送新镜像，未推送 GHCR 或主仓库。精确 Server digest 30e582dc0fbe5571d2b775e8f659530b01ada6f612d28672b277b043b9b9344b，Web digest 765dc21d518a98160130b09b16ed43206587f29b18fac2f6d00edd2d0e2ca55d。导入镜像 ID 分别与主机已扫描镜像 config ID 一致。

升级前该独立环境 MySQL 为 V36/success=1、部署表零行，三组持久化 API verify 通过。审阅 V37 仅新增可空构建验证时间且无回填后，执行 upgrade.sh --install-dir /opt/devpilot --backup-dir /opt/restore-lab-v37-upgrade-backups --migrations-reviewed --yes，使用上述两个内部 Registry 精确 digest。升级退出 0，先停止写入并保留原 V36 归档 devpilot-20260906T083917Z-3mECvS.tar.gz 及 sidecar，sha256sum 校验 OK；延后报告 .upgrade-report.CmDd53/report.json 在新服务 healthy 后 accepted。原数据没有被恢复覆盖，保留升级前恢复材料。

实际 MySQL 查询 V37/success=1；新签名回放前三个配置的 build_callback_verified_at 全为 NULL、部署仍零行，证明没有从历史流水线补造验证。升级后 test-persistence-api.sh verify（REQUIRE_AGENT=true）、test-release-approval-api.sh verify、test-observer-persistence-api.sh verify 均退出 0，覆盖管理员、加密配置读取与脱敏、Agent ONLINE/至少五容器快照、原审批身份/时间及撤销记录。审批验证以原签名幂等回放构建后，真实 HTTP readiness 返回 BUILD_CALLBACK=PASS（UTC 2026-09-06T08:40:04）、CALLBACK=WARN；构建回调没有升级为发布证明，测试应用零部署。

五个 Compose 服务均 healthy，内部 Registry 保留。此次只升级独立恢复实验环境；源 devpilot-stability-lab 保持 V36，Dokploy v4 服务、GitHub Secrets 和回调隧道未修改。V37 新字段的独立备份恢复、浏览器视觉验收、真实异常恢复及完整稳定版验收仍未完成，不把本次成功升级称作全部交付完成。

## 2026-09-06：真实发布历史的浏览器验收与排版修复

新增 scripts/test-release-evidence-ui.cjs，使用独立 headless Chromium，通过 docker exec/curl 转发读取页面，无宿主监听端口。默认页面资源来自恢复实验环境的 V37 镜像，失败历史 API 来自源 V36 的实际 e2e-demo；第二个独立浏览器 context 使用恢复环境 V37 API 验证两类回调检查。拦截所有非读取请求（仅允许登录/续期/退出），拒绝外部 origin，不触发部署、审批、回滚或凭据写入。混合版本读取仅用于前端兼容/展示测试，不能据此声称源平台已升级。

真实数据验收确认两次历史失败分别出现镜像拉取和 digest 再打标签指引，成功部署行没有失败卡片；日志可展开关闭，未发生意外写请求或页面 JS 错误。V37 实际 readiness 卡片显示构建 PASS、发布 WARN。首次脚本断言通过，但人工看截图发现：手机卡片太宽、恢复提示被公共表格样式截断，长 digest 挤入相邻发布阶段。因此未将首次绿色测试认定为视觉验收通过。

修复失败卡片的视口约束、12px 字体和完整换行，使用适配主题的颜色；覆盖 small 的省略规则，保留完整恢复提示。修复交付阶段 grid 子项 min-width，长 digest 在本格省略并保留 title 完整文本。补充浏览器断言卡片宽度、恢复提示 white-space=normal、digest 不越过阶段边界及 title 等于完整内容。

最终 npm run build（含类型检查）、35 项前端测试和增强后的浏览器测试均退出 0。本次样式使用 DEVPILOT_TEST_LOCAL_DIST=true 读取最新生产构建，API 仍为上述实际隔离环境；未重新构建/部署 Docker 镜像。最终截图目录 /var/folders/0k/bg28r22s5m53w39tn9b5krz80000gn/T/devpilot-release-evidence-ui-MxX0eF，包含桌面失败卡片、390px 手机卡片及实际 V37 回调检查；人工复看确认恢复文字完整、digest 不再覆盖相邻步骤。git diff --check 通过。

此验收覆盖发布失败历史与回调检查的指定路径，不等于整个站点视觉审查、全新用户接入或所有异常场景完成。独立恢复环境 V37 镜像仍是样式修复前版本，源平台和健康 v4 未改动，完整目标保持进行中。

## 2026-09-06：V37 新回调证据的独立数据库恢复

从已升级的 devpilot-stability-restore-lab 使用正式 backup.sh 生成 /opt/stability-v37-evidence-backups/devpilot-20260906T084738Z-Id7O1c.tar.gz 及 sidecar。备份含 MySQL SQL、Compose/网关配置和主密钥；私有转移材料位于 /tmp/devpilot-v37-recovery.fdWg79，不提交 Git 或作为公开附件。

为避免覆盖两个已有验收数据库，新增 devpilot-v37-recovery-proof，镜像 devpilot/install-lab:acceptance，独立 daemon 数据卷 devpilot-v37-recovery-proof-data，network=none、无宿主端口、仅仓库只读挂载、无宿主 Docker socket。它仍是 privileged Docker-in-Docker 测试环境，不是生产隔离方案，也未解决 DS002。首次导入早于 daemon 就绪而失败；只读日志及 docker info 确认 Docker 28.5.2 已就绪后重试导入，未重建现有实例。

仅复制备份/配置/私有验收客户端材料及镜像，不复制数据库卷。新 daemon 的内部 loopback Registry 提供相同 V37 Server/Web digest（30e582dc…9344b / 765dc21d…ca55d）；启动 mysql/redis/web，新创建 devpilot_mysql-data 与 devpilot_redis-data。恢复前实际 SQL 查询目标数据库表数为 0，校验 sidecar OK。随后仅对该新空库执行正式 restore.sh --install-dir /opt/devpilot --archive /opt/recovery-input/devpilot-20260906T084738Z-Id7O1c.tar.gz --yes，退出 0，五个服务 healthy。

在任何签名重放前，SQL 查询恢复后的 Flyway 为 37/success=1，三个配置的回调字段与备份来源逐项相同：应用 2096404647212032001 的 build_callback_verified_at=2026-09-06 08:40:04、callback_verified_at=NULL；其余两条均为 NULL，部署表零行。这证明保留了已有构建验证时间和“尚未验证发布”的区别，不是恢复后测试重新写入的替代证据。

随后三组 API verify 全部通过：管理员登录、原服务器/应用和初始化配置、加密公开变量解密/秘密脱敏、原构建 digest、原审批身份/时间/撤销历史、签名幂等重放、observer 配置。后两组仍明确不证明合成 GitHub Token 的真实授权；全程无业务部署。从恢复平台下载 arm64 Agent 并用原恢复凭据启动，REQUIRE_AGENT=true 再验通过，Agent ONLINE、至少五条真实容器快照。

验收后停止新增 recovery-proof 外层容器及其中测试进程，保留容器、命名数据卷与备份以便复核，不删除恢复材料。源 V36、已升级 V37 的恢复平台和 Dokploy v4 未停机或改配置。V37 新字段独立恢复已获得真实 MySQL 证据，但仍不等于主机内核重启、真实业务故障回滚、全部安全门禁或用户独立接入等剩余目标完成。

## 2026-09-06：故障自动回滚验收前的只读核对（尚未批准故障发布）

源 V36 的 e2e-demo 运行态仍为批准的 v4 digest dec60c4be27500525c439ff11f1686edd589963997cb9fe654cbd6824bd6f9aa，部署 2096503104338214913 HEALTHY，Agent 在线且健康检查时间 2026-09-06T08:52:06.515033。配置读取 autoRollback=true、healthTimeoutSeconds=120；历史最新健康基线确为该 v4，不再是没有健康发布历史的首次发布状态。readiness 无阻断，平台连接验证超过 15 分钟为 WARN，执行前仍须重新验证。

故障候选为原构建 build:github-34017532658-1（buildId 2096491132800548866），commit 916c3af6d47c9b2e510359e83ea08f4df0df7a85、digest 1da5382db94490d8322d78701b2a0dfe8bafc101ac453b7fd91196719108f621。GitHub 只读查询仍 completed/success，quality/security/image 通过、production skipped；DevPilot 仍 AWAITING_APPROVAL。读取该提交源码确认版本 v3-fault、默认 DEVPILOT_E2E_FAIL_HEALTH=1，/health 有意返回 503。这不是普通升级版本，可能让测试服务短暂不可用。

仅获取故障构建的版本绑定 approval-context，未创建确认记录、未 dispatch 工作流、未改自动回滚开关或目标镜像。现阶段明确请求用户确认在这个本地 TEST 应用发布故障镜像、由系统回滚到 v4，并允许自动恢复失败时人工恢复同一 v4；此前“确认发布 v4，失败恢复 v2”不被扩张为本轮故障发布授权。真实故障识别/自动回滚场景仍未完成，不能用只读预检替代。

## 2026-09-06：主项目 CI 最小权限修正

自动继续目标不视为故障发布授权，仍未创建故障审批或发起部署。核对本机未发现 limactl/multipass/qemu-system-aarch64/tart 可执行工具；结合此前 rootless 与 root-owned 安装目录不兼容的实测，没有用只改 USER、放宽目录权限或忽略规则来消除 DS002。该问题保持开放，不由本轮声明解决。

检查主项目 .github/workflows/cicd.yml 发现顶层 packages:write/security-events:write 会使非镜像任务也申请不必要的写权限。修改默认权限为 contents:read，仅 images job 声明 packages:write，保留 needs=[quality,security] 和 PR 不推送规则；删除未用于 SARIF 上传的 security-events:write。四处 checkout 设置 persist-credentials:false，避免把令牌保留在工作树 Git 配置。回调任务仍在 production environment，未变更环境授权或 Secrets。修正旧注释：workflow_dispatch 可由 API 调用，不能独立作为人工确认的证明。

新增 projectCiPolicy.test.mjs 检查默认/逐 job 权限、镜像前置门禁、PR 不推送、四处 checkout 不持久化令牌及不使用 pull_request_target。首次测试把事件键也当作 job 且漏算带连字符的报告 job，断言失败；已限定 jobs 区段并覆盖全部四个 job，最终前端 37 项测试通过。主 workflow actionlint v1.7.7（不含 shellcheck/pyflakes）及 git diff --check 通过。尚未推送或运行真实 GitHub CI，不宣称远端权限已更新或完整安全验收通过。

## 2026-09-06：发布前检查不再信任过期运行证据

CicdReadinessService 原先仅按 ONLINE/running 状态判断 Agent 和容器通过，缺少心跳/清单有效期；健康检查虽拒绝旧数据，但未拒绝明显未来时间。本轮增加统一近期 Agent 条件（ONLINE、心跳存在、2 分钟内、不超前 30 秒），在线标记缺少有效心跳时 WARN。健康检查与当前容器检查都要求有效 Agent 证据；健康时间、清单时间明显超前或清单超过 2 分钟时 WARN，明确当前状态未知，不把历史正常变成当前验证成功。明确 OFFLINE 仍在 AGENT 项 BLOCK，不把遥测缺失直接称为业务异常。

新增 HTTP 集成场景以受控测试数据库构造过期/未来容器时间、未来健康时间、过期/未来心跳、明确离线及恢复正常时间。断言健康/容器不误 PASS，恢复新鲜数据后重新 PASS，明确离线 BLOCK，始终零部署。第一次运行因新增测试误用 application_record 表名失败，已核对实体表名修正为 application，完整 CicdIntegrationTests 重跑 40 项全部通过，零失败/错误/跳过（Java 25/H2）。git diff --check 通过。

本轮无新迁移，不改变镜像/部署任务、不向故障候选发出批准；尚未构建部署新的 Server 镜像或在运行 MySQL/浏览器上验证这部分新逻辑。故障发布仍需明确确认，DS002 及完整稳定版剩余事项保持开放。

## 2026-09-06：接入重试保留远端实际镜像

ProviderOnboardingClient.configure 原来每次 Dokploy 配置均写入 pending-first-release。在配置成功但响应丢失、任务仍停在 stage 2 时，重试可能覆盖远端随后设置的实际镜像。本轮改为写入前先 application.one 核对目标 ID，保留非空 dockerImage，仅空值初始化占位镜像；原有配置后的只读验证保留。未新增部署调用、registryId 推送绑定或应用创建。此处读后写仍不提供平台侧原子比较更新，不声称已解决与外部控制台同时修改的所有竞态。

新增 ProviderOnboardingRetryTests 三项：连续两次配置保留完整 digest/拉取凭据、复用相同端口且无其他资源或部署调用；空镜像初始化及错误应用 ID 在写入前拒绝；模拟写后响应丢失后，重试重新读取并保留已变化的远端 digest。首次测试遗漏原有配置后验证请求，顺序断言失败；修正为核对真实 GET→POST→GET 次序，不删除安全验证。

最终 ProviderOnboardingRetryTests 3、RegistryCredentialPolicyTests 3、CicdIntegrationTests 40 均通过，共 46 项零失败/错误/跳过（Java 25；Provider 使用 HTTP mock，集成使用 H2）。git diff --check 通过。未对真实 Dokploy 配置做写入试验，未发布新平台镜像；健康 v4、现有接入资源及 Secrets 未变。真实故障发布仍待用户确认，完整目标保持进行中。

## 2026-09-06：接入恢复不清空远端构建配置

继续检查 configure 发现保存计划环境变量时固定发送 buildArgs=""、buildSecrets=""、createEnvFile=false，可能清除重试前已有设置。改为在任何配置写入前检查远端 env 及所需构建配置字段；变量为空时按计划写入并保留读取到的构建参数/构建秘密/文件开关，变量文本完全一致时不重复调用 saveEnvironment。非空差异、缺少字段或字段类型异常时，明确提示核对并停止，错误不回显变量或秘密。没有增加部署动作，也不把任意 dotenv 文本自动合并；格式差异及外部并发仍需审阅，不能称为所有冲突均自动解决。

仅对现有本地 Dokploy application.one 做只读字段存在性/类型核对，env/buildArgs/buildSecrets 均返回 string，createEnvFile 返回 boolean；不展示字段值、未写入目标应用。新增测试覆盖保留构建设置、相同环境重试不写入、远端差异/字段缺失/异常类型在任何写入前拒绝及错误不含秘密。最终 ProviderOnboardingRetryTests 5、RegistryCredentialPolicyTests 3、CicdIntegrationTests 40 全部通过，共 48 项零失败/错误/跳过；git diff --check 通过。

本轮仅源码、测试和文档更新。真实写入响应及完整浏览器恢复路径尚需专用测试应用验证，未部署新版镜像，未进行故障发布或修改健康 v4。整体目标保持未完成。

## 2026-09-06：端口重试在写入前核对冲突

configure 原先仅比较 publishedPort/targetPort，且在检查前已写入 Registry 配置。最新源码要求 ports 为数组、端口数字字段为整数，同一发布端口必须唯一且目标端口、tcp 协议、ingress 模式均匹配；无法读取、冲突或重复时在任何配置写入前停止并提示核对原应用。其他发布端口不删除，空列表仍允许首次创建。此处未扩展为服务器全进程端口检测，也没有平台侧原子锁，外部同时变更仍可能产生竞态。

新增两项 mock 测试覆盖端口列表异常、目标/协议/模式差异、重复映射及写前拒绝；模拟 port.create 已保存但响应丢失，重试只创建一次端口并复用真实读取结果。已有测试改用完整协议/模式字段。只读查询当前本地 Dokploy 实际应用，确认 ports 数组包含数值 18088→8080、protocol=tcp、publishMode=ingress；未改动该应用。

ProviderOnboardingRetryTests 7、RegistryCredentialPolicyTests 3、CicdIntegrationTests 40 均通过，共 50 项零失败/错误/跳过；git diff --check 通过。上述异常重试仍是 mock 验证，不代表真实 Dokploy 超时或浏览器接入流程已经验收。未部署新平台镜像，故障发布未获新确认，完整目标继续保持未完成。

## 2026-09-06：真实 Dokploy 配置重试验证（不部署）

新增显式启用的 DokployLocalRetryLiveTests，调用当前 Java ProviderOnboardingClient 和真实 OnboardingHttpClient，仅允许 localhost:19000 的应用创建、配置及读取接口。配置写入被限制到本轮创建/复用的 scratch ID；归属标记和 dp-retry-live 名称会被核对，拒绝已完成端口配置的旧测试应用作为新一轮测试起点。没有部署、构建、镜像拉取或删除端点。请求对象使用 mock 提供参数，但 HTTP 和 Dokploy 持久化均为真实操作。

首次初始化测试数据遗漏 saveDockerProvider 的用户名/密码字段，得到 HTTP 400；修正为合成测试值后复用该空应用通过。增加归属防护后，以新的空应用重跑最终版本，真实测试 1、ProviderOnboardingRetryTests 7、RegistryCredentialPolicyTests 3 共 11 项通过，无失败/错误/跳过。场景覆盖 ensureApplication 复用、不覆盖已有 digest、保留构建参数/构建秘密/createEnvFile、相同环境不重复保存，以及端口/环境差异在写前拒绝。端口响应丢失由客户端在真实成功响应之后主动抛错模拟，重试读取真实已保存端口并不再创建；不声称模拟了网络层真实超时。

保留两个仅含合成配置的空应用供复核：ToYJOxJ03LBPAbvKBullY（dp-retry-live-46b64a13-duug30）及 uyu3QjT0xFmKmqXVs5rww（dp-retry-live-5ba4af6a-s26v2w）。均未部署，端口 28089 只是数据库配置，未启动服务占用端口。清理时只核对并删除这两个 scratch 应用，不能删除所在共享项目或环境。实际业务应用 e2e-demo 的 v4 健康和 digest 在测试后复查一致。

复跑方法：在 devpilot-server 目录设置 DEVPILOT_LOCAL_RETRY_LIVE=true、DEVPILOT_LOCAL_DOKPLOY_KEY_FILE（受保护的 Key 文件路径）、DEVPILOT_LOCAL_DOKPLOY_PROJECT、DEVPILOT_LOCAL_DOKPLOY_ENVIRONMENT，再运行 `mvn -Dtest=DokployLocalRetryLiveTests test`。只面向明确授权的本地测试 Dokploy，每次默认创建一个唯一空应用并保留；普通 CI 未设置启用变量时跳过。DEVPILOT_LOCAL_RETRY_JOB_ID 仅用于端口尚未创建时恢复失败的测试初始化，不得指向业务接入任务。不要把 Key 放入命令行或测试输出。

仍未验收完整浏览器新项目接入、真实故障发布/自动恢复或本轮源码的平台镜像升级；稳定版总体保持进行中。

## 2026-09-06：近期重试及状态修复后的完整本地回归

本轮对当前工作树运行完整测试，而非仅最近新增测试：

- Server：Java 25 执行 `mvn -q test`，28 个 Surefire 报告均在本轮 09:20 UTC 更新，合计 113 项，112 通过、0 失败、0 错误、1 跳过。唯一跳过是需要显式授权环境变量的 DokployLocalRetryLiveTests，上一节已单独真实执行通过；本轮不创建新 Dokploy 资源。HTTP 集成依赖 H2，不能替代生产 MySQL 验证。
- Web：37 项测试通过，类型检查和生产构建通过。
- Agent：`go test ./...` 通过；另执行 `go test -race -count=1 ./...`，7 个有测试的包通过，排除缓存复用；2 个包无测试文件。
- `scripts/verify-cicd.sh` 通过（文件/静态规则、Shell 语法和 Compose 配置检查，不是远端交付验收）。
- 维护与安装升级测试分别在无网络、仓库只读挂载的临时 Maven Linux 容器执行，通过锁冲突、安装恢复、备份校验、危险归档拒绝、升级/恢复失败保持停止和保留配置等脚本断言。内部 Docker 命令为模拟，日志中的卸载/删除测试没有删除现有 Docker 业务资源。
- 8 种 GitHub 生成模板（NODE/JAVA/GO/DOCKER × Preview 开关）以及主项目 workflow 均通过 actionlint 1.7.7，未运行其可选 shellcheck/pyflakes；没有 dispatch 或推送。
- `git diff --check` 通过。

当前结论是本地回归通过，不是稳定版发布批准。主机真实重启、新项目完整 UI 接入、故障发布与恢复等验收仍缺证据；最新代码尚未打入新平台镜像升级。install-lab.Dockerfile 仍使用 root Docker-in-Docker，先前 DS002 安全门禁问题未修复，本轮也没有重新扫描或关闭该门禁。整体目标未完成。

## 2026-09-06：重试保护候选镜像在恢复实验环境升级

构建本地 `devpilot/server:stability-retry-v37` 和 `devpilot/web:stability-retry-v37`，含近期接入重试保护、运行证据有效期和失败卡片布局修复。镜像平台为 linux/arm64，Web 内附带的 Agent 文件同时包含 amd64/arm64；不能将此称为平台镜像已完成双架构构建。未推送 GHCR，未改动业务发布 workflow。

Trivy 0.65.0 使用新下载漏洞数据库及 Java DB，对归档执行 `image --scanners vuln --severity HIGH,CRITICAL --exit-code 1`，未使用 ignore-unfixed。Server 的 Alpine/Java、Web 的 Alpine/两个 Go 二进制均为零高危/严重项，退出码 0。扫描器提示 Alpine 3.23/3.24 不在 EOL 列表、部分严重性来自其他供应商；本次扫描不覆盖完整源码安全门禁，DS002 仍未解决。缓存保留在 Docker volume `devpilot-trivy-validation-cache`，归档及 JSON 报告位于受限目录 `/tmp/devpilot-retry-v37-validation.Qrw2Kb`。

只升级 `devpilot-stability-restore-lab`。本地仓库最终 digest：

- Server：`127.0.0.1:15000/devpilot/server@sha256:d688336ebfc73bc3e7d4dc4188b4294eb3f5e74f5a36cbbce44bf090a1134e0e`，config ID `e67e54ca2c591e0ed1720264b3c10b716ea90e042f47814bbb6ab6a389e04d9e`。
- Web：`127.0.0.1:15000/devpilot/web@sha256:31880f812e1e9b168351121aed34144370302170a5d870d2c2db9dfa16a25dbe`，config ID `dcfd7c523f97aa49d269678ba2fe668e592fa5135bad482869e73c7e4a6e79bb`。

归档转入实验环境 `/opt` 后与宿主 SHA256 一致（Server `63e675c7fabbdcb8653c0e9047066622230e08b0b84226925f2ddef4dee042bb`、Web `89abc4386e5ff9f2953267af37c73694522881dbc4361cdf89728d960c1f80e6`）。最初传到 `/tmp` 后无法由容器读取，未据此运行升级；改用 `/opt` 后验证成功。新旧 Server JAR 中迁移内容的串联 SHA256 相同，为 `7b707bd6d1dc7fe567bc2379cec19df620357913e143110a6a7c34c60c2a1e18`，数据库保持 V37。

正式 upgrade.sh 先停止入口/Server、生成备份，再按 digest 更新并等待健康，最终退出 0，五个 Compose 服务健康，备份状态上报成功。备份为实验环境 `/opt/restore-lab-retry-v37-upgrade-backups/devpilot-20260906T092650Z-YnoWUg.tar.gz`；首次校验在错误工作目录找不到相对文件，改在备份目录执行 `sha256sum -c` 后确认 OK。备份含秘密，不能公开。启动日志验证 37 项迁移、MySQL schema 无需更新；仍存在 Flyway 对 MySQL 8.4 支持范围的警告，应后续处理，不标记完全兼容。

升级后 persistence、release-approval、observer-persistence 三项 API verifier 通过。确认 verifier 包含签名构建回调重放与旧撤销确认幂等请求，但没有新部署或真实 Provider 调用；观察器仅验证配置和脱敏存在，不证明 GitHub 认证。未使用 local-dist 覆盖，浏览器直接加载运行中的新 Web 镜像，失败记录、日志展开、移动布局及构建/发布回调证据分离测试通过；三张截图人工查看通过，位于 `/var/folders/0k/bg28r22s5m53w39tn9b5krz80000gn/T/devpilot-release-evidence-ui-hLoYev`。

业务源环境仍为 V36，健康 v4 的实际 digest、容器和近期健康复查一致；本轮没有故障发布。恢复实验环境升级证明了候选平台构建可运行，但尚未证明完整新用户接入、真实主机重启或故障自动恢复，稳定版目标仍未完成。

## 2026-09-06：Flyway 依赖兼容性升级准备

核对 Spring Boot 3.5.16 BOM 和依赖树，原实际 Flyway 为 11.7.2。官方 [11.20.3 发布记录](https://documentation.red-gate.com/flyway/release-notes-and-older-versions/release-notes-for-flyway-engine)及该版本 [MySQLDatabase 源码](https://github.com/flyway/flyway/blob/flyway-11.20.3/flyway-database/flyway-mysql/src/main/java/org/flywaydb/database/mysql/MySQLDatabase.java)显示新版本 MySQL 升级建议阈值为 9.4。官方 MySQL 驱动页面的 verified versions 并未单独列出 8.4，因此不能仅凭阈值判断实际业务完全兼容。

在 pom.xml 统一设置 flyway.version=11.20.3，保留当前 11.x 主版本；依赖树确认 core/mysql 同为 11.20.3。未修改迁移 SQL、执行 repair/baseline 或改变数据库版本。Java 25 下完整 `mvn -q test` 退出 0，113 项中 112 通过，0 失败、0 错误，真实 Dokploy opt-in 测试 1 项按默认跳过；报告最早更新时间为本轮 09:30:54 UTC。H2 中 37 项迁移校验通过。

这仅完成依赖调整和本地回归；运行中的恢复环境仍用上一节镜像及旧 Flyway，警告尚未在真实 MySQL 上验证消失。接下来必须构建扫描新 Server 镜像，并在备份保护的隔离 MySQL 环境验证启动、历史校验及数据持久化。旧镜像扫描结果不能直接用于新增依赖后的镜像。稳定版目标保持未完成。

## 2026-09-06：Flyway 11.20.3 真实 MySQL 升级验证

构建 `devpilot/server:stability-flyway-v37`（linux/arm64），镜像内确认 flyway-core/mysql 均为 11.20.3；37 份迁移内容串联 SHA256 仍为 `7b707bd6d1dc7fe567bc2379cec19df620357913e143110a6a7c34c60c2a1e18`。新归档及扫描报告在 `/tmp/devpilot-flyway-v37-validation.EWKBPY`。Trivy 0.65.0 使用前轮下载的有效缓存扫描 Alpine 和 Java，HIGH/CRITICAL 均零、退出 0，未忽略无修复项；Alpine EOL 列表警告仍存在，未宣称完整源码门禁通过。

扫描的 config ID 为 `415aa06372f5c6ce02511a8a8abcfea0e8eb8a639c2c937fdcf08000681a38f9`；导入恢复实验环境的最终镜像为 `127.0.0.1:15000/devpilot/server@sha256:ffa1a521361e2a7d9f5f059659ab60c86694d20281b89891a06cf1217c054afd`。只升级 devpilot-stability-restore-lab，Web 保持 digest `31880f812e1e9b168351121aed34144370302170a5d870d2c2db9dfa16a25dbe`。未推送 GHCR 或触发业务发布。

正式 upgrade.sh 退出 0，备份 `/opt/restore-lab-flyway-v37-upgrade-backups/devpilot-20260906T093359Z-k7kUgO.tar.gz` 校验 OK、备份状态上报成功，五个 Compose 服务健康。新容器 09:34:02 UTC 启动日志确认真实 MySQL 8.4、37 项迁移验证通过、无需新增迁移，未再出现之前的 MySQL/Flyway 兼容性警告。升级前后数据库 flyway_schema_history 的记录数/成功数均为 37/37，按 installed_rank 排序的 rank/version/checksum/success 摘要均为 `4949c27fb7e55eb15dcd5270c30133860509463a1fda0d83454294c6160b020b`，未使用 repair/baseline 或重写迁移历史。第一次只读摘要 SQL 使用不合法的 SEPARATOR 表达式，修正查询后取得前后摘要；未执行数据库写入纠错。

升级后 persistence、release-approval、observer-persistence 三组 API verifier 通过：登录、受管配置解密/脱敏、历史 digest/确认身份和幂等、观察器配置保留均通过；确认验证包含受控签名回放且始终零部署，观察器检查不代表真实 GitHub 认证。业务源环境 v4 复查健康、digest 和容器一致。此项证明当前数据集的升级兼容性，不等于覆盖 MySQL 全功能或新安装全套迁移。本轮未改 UI，未重跑浏览器视觉测试。

当前恢复环境已使用新 Flyway，原警告在该环境验证消失。DS002、完整新用户 UI 接入、真实主机重启及故障发布/恢复仍待完成，稳定版总体目标继续进行中。

## 2026-09-06：rootless 安装实验的命名空间权限方案

此前失败是外层 UID 0 创建的 0700 目录在 rootless daemon 内映射为 nobody，并不证明 rootless 无法运行安装验收。本轮用独立 docker:28-dind-rootless 探针验证：默认外层 UID 1000，显式 `dockerd --host=unix:///run/user/1000/docker.sock`，不添加默认 TCP 2375/2376 监听。通过 nsenter 进入实际 dockerd 的 user/mount/network namespace 后，命名空间内 UID/GID 为 0，可创建 root:0700 的目录；外层相同目录为 UID 1000:0700。导入 alpine:3.22 后，内层容器只读 bind 可以读取 marker，并看到 0:700。外层和命名空间内均无 TCP 监听。

新增实验文件 `scripts/fixtures/rootless-install-lab.Dockerfile` 和 `rootless-lab-exec.sh`，默认 USER rootless，构建期仅安装必要工具。包装命令拒绝外层 root，要求恰好一个 UID 1000 的 dockerd、核对 uid_map 和 rootless SecurityOptions，然后进入目标命名空间执行安装/维护命令。没有改变正式安装脚本的 root/0700 检查，也没有修改 DS002 忽略规则。

实验镜像 `devpilot/install-lab:rootless-candidate` 构建通过，镜像索引 digest `f89c076d90c69ce39498e12a681f45cd6dcee780e1ffa5e96ccd9417bbbe38b4`。新容器 `devpilot-rootless-install-candidate` 默认 UID 1000、network=none、无发布端口、仅仓库只读挂载、无宿主 Docker socket，2 CPU/2 GiB，仍需 privileged（仅本地测试，不称为强隔离或生产部署方案）。经包装命令运行 test-install-upgrade.sh 和 test-maintenance.sh 均退出 0；这些脚本仍使用模拟 Docker，不代表 MySQL/Server 完整安装已经成功。

初始 `devpilot-rootless-namespace-probe` 已精确停止，因 --rm 删除其临时容器/匿名卷，仅含 marker 和测试 Alpine 镜像，无业务数据。candidate 留运行供下一轮真实安装验证，尚无业务服务或数据；旧安装 fixture 和所有既有验收环境保持不变。候选方案尚需完整服务安装、备份恢复、Agent 和重启验证，以及实际安全扫描，不能提前宣称 DS002 已解决。

## 2026-09-06：rootless 候选真实安装与 Agent 验证

复用上轮仍运行的 devpilot-rootless-install-candidate，先确认内层没有容器，离线导入当前 Server（stability-flyway-v37）、Web（stability-retry-v37）、mysql:8.4、redis:7.4-alpine、nginx:1.29-alpine。经 devpilot-lab-exec 执行正式 install.sh，安装路径 `/home/rootless/devpilot`，入口只在隔离环境内为 127.0.0.1:18081，使用 --offline，未改正式安装脚本。成功退出 0，五个服务均健康；真实 MySQL 8.4 在空 schema 应用全部 37 项迁移至 V37。该实验同时补充 Flyway 11.20.3 的首次迁移证据，但仍不是全功能兼容认证。

使用独立验收 API 初始化管理员/服务器、创建测试应用及加密环境变量和合成平台凭据；私有状态仅存 `/home/rootless/stability-evidence`。下载本机 Web 提供的 arm64 Agent 至 `/home/rootless/stability-agent`，通过命名空间包装命令启动，继承 rootless Unix socket 连接。persistence verifier 开启 DEVPILOT_TEST_REQUIRE_AGENT=true 后通过：管理员登录、配置 revision、公开值解密、秘密脱敏、Agent ONLINE、至少五个实际容器清单。此为 API 验收，不宣称新用户浏览器接入流程已经完成。

正式 backup.sh 在 `/home/rootless/stability-backups` 生成 `devpilot-20260906T094050Z-OJhlMC.tar.gz`，SHA256 sidecar 校验 OK。命名空间内 `.env` 为 UID 0:0600、证据和备份目录 UID 0:0700，安装目录本身为脚本原有 0750，未放宽权限。备份含随机测试管理员密码、加密主密钥等秘密，不能公开；应保留供独立恢复测试。内层 Docker 28.5.2 的 SecurityOptions 仍包含 rootless，外层未发布主机端口或挂载主机 Docker socket。

候选环境当前保留运行及测试数据，不能再视为空探针直接清理。旧业务 v4、源实验环境和恢复实验环境均未改动。尚需 rootless 的独立恢复、重启持久化、fixture 替换和完整源码安全扫描，DS002 继续保持未解决而非忽略。实际宿主内核重启/systemd 验收仍缺失，不能以命名空间实验替代。

## 2026-09-06：rootless 独立恢复通过，重启暴露运行目录问题

新建 devpilot-rootless-restore-candidate（非 --rm，network=none、无宿主端口、只读仓库、2 CPU/2 GiB、privileged），从源 rootless-install-candidate 仅传输配置、备份、验收身份及 Agent 文件，不复制数据库卷；镜像单独离线导入。首次核对内层无容器/卷，新建 MySQL 后目标 schema 表数为 0。备份 `devpilot-20260906T094050Z-OJhlMC.tar.gz` 校验 OK，经命名空间包装运行正式 restore.sh --yes，退出 0、五个服务健康。

恢复后 persistence verifier 验证管理员登录、配置 revision、公开值解密及秘密脱敏通过；手动启动目标环境 Agent 后，REQUIRE_AGENT=true 验证 ONLINE 和至少五个实际容器清单通过。源实例没有停止或覆盖，目标数据库来自备份恢复而非挂载源卷。这里仍是 API 验收，不代表浏览器完整接入验收。

随后对目标外层容器执行 docker restart。重启前 `.env` 摘要为 `0e558be051e5da3a1b8ebd488892f8b2c56497d320a928a929febc52807ebe72`，内核 boot_id 为 `10ac55f5-c9e9-45dd-b833-ebfa941ab8dc`（仅容器重启，不会证明主机重启）。容器 StartedAt 更新至 09:44:05 UTC 后实际退出 1；日志显示 containerd 状态残留，先报告 containerd is still running/pid=89，随后 timeout waiting for containerd to start。wrapper 无法连接 daemon 的报错不是测试通过，未盲目反复重启或清除数据库。

当前 target 为 exited，保留可恢复的配置、备份及数据卷。它的 rootless Docker 数据卷为 `c235ab1c492335eee0a31587cc04b885665d21242950a1233c52c54c47f6f57d`，另有未使用的继承 rootful 数据卷 `3b4c408535549b0893a7d04cceec71df59742c3bb2ec6acd04cd7b1d27bf821d`，均未删除。source rootless-install-candidate 仍运行且持有原备份。下一步应将运行状态目录 `/run/user/1000` 以 UID/GID 1000、0700 的 tmpfs 提供，使重启不复用旧 PID/socket，再在独立候选环境重新验证；该修正尚未实测，不宣称已解决。

结论：rootless 独立恢复已获真实证据，rootless 重启验收失败，旧 fixture 仍不能替换，DS002 仍开放。业务 v4 及既有两个稳定性实验均未修改。

## 2026-09-06：tmpfs 修复重启，DS002 配置扫描通过

另建 devpilot-rootless-tmpfs-candidate，保持默认 UID 1000、network=none、无宿主端口、只读仓库及 privileged 测试边界，只增加 `/run/user/1000` 的 tmpfs（UID/GID 1000、0700、nosuid/nodev）。findmnt 实测确为 tmpfs，Docker 状态目录重启后不复用旧 PID/socket；数据库仍在持久化内层卷。没有删除前轮失败容器或其数据。

从相同源备份重新独立恢复：新目标起始无卷/容器，MySQL 表数为 0；正式 restore.sh 成功、五个服务健康，管理员/应用环境/合成平台凭据验证通过。随后两次 docker restart，外层 StartedAt 分别为 09:47:52 和 09:48:17 UTC，两次 daemon 均自动恢复；第二次后五个服务均 healthy。每次登录、加密配置和 revision 验证通过，`.env` 摘要始终为 `0e558be051e5da3a1b8ebd488892f8b2c56497d320a928a929febc52807ebe72`。之后手动启动测试 Agent，REQUIRE_AGENT=true 验证 ONLINE 和至少五个真实容器通过。这里没有声称 Agent 自动启动或宿主内核重启通过。

将已验证的 rootless 内容合并到正式测试 fixture `scripts/fixtures/install-lab.Dockerfile`，删除本轮早先新增的重复实验 Dockerfile（不是用户业务文件）；保留包装命令。新增 `scripts/start-rootless-install-lab.sh` 固定 Unix socket/none 网络/tmpfs/只读仓库启动方式，并拒绝覆盖同名容器；Shell 语法及真实同名拒绝检查通过。新建操作参数与本轮直接执行的成功启动参数一致，但脚本的新建分支未额外创建第四个环境。正式 fixture 重建成功，运行层 manifest `4c06fa58ccc0eaa3cd92367cf5244b5abcc94aee6044cfa18d866233817ae73c` 和 config `3d9d586193dc13523080733775b0e2ce30fb8b4322c5a390e9e6f877b7d7d59b` 与实测候选一致。

Trivy 0.65.0 对当前仓库执行 fs/scanners=misconfig/severity=HIGH,CRITICAL/exit-code=1，退出 0；JSON 报告明确列出 Agent、Server、Web 及 scripts/fixtures/install-lab.Dockerfile 四个目标，各 20 个成功检查、0 失败。报告位于 `/tmp/devpilot-rootless-security.kYJDvl/misconfig.json`。DS002 在此配置扫描中已消除，没有更名躲避扫描或添加忽略规则；本次只扫描 misconfig，不等于完整依赖/密钥/源码安全门禁通过。

新增 [rootless 安装验收说明](rootless-install-lab.md)，说明构建上下文、正确启动/命名空间执行方式、离线导入、凭据权限、数据保留和局限。旧 rootful 实例及镜像不会自动转换。当前保留 source 和 tmpfs-candidate 运行，失败 restore-candidate 停止并保留证据。后续仍需完整安全门禁、正式 fixture 启动脚本完整流程、真实主机重启和新用户 UI/故障发布等验收，整体目标保持未完成。

## 2026-09-06：完整本地安全门禁与主仓库发布协议调整

重新核对 `/tmp/devpilot-full-security.S7m5D7` 的实际报告：source.json 包含 Go、Maven POM、npm lockfile 和四个 Dockerfile，HIGH/CRITICAL 漏洞、密钥及配置发现均为 0；源码扫描于 09:51:56 UTC 退出 0。Maven POM 解析有无法确定依赖版本的警告，因此另用 CycloneDX 2.9.1 解析包含测试依赖的 BOM，134 个组件均有版本（包括两个 Flyway 11.20.3 组件及测试依赖）；java-sbom-scan.json 对 Java 清单的 HIGH/CRITICAL 发现为 0，09:52:10 UTC 退出 0。第三方 BOM/hash 支持警告仍存在，没有关闭规则或忽略未修复项；零发现不等于没有风险。该报告先于下面的 workflow 调整，不能当作修改后文件的扫描证据。

用户更换 Registry 凭据后，实际只读核验 classic PAT 的 GitHub 认证 200、账户匹配、scope 仅 read:packages；私有 GHCR 的 v4 digest 与 sha tag 均返回 200，镜像索引包含 linux/amd64 与 linux/arm64。业务 v4 在 09:56:56 UTC 的健康证据仍为 HEALTHY，实际容器/digest 与已批准版本一致。本次没有发布或修改用户凭据。

检查发现主仓库 `.github/workflows/cicd.yml` 仍使用历史 tag 回调，未传递版本绑定的确认 ID。将该入口替换为与生成模板一致的 recover_build/production 协议，并保留三组件质量、安全及双架构构建。push 保存每个组件的 digest/commit/run/attempt 凭证；手动任务不重建，验证原仓库/分支/workflow、质量与安全及三个镜像任务全部成功，再验证原 Web 产物并传入人工确认 ID。构建开始/结束使用独立派生密钥，完成回调等待开始回调任务终结，避免本流程内完成后又回报 RUNNING。构建报告和可选 Web 发布须显式设置 DEVPILOT_SELF_DELIVERY=true，默认仍可构建三组件镜像，不能把 Web 演示发布称为全平台升级。

新增 `scripts/test-platform-workflow.mjs`，直接提取实际 workflow Shell 片段，用本地合成 GitHub 响应验证：26 个用例通过，覆盖五个门禁分别失败、缺失/重复任务、缺失确认 ID、错误来源/commit/digest/run/attempt、回调 HMAC 与确认身份绑定、缺失回调地址拒绝。发现 macOS Bash 3.2 对 `[[ ... ]]` 的 errexit 行为不同，因此关键正则校验显式 `|| exit 1`，未把本机假通过当成门禁通过。测试仅删除自己新建的合成临时目录，不访问远端或真实秘密。Docker buildx 对公开 Alpine 镜像的只读查询确认 `{{.Manifest.Digest}}` 返回 sha256 digest；未推送该镜像。

主仓库及八种生成模板 actionlint 1.7.7 通过，未运行外部 shellcheck/pyflakes；verify-cicd.sh 与 git diff --check 通过。主 CI 已接入这组实际 Shell 校验及主 workflow lint。新增 [平台自身流水线说明](platform-workflow.md) 并从 README 链接，明确 Environment/派生密钥、原构建恢复、人工确认、90 天凭证及平台升级边界。

这只是本地代码与合成协议验收，未 push、dispatch、扩大 GitHub 权限或创建新的部署；旧演示仓库的成功不能替代新主 workflow 的远端验证。完整新用户 UI 接入、实际主机重启、故障版本发布/自动恢复等仍未完成，故障发布仍须用户明确授权。稳定版目标保持 active。

随后针对修改后的源码重新执行 Trivy fs（vuln/secret/misconfig、包含开发依赖、HIGH/CRITICAL、exit-code=1），10:07:48 UTC 退出 0；`/tmp/devpilot-full-security.S7m5D7/source-after-workflow.json` 的七个报告目标合计发现 0。Maven 解析警告仍由同轮前述已解析 BOM 扫描补充；本轮没有再变动 Maven 依赖。报告生成后只补充本段验收文档，未宣称远端 CI 已通过。

## 2026-09-06：构建上报确认与丢失回调恢复的 Shell 验证

主 workflow 和 GitHub 生成模板的普通构建上报此前仅依赖 HTTP 成功。本轮增加业务响应校验：code=0、externalRunId/commit 对应；终态上报要求状态一致，成功时还要求同一完整 digest 及 PASSED 的测试/安全结果。开始回调的迟到重放可接受同一构建的已知终态，不要求服务端回退 RUNNING。恢复构建的响应也增加 code=0，仍要求原 build Run/commit/digest 和 AWAITING_APPROVAL，不能将恢复记录当成部署操作。未修改后端已有的终态幂等保护。

扩展 `scripts/test-platform-workflow.mjs` 到 84 个真实 Shell 片段用例，退出 0。新增覆盖：恢复来源和五门禁、错误 attempt 产物在发送前拒绝、恢复 payload 不含发布确认字段、HTTP 200 内业务错误、非 JSON 网关响应、错误 Run/commit/digest/状态、失败与取消映射、迟到开始回调，以及八种生成模板（四运行时 × Preview 开关）的成功和拒绝路径。curl/gh 都由测试内部函数替代，使用合成响应与密钥，无真实 HTTP 回调或 GitHub dispatch；只有同一测试新建的临时合成文件夹被自动清理。该项证明脚本协议判断，不证明真实丢失回调已由远端恢复或观察器补偿成功。

完整 Web 测试最初暴露三项失败：两个权限策略断言仍假设旧四任务/四 checkout，另一个恢复测试的成功响应缺少新要求的 code 字段。更新为当前七任务的明确权限白名单：仅镜像任务 packages:write，构建上报 permissions:{}，恢复 actions:read，发布 contents/actions:read；发布无 checkout 或重建。合成响应补 code=0，并增加非零业务码必须拒绝的用例，没有删除或降低权限门禁。复测 37 项全部通过，type-check 和生产 build 均退出 0。

主 workflow 与八种生成模板 actionlint 1.7.7 通过；verify-cicd.sh 与 git diff --check 通过。此轮未改依赖、未重跑完整镜像/源码安全扫描；前节安全结果保留其时间和范围，不自动扩大到本次新代码。新增构建结果已在本地 dist，尚未重建运行中 Web 镜像、推送仓库或更新既有业务 workflow。因此现有 v4 未因本次调整而重新发布，稳定版验收仍未完成。

## 2026-09-06：初始化状态不再信任离线或超前时间

检查首次使用页面发现 PlatformSetupService 只比较心跳下界，未核对 ONLINE，也未限制未来时间；Dokploy 15 分钟验证记录同样只有下界。先添加两项接口测试，旧实现两项均失败：明确 OFFLINE 仍返回 VERIFIED、未来十分钟的平台验证仍返回 VERIFIED，确认为可复现问题。

修正 GET /api/setup 的纯读判断：仅 ONLINE 且在 Agent 配置的 heartbeatTimeout 内、未超过当前时间 30 秒的心跳为 VERIFIED；OFFLINE 或过期为 FAILED，未知连接状态为 SAVED_UNVERIFIED，无心跳为 NOT_CONFIGURED，明显超前时间为 MANUAL_REQUIRED。Dokploy 验证同样增加未来 30 秒限制，旧错误仍优先显示 FAILED，超过 15 分钟仍显示未验证。一次查询使用相同 observedAt；不改变数据库、凭据、部署目标或触发平台请求。

接口测试覆盖当前/允许的小幅超前心跳、离线、过期、未来十分钟、未知连接状态、缺失心跳，以及平台验证未来/过期/当前时间；同时断言初始化 revision 未改变、不会返回 Key、只读查询没有调用 Provider 客户端。完整 CicdIntegrationTests 42 项全部通过，0 失败/错误/跳过，H2 集成测试约 23 秒；没有宣称真实 Linux 网络故障验收通过。

初始化页增加时钟异常及有效心跳的明确说明。新增 `scripts/test-setup-status-ui.cjs`，读取真实生产 dist，并对所有网络请求拦截后返回合成数据；不启动服务器、不连接真实环境、不使用真实令牌。验证五种 Agent 状态、平台与 Agent 时钟提示、Key 输入空白、刷新无保存/验证/创建/部署请求、无运行时错误，以及 390px 手机宽度无横向溢出。首次运行因定位匹配到外层 section、其次因未模拟布局公共设置和告警摘要请求而失败；将定位收窄至向导直接子 section、补齐真实布局读取接口后通过，未允许外网请求来掩盖失败。

桌面与手机截图位于 `/var/folders/0k/bg28r22s5m53w39tn9b5krz80000gn/T/devpilot-setup-status-ui-x7sNyY`，已逐张查看，提示可见、布局正常，截图均为合成信息。Web type-check 和生产 build 退出 0。本轮没有数据库迁移、依赖修改、运行镜像替换、真实业务发布或 GitHub 推送；运行中的平台尚未包含此修复。新用户完整真实接入、主机重启及故障自动恢复等验收依然未完成，稳定版目标继续保持 active。

## 2026-09-06：近期修复已升级到隔离恢复环境

构建 linux/arm64 候选 `devpilot/server:stability-setup-v37` 和 `devpilot/web:stability-setup-v37`，Server 在 Java 21 构建阶段打包，Web 包含更新后的初始化说明、回调响应校验生成模板及原有双架构 Agent 下载文件。归档/报告保留于 `/tmp/devpilot-setup-v37-validation.HqYsbr`。Trivy 0.65.0 image/scanners=vuln/HIGH,CRITICAL/exit-code=1 对两个精确归档均退出 0、发现 0，分别覆盖 Server 的 Alpine/Java 及 Web 的 Alpine/两个 Go binary；未忽略未修复项。Alpine EOL 列表警告仍存在，该扫描不是完整源码/密钥/IaC 门禁，也不等于远端 Actions 通过。

扫描 config ID 与导入及实际运行镜像一致：Server `91e62c15113f6c1836e8711359298aea8e64df020e79ef0359e24554983fb302`，Web `841b65960176edfafe5ef762a06a901509452a62eafcd7555666a22576a595ab`。仅推入 devpilot-stability-restore-lab 内的 127.0.0.1:15000 Registry，未推 GHCR；固定运行引用为：

- Server：`127.0.0.1:15000/devpilot/server@sha256:06c904c4bc9f9a418cec9dfe1ffdbca71f19be465681b764a3bcaee24c2db192`
- Web：`127.0.0.1:15000/devpilot/web@sha256:5a18c41885efedb3ccf9b0c8c6c3789cfe9ae1b2d63d79105c48aff10e82dd2a`

分别从旧 Flyway 候选和新 Server 的实际 JAR 读取迁移条目，串联 SHA256 同为 `7b707bd6d1dc7fe567bc2379cec19df620357913e143110a6a7c34c60c2a1e18`；直接按文件名字典序串联仓库 SQL 的摘要为另一值，因为排序不同，未将两种计算顺序混作迁移改变。无新增迁移，未执行 repair/baseline。

通过正式 upgrade.sh 升级隔离恢复环境，退出 0；预升级备份 `/opt/restore-lab-setup-v37-upgrade-backups/devpilot-20260906T102726Z-1qKda8.tar.gz` 和 sidecar 校验 OK，`.upgrade-report.9aKQ2Z/report.json` 备份状态上报 accepted。备份含恢复所需秘密，保留私有，不公开；accepted 只表示报告被接受，不代表再次独立恢复过这份新备份。新 Server/Web/gateway 健康。真实 MySQL 迁移历史升级前后均 37/37，摘要仍为 `4949c27fb7e55eb15dcd5270c30133860509463a1fda0d83454294c6160b020b`。

升级后三组 verifier 通过：管理员登录、初始化配置/服务器选择、应用环境 revision 与公开值解密/秘密脱敏，原发布确认人/时间/digest 和幂等/撤销历史保留，观察器配置保留且无新增部署。发布确认 verifier 包含受控签名回放，不称为纯读；观察器验证仍是合成凭据存在性，不证明 GitHub 认证。另启用 REQUIRE_AGENT=true 的 persistence verifier 通过，Agent ONLINE 且至少五个实际容器快照。

运行时 UI 验证没有使用本地 dist 替代：从已升级 Web 容器获取资源，读取原业务失败历史和恢复环境回调证据，确认移动布局、日志读取与新初始化提示。增加 `/setup` 页面检查，Key 输入保持空白；截图目录 `/var/folders/0k/bg28r22s5m53w39tn9b5krz80000gn/T/devpilot-release-evidence-ui-BcQPUH`，实际初始化截图已查看，当前 Agent 有新鲜心跳、合成 Dokploy 配置显示已保存未验证，没有伪装成真实平台已连通。

业务源环境不升级，v4 在 10:28:41 UTC 复查仍 HEALTHY、容器 `3b18d1920a1153dc1ca6bfd737a0db33959888ef34f94ba488800de07a0fda3f` 和已批准 digest 一致。未故障发布、未重启主机、未改变 Registry 凭据或仓库权限。至此近期修复已在隔离恢复环境运行，但未推送主仓库、未更新业务 workflow，完整新用户接入、真实主机重启及故障自动恢复等验收仍缺失，稳定版总体目标保持进行中。

## 2026-09-06：应用创建响应丢失的恢复缺口（V38 候选）

继续检查接入入口，确认此前仅在创建应用响应到达后保存 applicationId；若服务端已创建但响应丢失，重试只能得到“应用编码已存在”，前端无法恢复原结果。新增可选 UUID requestId 和 V38 `application_creation_request` 表，按认证用户及请求 ID 去重，记录原参数哈希与应用 ID，不记录令牌或业务变量。同一用户行锁序列化首次/并发请求，创建应用及保存映射在同一事务中完成。相同参数返回原应用，参数变化或原应用删除后拒绝重建；没有 requestId 的旧请求行为不变。

ApplicationIntegrationTests 增加精确重放（UUID 大小写等价）、参数改变拒绝、原应用删除后拒绝、四个并发请求只创建一条、跨用户隔离和无效 UUID 拒绝的测试。完整后端回归退出 0：118 项，117 通过、1 项 opt-in Dokploy live 测试按默认跳过，0 失败/错误；本轮测试使用 H2，V38 在 H2 验证迁移，不代表真实 MySQL 升级已验收。运行中的恢复环境仍为上一节 V37，未改其数据库。

接入页在创建前按用户保存 UUID 与非密钥应用参数，刷新后恢复名称、编码、服务器及健康地址，提示重新授权核对同一项目。原请求参数变化或存储损坏/不可用时拒绝新建；仓库 Token、平台 Key、Registry 密码和业务变量均不保存。接口返回应用 ID 后先保存 URL 查询参数，再清除浏览器创建恢复记录并提交接入任务。为不支持安全 UUID 的非 HTTPS/非 localhost 浏览器提供明确提示，不使用弱随机标识降级。

新增两个浏览器存储单元测试，完整 Web 测试 39 项通过，type-check 与生产 build 通过。新增 `scripts/test-onboarding-creation-ui.cjs`，运行真实生产前端、完全拦截 API：丢弃第一次应用创建响应，刷新后重填授权，确认第二次 payload 与 requestId 完全一致、只创建一次接入任务、applicationId 先进入 URL、Token 不在 localStorage、成功恢复后清除临时创建记录。模拟预检随后主动停止，未创建真实外部资源。首轮测试使用不安全的虚构 HTTP 域名，UUID 不可用而未真正发出创建请求，导致等待恢复提示超时；改为受支持的 localhost 安全上下文并额外断言第一次确实发到创建 API 后测试通过，没有将之前错误计为恢复成功。

新增 V38 功能仍需真实 MySQL 迁移、升级/重启/独立恢复后幂等重放、真实新用户接入等验证。去重表删除应用后仍保留，必须随备份保留；不承诺无状态旧浏览器或手工清理恢复记录后仍能自动找回。此轮未构建/替换运行镜像、未推送 GitHub/GHCR、未业务发布，当前稳定版目标仍未完成。

## 2026-09-06：V38 真实 MySQL 升级及服务重启后重放

构建 `devpilot/server:stability-creation-v38` 和 `devpilot/web:stability-creation-v38`，linux/arm64。归档与 Trivy 0.65.0 image/vuln/HIGH,CRITICAL 报告在 `/tmp/devpilot-v38-validation.RQQCSt`；两个扫描均退出 0、发现 0，仍有 Alpine EOL 列表警告，未关闭门禁或忽略未修复项，也不宣称完整源码/密钥扫描已重跑。扫描、导入及实际运行的 config ID 分别为 Server `cd7c19e102913b2d9602a801c19e32cfdf64af2e85ddd98a4bdfd890e725dba2`、Web `37ecfddd3a787d649863b5c5c69fce10033586eab4666f507bf1a74412ad3b86`。

仅将镜像推入隔离恢复环境内部 Registry。当前运行引用：

- `127.0.0.1:15000/devpilot/server@sha256:24949bd0b0b4df8cae66a773235010723c5670686b4987f35e6a32e8bdfd84ae`
- `127.0.0.1:15000/devpilot/web@sha256:8120e14b3383538e44af52191ce7c7f3db1beec356345d469f70422f9495ef89`

正式 upgrade.sh 成功，预升级备份 `/opt/restore-lab-creation-v38-upgrade-backups/devpilot-20260906T104243Z-GmzmIN.tar.gz`，状态报告 `.upgrade-report.2KVXVv/report.json` accepted。MySQL 的 V38 记录 success=1；按前 37 项 migration rank/version/checksum/success 计算的摘要仍为 `4949c27fb7e55eb15dcd5270c30133860509463a1fda0d83454294c6160b020b`。未修改旧迁移或执行 repair/baseline，V38 只增加去重表。

新增 `scripts/test-application-creation-api.sh`，限定显式隔离 loopback 地址及已有私有验收身份，seed 只创建一个 TEST 环境、无健康 URL/容器绑定的元数据应用，不创建部署配置或调用 Provider。证据目录 `/opt/stability-v38-creation-evidence`：四个真实并发 POST 返回相同应用 ID，随后精确重放仍返回原 ID，列表中同编码仅一个；改变名称返回 409/40978。MySQL 去重表 count=1，记录字段摘要 `6495acec2bbd2b2b1942930ece74ae3dc2b408d1ceaf9c69eee68876c62952ed`。

创建含 V38 数据的正式备份 `/opt/stability-v38-creation-backups/devpilot-20260906T104355Z-XToPFi.tar.gz`，sidecar 校验 OK；通过流式读取数据库 dump、仅检查 INSERT 存在性，确认备份包含去重表记录，未输出 SQL 或秘密。该备份尚未在独立环境恢复，不将“包含记录”当作恢复成功。

随后只重启隔离环境 Server/Web/gateway 容器，Server StartedAt 从 10:42:43 更新到 10:44:11 UTC，Compose 健康等待通过；不是 Docker 外层/宿主内核重启。verify 重放再次返回同一应用，去重表 count/hash 与重启前一致。管理员登录、加密环境 revision/值解密/秘密脱敏、初始化 revision、Agent ONLINE/至少五个真实容器、原发布确认及观察器持久化 verifier 全部通过；确认 verifier 包含受控签名回放，观察器仍只验证合成凭据存在性。运行时 Web 历史/日志/手机布局/初始化检查通过，截图目录 `/var/folders/0k/bg28r22s5m53w39tn9b5krz80000gn/T/devpilot-release-evidence-ui-NCOrhh`；本轮未把旧合成浏览器测试当成完整真实接入。

业务 v4 的只读复查发现容器已变化，不能声称完全未变：新容器 `bb724790903b386e8dbcb0e577715c2bde720b7687f2ec66613ef723b2b4ee01` 于 10:41:18 UTC 启动，仍是已批准的 v4 digest，10:45:16 健康，DevPilot 已重新关联。Dokploy Swarm 的服务 UpdatedAt 仍为 07:37:59、ForceUpdate=6，未发生本轮新的服务发布配置更新。旧容器 3b18d... 的 Health Log 显示 10:40:43–10:41:13 一次超时（配置 timeout 3s、实际结束明显延后），随后 Swarm 建新 task；旧容器 exit=137、OOMKilled=false，服务任务无额外错误。健康超时触发替换有直接证据，是否由本地同时构建资源争用造成尚未证实，不把时间重合当成因果。本轮没有对该应用发送发布/回滚；后续本地镜像构建改为串行，保留原健康门限，不靠放宽门限让验收变绿。此事件证明同 digest 的运行容器替换与重关联，不证明故障版本发布后的自动回滚已验收。

当前恢复环境已为 V38；业务源仍 V36，rootless 实验环境版本未自动同步。主仓库/GHCR 未推送、主机重启与 V38 独立恢复、新用户真实完整接入、故障发布/回滚仍待完成，目标保持 active。

## 2026-09-06：V38 在空 rootless 环境的独立恢复

已验证用户更换后的全局 GHCR 凭据：GitHub user API 200、预期账号匹配、classic PAT 仅 read:packages；Registry token、v4 原始 digest 及对应 commit tag 的 manifest 均 200，包含 linux/amd64 和 linux/arm64。该检查没有写入部署配置或触发发布，不等于验证应用内另存的凭据已同步。

本机 Docker 分配约 7.75 GiB，多个测试实例并存。先确认旧 `devpilot-rootless-tmpfs-candidate` AutoRemove=false 后停止并保留其数据，释放约 1 GiB；未停止业务或其他项目。首次直接执行 `scripts/start-rootless-install-lab.sh devpilot-rootless-v38-recovery` 的新建分支成功：独立 rootless Docker、2 CPU/2 GiB、临时 /run/user/1000、无外部网络、无宿主发布端口、无宿主 Docker socket、仓库只读挂载、AutoRemove=false。导入前 containers 和 volumes 均为空；rootless 数据卷为 `ededfb271ec1b3d51719bd1dcbb1d369ada4133314b97007bc6a3ed8befa8d4e`，未挂载任何原数据库卷。此模型仍是 privileged 本地嵌套 Docker，不代表真实主机隔离或系统重启验收。

离线导入已扫描 V38 镜像与 MySQL/Redis/Nginx/Registry，在新环境 loopback Registry 推入的 Server/Web digest 与上一节完全一致。仅复制安装配置、备份及私有验证身份/请求证据；通过 namespace wrapper 保持 .env 0600、身份目录 0700。启动 MySQL/Redis/Web 后，目标数据库表数确认为 0。随后执行正式 restore.sh，使用 `/home/rootless/stability-v38-creation-backups/devpilot-20260906T104355Z-XToPFi.tar.gz` 与原主密钥，校验 sidecar 并成功恢复，五个 Compose 服务健康。没有使用源数据库卷、重新 seed 或修改数据库记录来制造通过结果。

恢复后 MySQL V38 success=1；应用创建去重表 count=1，字段摘要仍为 `6495acec2bbd2b2b1942930ece74ae3dc2b408d1ceaf9c69eee68876c62952ed`，API 重放前后均一致。原请求重放返回备份前同一 applicationId、同编码只有一个应用，修改名称被 40978 拒绝。管理员登录、原服务器身份、环境 revision/公开值解密/秘密脱敏、初始化配置、发布确认人/时间/digest/幂等及撤销历史均通过。发布确认 verifier 包含受控签名回放但零 Provider 部署；观察器 verifier 只证明合成凭据存在及 revision/expiry 保留，不证明真实 GitHub 认证。

从实际恢复后的 Web 下载 arm64 Agent 并通过其 SHA256SUMS 校验，使用原注册配置手动启动。10:56:07 UTC 原 serverId ONLINE；进一步逐一比对目标 Docker 的全部六个实际容器完整 ID 与 API 快照，全部匹配，排除只读取备份旧快照而误判 Agent 恢复。Agent 是手动进程，本轮不声称系统服务自启动通过。

私有配置、原备份和最新验证证据已导出至 `/tmp/devpilot-v38-recovery.zc8RvH/private-recovery-evidence.tar.gz`（私有目录/文件，含秘密，不公开），归档列表可读。验证后停止新恢复测试环境并保留容器/数据，减少本机资源占用。业务 v4 于 10:56:06 UTC 仍健康，容器仍为 bb724790...，digest 与人工确认一致。本轮未推 GitHub/GHCR、未发布故障版本；V38 独立恢复缺口已补齐，但真实主机重启、完整新项目 UI 接入、故障发布及自动恢复等尚未完成，整体目标继续 active。

## 2026-09-06：V38 接入页的真实只读识别

新增 opt-in `scripts/test-onboarding-inspection-live.cjs`，浏览器使用运行中恢复环境的 Web 与 API，经 Docker curl 桥接访问本地隔离实例，不用本地 dist 或合成 GitHub/Dokploy 响应。脚本只允许 GET/HEAD、认证 POST 和 `/cicd/onboarding/inspect` POST；创建应用/任务、推进、Secrets、发布等请求均禁止。仓库授权从已登录 gh 的内存结果读取，Dokploy Key 从显式私有文件读取，不写入报告或打印上游错误，截图屏蔽所有密码输入框。

为只读识别临时连接恢复环境至既有 devpilot-v36-e2e 网络；检查结束已断开，未改变业务网络。11:00:54 UTC 对现有私有仓库 miaomeng1/devpilot-e2e-demo 的真实检查返回 HTTP 200/code 0：默认分支 main、NODE 测试类型、两个可读项目环境和一个 Dokploy 本机目标。这里只读取已有仓库，不为它新建应用或覆盖流水线。首次脚本因两个字段定位使用了不匹配的 label 而超时退出 1，修正为实际“平台地址”和“部署平台 API Key”后重跑通过，没有修改生产 UI 来迎合测试。

确认 Dokploy 配额和配置变更两项均未勾选、创建按钮禁用、真实仓库 Token 和 Key 不在 localStorage、页面无运行时错误、390px 无横向溢出。报告与桌面/手机截图在 `/var/folders/0k/bg28r22s5m53w39tn9b5krz80000gn/T/devpilot-onboarding-live-inspection-maPsNd`，两张截图已查看。node --check 和 git diff --check 通过。报告中唯一非认证 POST 是 inspect；这仅证明识别流程，不证明写权限、目标 Agent 对齐、端口空闲、回调可达、创建资源、PR 合并或部署成功。

完整新项目验收接下来需要专用测试仓库/应用，避免覆盖健康 v4 的配置，并需管理员核对当前 Dokploy Key 配额与有效期。现有恢复环境 Agent 不是业务 Dokploy 主机的 Agent，不能直接勾选成已对齐；应为新测试接入正确的目标 Agent。Linux 内核重启工具 limactl/multipass/qemu-system-aarch64/tart 当前未找到，容器重启不会替代该项。上述范围未扩大为故障版本发布授权，整体目标保持 active。

## 2026-09-06：用户文档与实际实现对齐

检查完整 automatic-onboarding.md 及 github-observation.md，发现历史开发说明与后续实现/验收混杂：仍称人工确认“已开始实现”、需要另补身份来源、GitLab 尚未传入确认 ID、V36 尚未部署。逐项核对 ManualReleaseApprovalService、CicdService、workflowTemplates.ts、GithubObserverSettings 及既有验收记录；另只读查询 GitHub run 34017861081/34019657199，均 completed/success，事件分别 push/workflow_dispatch，commit 仍为 01607503...。GitHub 绿色不单独作为部署健康证据，未借此次查询触发重跑或发布。

接入文档新增日期明确的验证范围，区分已有人工修复的演示链路、真实页面只读识别、V38 去重恢复与仍未完成的全新 UI 接入；补齐人工确认 API 的 expectedFingerprint，明确在 DevPilot 哪里确认、GitHub release 才需要确认 ID，并纠正 GitLab 已有传递实现但未真实验收的状态。保留旧 workflow 必须人工更新、无法绕过门禁、不要把构建状态当上线成功的限制。观察器文档不再声称 V36 未部署，明确真实幂等补报不等于丢失回调恢复；区分单应用与主仓库三组件 workflow，避免混用 Run ID。

本轮只改用户文档，未改后端/Web/Agent 或运行配置，未重建镜像、创建测试资源、写 Secrets 或发布。两个文档的本地 Markdown 链接目标均存在，git diff --check 与 verify-cicd.sh 通过；未重跑全部单元测试或安全扫描，也未将文档更新当成验收场景 13 已通过。专用测试资源及管理员配额确认仍待用户回复，整体目标保持 active。

## 2026-09-06：获准后创建专用私有接入仓库

用户回复“允许”，按紧邻确认范围推进专用私有测试仓库、Dokploy 测试应用及管理员配额确认，不扩张为生产发布、故障发布或 Mac 重启授权。只读清点账号现有仓库并检查候选名称不存在后，创建 `miaomeng1/devpilot-onboarding-acceptance`，远端 isPrivate=true、默认分支 main。本地专用目录 `/tmp/devpilot-onboarding-v38.73Ivxw/repo`，只提交七个明确列出的业务文件，没有从旧项目复制 .github、凭据或其他配置。

初始服务版本 onboarding-v1，Node 单服务、无第三方 npm 依赖、无数据库/数据卷，容器以 node 用户运行，暴露 8080、/health 健康接口；没有故障注入配置。npm ci --ignore-scripts 和 npm test 成功，/、/health、404 三项测试通过；npm 的零漏洞结果不是镜像或完整安全扫描。远端 workflows.total_count=0、运行列表为空，确认尚无 CI，避免沿用旧 workflow 伪装自动接入成功。

临时将恢复环境重新连到 devpilot-v36-e2e 网络，以便真实后端读取 GitHub/Dokploy。11:55:38 UTC 的 V38 运行页面检查成功识别新仓库 main/NODE、两个项目环境和一个 Dokploy 目标，报告在 `/var/folders/0k/bg28r22s5m53w39tn9b5krz80000gn/T/devpilot-onboarding-live-inspection-4q006i`；此次仍为 inspect 模式，未勾选创建按钮，没有创建部署应用、接入任务、Secrets 或 PR。新仓库已实际创建，但完整接入尚未完成。后续必须为候选平台接入 Dokploy 主机的正确 Agent，并准备该测试应用的回调路径；不能用恢复主机 Agent 代替业务主机，也不影响原 v4。

## 2026-09-06：全新项目经运行中 V38 页面完成五步配置

用户继续授权后，新增 opt-in test-server-registration-live.cjs，通过运行中 V38 的服务器页面创建 onboarding-v38-dokploy（ID 2096573985233117185），未直接插库或调用创建 API 代替页面；请求前保留私有浏览器恢复状态及 requestId，返回凭据保存 0600 文件，不打印密钥。默认安装路径不覆盖原 Agent，在 Dokploy 主机独立目录 `/opt/devpilot-onboarding-v38` 下载候选 Web 的 arm64 Agent 并核对 SHA256SUMS，使用新身份手动启动。目标容器无 systemd，因此这不是正式 install-agent.sh 或主机自启动验收。12:25:02 UTC 新服务器 ONLINE；原 v4 Agent 不替换、不轮换。

新应用使用独立 callback-only gateway（host loopback 18187，进程工具 session 63999）及临时隧道（session 69397），根地址 `https://date-reprints-alcohol-jewellery.trycloudflare.com`。仅允许 onboarding-acceptance 的签名发布/构建回调，后端验证 HMAC；健康路径 200、管理 API 404。旧 v4 的 18186 gateway/隧道不改。临时入口必须在验收结束后清理；进程存在不保证将来可达，首次 CI 签名回调仍需验证。

新增 opt-in test-onboarding-configuration-live.cjs，通过运行中真实 UI/后端完成配置，桥接只允许认证、inspect、一次指定应用创建及该应用接入/advance，不允许发布或审批 API。真实仓库 Token 来自 gh 已登录身份；私有拉取凭据从用户已配置的全局 Registry 读取，留在内存中提交到向导，不输出密码。首次两次在服务器 select 的精确 label 定位处停止，未创建应用，读回应用列表确认匹配编码为 0；修正自动化选择器后完成，不修改产品 UI 迎合脚本。

任务 ID `3c911956-41fc-4eba-b3f7-282fd83ac787`，应用 ID `2096575545925246978`，编码 onboarding-acceptance，stage=5/AWAITING_MERGE。实际新建 Dokploy 应用 `gAwb9bLIeKQvZd-kkvF26`，runtime key `dp-onboarding-acceptance-3c911956-6kpniu`。只读 application.one 验证拉取凭据存在、registryId=null、镜像 pending-first-release、TCP 18089→8080。没有新 Swarm 业务服务，未部署 placeholder。GitHub 独立 build-status-onboarding-acceptance 环境仅有 BUILD_CALLBACK_URL/SECRET，production-onboarding-acceptance 环境仅有 CICD_CALLBACK_URL/SECRET，前缀均 DEVPILOT_。

向导自动生成 https://github.com/miaomeng1/devpilot-onboarding-acceptance/pull/1 ，仅新增 `.github/workflows/devpilot-onboarding-acceptance.yml`（355 行），未预置或手工提交 workflow。页面五步全部完成截图 `/tmp/devpilot-onboarding-v38.73Ivxw/onboarding-result.png` 已查看；应用/任务响应与创建恢复信息保存在同一私有目录。PR 自动触发真实 pull_request run 34033077712，测试和安全检查已通过，镜像检查当时仍在运行，production 跳过；未自动合并、未生成发布确认。

12:26:56 UTC 原 v4 仍 HEALTHY，容器 bb724790...、批准 digest 和确认绑定均一致。语法检查与 git diff --check 通过。本轮证明从无 CI 私有仓库经页面到部署配置/Secrets/PR 的真实链路，不等于首次 push 镜像发布、签名回调或上线已完成。下一步等待 PR 检查并审阅合并；生产发布仍单独人工确认。

随后 run 34033077712 completed/success：quality 8s、security 25s、image 56s；PR 模式下 immutable release evidence/upload artifact、build callbacks 和 production 均跳过。不能将 PR 构建检查通过称为镜像已推送 GHCR 或回调已连通。Runner 提示 Docker actions 的 Node 20 runtime 被强制使用 Node 24，为后续依赖维护项，未关闭门禁。PR 尚未合并，需要审阅确认后才能触发默认分支 push 链路。

## 2026-09-06：新项目首次 push 到私有双架构镜像及待人工发布

用户明确允许合并 PR #1 后，重新读取全部 diff、确认仅新增向导生成的 workflow、head=07c3dcc2479aed0d54143ecf381bc6340e915508、CLEAN，并核对临时 callback health=200。使用 match-head-commit 合并，merge commit `6b5dcbb6d52a10443af687bbf9b135da062614e6`，时间 13:18:56 UTC。没有直接改写 main、手工写 Secrets 或派发 release。

更正前节关于 PR 镜像推送的表述：实际模板的同仓库 PR image 作业设置 push=true，跳过的是正式 release.json/回调/生产步骤，而不是镜像推送。前节没有核对包便不应暗示“PR 不推镜像”。本轮 GitHub packages API 已确认 devpilot-onboarding-acceptance visibility=private。PR 产生的镜像不具备这里要求的默认分支 push 发布凭证，不能代替首次 push 证据。

真实 push run `34035719296` completed/success：quality 7s、security 22s、image 53s、build_started 3s、build_finished 5s。production/recover_build 均跳过。开始回调曾在 V38 中形成 RUNNING/BUILDING，随后最终回调更新为 SUCCEEDED/AWAITING_APPROVAL。下载原 run attempt 1 的 `devpilot-release-onboarding-acceptance-1`，保留在 `/tmp/devpilot-onboarding-v38.73Ivxw/push-evidence/release.json`；逐项核对 repository、event=push、branch=main、commit、Run ID/attempt 及 image/digest 与真实平台唯一构建记录一致。测试/扫描均 PASSED；构建行 ID `2096588992977117186`，externalRunId `build:github-34035719296-1`。

原始镜像 `ghcr.io/miaomeng1/devpilot-onboarding-acceptance@sha256:ce61a0df42773851a81892164d2deaa65883dd96394fdfdbf1e39c102bf6a039`。使用已配置 read:packages classic PAT 请求 GHCR token 和该 digest 的 manifest 均 200，返回 digest 一致，包含 linux/amd64 与 linux/arm64（其他 unknown 条目不是业务架构）。这证明私有包及该凭据能读取镜像清单，不单独证明 Dokploy 已实际拉取并运行。此次 security 作业是 fs vuln/secret/misconfig 门禁，不宣称额外完整容器镜像漏洞扫描已经执行。

只读 verifier 确认该新应用审批数=0、部署数=0，保存 verified-push.json；读取版本绑定 approval-context 保存为私有证据，不提交审批。当前停在真实待人工发布；同一运行的成功开始/结束回调不是丢回调恢复场景。Runner 的旧 Action Node runtime 警告仍在，未关闭门禁。尚需用户确认将 onboarding-v1 发布到新测试应用的 18089 端口，不能沿用本次合并批准去执行发布或故障注入。

## 2026-09-06：用户确认后，onboarding-v1 首次发布健康

用户明确“发布”并继续后，通过运行中 V38 的人工确认面板选择构建 2096588992977117186，核对应用、服务器、commit、原始 digest 及配置 fingerprint，勾选并提交。确认 ID `5fbc01e1-2d57-4d94-a139-a1fb95a33e9a`，服务器记录 stability-admin 于 13:49:09 UTC 确认。请求及返回证据保存在私有目录 `/tmp/devpilot-onboarding-v38.73Ivxw`，approval-ui.png 已查看；没有绕过审批 API 或直接调用 Dokploy deploy。

派发 workflow operation=release、原 build_run_id=34035719296、上述确认 ID，产生 run `34037265942`。production 成功（6s），质量/构建等均跳过，读取原 attempt 制品并签名发送发布证据，未重新构建镜像。平台部署 ID `2096596684403712002` 从 TRIGGERED → VERIFYING → HEALTHY；没有把 GitHub success 或旧健康探测提前当成发布完成。

新实际容器 `a6b0bbf0996aec8ccd277733516c2fc86731d091e37d485206a3ff500892cc2c` running/healthy，镜像与批准原始 digest `sha256:ce61a0df42773851a81892164d2deaa65883dd96394fdfdbf1e39c102bf6a039` 完全一致。Dokploy 测试环境内部 `http://127.0.0.1:18089/health` 返回 `{status:ok,version:onboarding-v1}`；这是隔离环境内部发布端口，不是自动新增 Mac 主机端口映射。Agent 自动关联新容器，13:50:22 UTC 健康证据刷新后平台完成 HEALTHY。只读断言：部署恰好一条、部署/运行镜像等于原制品、应用 HEALTHY、确认 consumedByRunId=github-34037265942-1 全部通过，release-status.json 保留完整响应。

原 v4 容器 bb724790... 及批准 digest 不变，复查 HEALTHY。本次没有故障注入、自动回滚测试或主机重启，没有扩大权限或公开镜像。至此专用私有仓库从无 CI，经真实页面创建配置/Secrets/PR、人工合并、push 构建、人工确认、发布原 digest、容器自动关联及健康验证的成功路径已跑通；测试环境 Agent 为手动启动，临时回调隧道仍需最终清理，不能据此宣布个人自用稳定版全部完成。

## 2026-09-06：保存首次接入成功后的恢复基线

复查新应用仍 HEALTHY 后，执行正式 backup.sh，生成 `/opt/onboarding-v1-release-backups/devpilot-20260906T135137Z-kedXwh.tar.gz` 及 sidecar；校验 OK。仅流式检查 SQL INSERT 中的新应用、部署及审批标识存在，不输出数据库内容或凭据。备份 SHA256=`ce74930ee2dc21b65b4076c531bbfcc9467ee73182766a6e05b0f4313e78592f`。

在私有目录 `/tmp/devpilot-onboarding-v38.73Ivxw/release-backup` 保存 AES-256-CBC/PBKDF2（200000 iterations）加密副本 `devpilot-onboarding-v1.tar.gz.enc` 及本地 recovery.key，目录 0700、两文件 0600。流式解密后 SHA256 与原归档一致。密钥没有打印或提交；CBC 副本不是 AEAD，恢复前仍必须核对可信原始摘要和备份 sidecar。密钥与副本均仍在本机，因此不声称完成异机密钥保管或异机备份；此新快照尚未独立恢复，先前 V38 空库恢复证据不自动覆盖新增真实发布记录。

更新自动接入文档顶部验证范围，反映新仓库的成功路径，同时保留手动 Agent、临时隧道及故障恢复未验收限制。没有重启/停止业务、发布故障版本、开启自动回滚或修改已批准 digest。后续故障测试应限定在新专用应用，以 onboarding-v1 为恢复基线，并另行取得明确授权；原 v4 不作为故障目标。整体稳定版目标未完成。

## 2026-09-06：首次发布后的真实页面只读验收

通过 Playwright 将页面资源与 API 请求转发至实际 V38 验收实例（非 mock、非本地新构建），仅允许 GET/HEAD 与登录认证请求，禁止业务变更。CI/CD 页面选中新应用，部署行显示 HEALTHY，展开采集日志可读取真实发布日志。应用详情显示批准的原始 digest、ONLINE Agent、运行端口 `8080/tcp` 与 `Swarm ingress :18089→8080/tcp`、一条成功发布记录；关联容器详情链接跳转成功。两张截图已人工查看，证据保存于私有目录 `/tmp/devpilot-onboarding-v38.73Ivxw` 的 application-runtime-ui.png、release-evidence-ui.png 与 runtime-ui-evidence.json。

此项证明运行页面能够呈现发布证据及关联入口，不等于容器实时 WebSocket 日志已验收。18089 仍是 Dokploy 隔离环境内部端口，并未暴露为 Mac 端口；访问地址未配置，页面如实显示。未发布、重启、开启自动回滚或触发故障测试，后续故障测试仍待明确授权。只读状态复查中新应用保持 HEALTHY。

## 2026-09-06：当前工作树完整基础回归复查

在当前未提交工作树执行 `mvn -q test`，进程退出 0；Surefire 汇总 28 套件、118 项，失败/错误 0、跳过 1（显式启用的 Dokploy live 测试），即 117 项通过。前端 `npm test` 39 项通过，`npm run type-check` 与 `npm run build` 均退出 0。Agent 使用 `go test -count=1 ./...` 禁用测试缓存后通过；未启用需要显式授权的真实 Docker 控制/模板集成测试，不能据此宣称真实控制操作已覆盖。

`test-github-workflow-lint.mjs` 校验平台 workflow 及 NODE/JAVA/GO/DOCKER × preview 开关 8 种生成配置，通过 YAML/Actions 语义检查；外部 shellcheck/pyflakes 未执行。`test-platform-workflow.mjs` 的 84 个实际 shell 块本地夹具场景通过，包含构建来源、门禁完整性、原 digest、HMAC 和回调确认校验；未派发远端 workflow。

只读挂载源码、无网络、无宿主 Docker socket 的临时 Maven 容器内，`test-maintenance.sh` 和 `test-install-upgrade.sh` 均通过。二者使用模拟 Docker，仅证明脚本边界与失败处理，不取代真实安装、恢复、升级或主机重启证据。本轮未更改生产配置或发布应用，未完成新的依赖漏洞扫描，稳定版整体仍未完成；故障恢复真实验收仍待用户授权。

## 2026-09-06：当前工作树安全门禁复查

按平台 workflow 的同版本 Trivy 0.65.0 和同扫描参数执行本地复查，源码只读挂载，不挂载 Docker socket。`fs --scanners vuln,secret,misconfig --include-dev-deps --severity HIGH,CRITICAL --exit-code 1` 退出 0；报告包含 Go go.mod、Java pom.xml、Web package-lock.json 与四份 Dockerfile，HIGH/CRITICAL 漏洞、Secret findings 和失败的配置检查均为 0。另以 CycloneDX 2.9.1 重新生成包含测试作用域的 Java SBOM，134 个组件均有版本，确认包含 spring-security-test/mockito-core；`trivy sbom --scanners vuln --severity HIGH,CRITICAL --exit-code 1` 退出 0，报告漏洞 0。

本次未使用 skip-db-update、ignore-unfixed 或新增忽略规则；复用扫描器缓存，报告及日志保存在 `/tmp/devpilot-security-current.qhrpBk`。结果仅表示指定扫描器/漏洞库/严重性门槛下未发现相应问题，不覆盖所有漏洞、业务安全或新的运行镜像扫描，也不代表当前未提交的平台 workflow 已在远端运行。Trivy 提示存在新版本，未在验收中自动升级扫描工具。本轮无发布、权限扩大或业务变更。

## 2026-09-06：明确授权后的真实故障发布与自动回滚

用户答复“允许”，授权范围仅新测试应用开启自动回滚、发布故障版本，以及自动恢复失败时人工恢复 onboarding-v1；原 v4 不动。先复核应用 2096575545925246978、Dokploy 资源 gAwb9bLIeKQvZd-kkvF26、仓库与旧健康 digest，随后只修改 autoRollback=true，保留 productionApproval=true、健康期限 300 秒及原凭据，未轮换 Secrets。配置原值保存在私有证据目录 `/tmp/devpilot-fault-acceptance.haRnsY`。

专用测试仓库提交 `b994507ec79e2612b9e25899041548d0ee3a1a1e`，明确将 /health 改为 503，README 与测试注明此为故意失败的验收夹具；测试验证该故障行为，不声称它满足生产健康条件。SSH 推送连接失败后先确认远端 main 仍为旧提交，再通过 HTTPS 与既有 gh 凭据完成普通 push，未强推或修改账号权限。push run `34038096535` 的 quality/security/image/build callbacks 成功，production 跳过；私有包可见性不变，Registry manifest 校验 linux/amd64 与 linux/arm64 及 digest 一致。

故障镜像为 `ghcr.io/miaomeng1/devpilot-onboarding-acceptance@sha256:237f4247a8ccb329b23c5b93527e25c97b8ce09d8d3c827e50a1fd65352df545`。页面提交确认 `4d6112ef-87d8-4b80-a5e9-2cb9710cc4b7`，绑定原构建、commit、digest 和配置 fingerprint。提交后的 UI 验收检查报错，但已收到并保存成功 API 响应；只读查询确认记录真实存在且未使用，未重新提交。派发 release run `34038224252`，仅 production 成功，其余构建/扫描跳过；审批最终 consumedByRunId=github-34038224252-1，发布原始制品而非重建。

故障发布 `2096601461690802177` 于 14:08:36 UTC 开始，进入 VERIFYING。实际故障容器 `69f3f2b5f509563efc82f0f62df0bb720fd50e63b4436cd575eaae31179d32c5` 使用故障 digest，Docker Health 多次 ExitCode=1 并标为 unhealthy。Swarm 配置为 start-first / FailureAction=rollback，于 14:09:32 UTC 先完成自身回滚；旧健康版本仍服务时，DevPilot 没有将故障发布标成 HEALTHY。必须区分 Swarm 回滚与 DevPilot 回滚，不能把前者冒充后者。

达到原 300 秒期限后，DevPilot 于 14:13:42 UTC 自动创建回滚 `2096602746288676865`，rollbackOfId 指向故障发布，镜像为原 onboarding-v1 digest `sha256:ce61a0df42773851a81892164d2deaa65883dd96394fdfdbf1e39c102bf6a039`。随后回滚 TRIGGERED → VERIFYING → HEALTHY，故障发布与发布流水线均为 ROLLED_BACK。没有调用人工回滚 API，没有缩短验证期限。新实际容器 `0ec644fafabdf94603938f009fc4dd85e867c0a60edcf41af6f43c7f47b891a8` running/healthy，Config.Image 与原 digest 一致，Agent 健康时间晚于回滚开始；隔离环境内 18089/health 返回 ok/onboarding-v1。最终断言全部通过。

真实 V38 发布页面只读检查显示“发布失败 · 已回滚”及关联 HEALTHY 回滚，日志可展开；rollback-ui.png 已查看。timeline.json、build-run.json、release-run.json、swarm-rollback.json、fault-container-health.json、verified-recovery.json 保留证据。原 v4 复查容器 bb724790...、digest 和 HEALTHY 不变。本次没有数据库/业务卷恢复或主机重启；自动回滚保持用户授权后的开启状态。测试仓库 main 保留故障夹具，运行环境已恢复旧 digest，不能将该 main 当作健康业务模板。整体稳定版仍未完成，临时凭据与隧道清理、主机自启动、其他异常路径和正式版本交付仍需继续。

## 2026-09-06：真实应用到容器 WebSocket 日志链路

在回滚后的新测试应用上，使用真实 V38 页面从应用详情点击关联容器，创建日志票据并通过 WebSocket 订阅。为访问未发布主机端口的隔离平台，测试程序仅在 Mac loopback 的随机端口建立透明 TCP 转发，底层连接为 docker exec + nc 至实际网关 18081；页面与 WebSocket 数据均来自运行实例，不使用模拟响应。浏览器只允许读取、认证及该目标容器的日志票据 POST，禁止其他业务变更。

该测试应用没有常规 stdout 输出，因此先核对实际容器与恢复 digest，再通过该容器的 /proc/1/fd/1 写入一条无敏感信息的 INFO 验收标记，未修改应用文件、镜像或重启服务。浏览器收到带 Docker 时间戳的真实标记，页面截图已查看；暂停/恢复按钮操作完成，搜索后显示目标标记。此证据覆盖实时数据到达与这些基本操作，不外推为高吞吐、所有过滤组合或暂停期间持续日志缓存的完整验证。

再次使用同一 WebSocket 票据，连接以 1008 关闭，验证一次性票据不可重放。程序退出 0，关闭浏览器、TCP 代理及其 nc 子进程；未停止原回调隧道或 Agent。私有证据 `/tmp/devpilot-log-acceptance.gZbIlh/evidence.json` 和 logs-ui.png 保留结果，不保存票据或管理员明文；日志标记会随容器日志正常保留。复查应用仍 HEALTHY、恢复镜像及发布/回滚记录不变。本轮补齐此前“只有日志入口”的证据缺口，稳定版其他待验收事项不因此完成。

## 2026-09-06：包含真实故障与回滚记录的恢复候选备份

在当前健康 V38 实例执行正式 `/workspace/scripts/backup.sh --install-dir /opt/devpilot --backup-dir /opt/onboarding-rollback-backups`，生成 `devpilot-20260906T143823Z-ksId0Y.tar.gz` 及 sidecar，校验 OK，SHA256=`3cfdec7ae135161fb0ebe2f624f6bf0345124ab2f024ae1384224d2e185d85be`。仅在内存中检查 SQL 对应表的 INSERT，确认包含应用 2096575545925246978、故障发布 2096601461690802177、回滚 2096602746288676865 和审批 4d6112ef-87d8-4b80-a5e9-2cb9710cc4b7；未输出数据库内容或凭据。

首次尝试通过标准输入传入脚本，虽然进程退出 0，却未生成归档（嵌套交互式命令可能消耗标准输入）；未将退出码当成备份成功。随后改用环境中已有脚本文件执行，核实实际归档和表记录。备份目前保存在受保护的隔离环境目录，尚未导出为新的异机加密副本，也尚未独立恢复，不能复用旧快照恢复证据宣称覆盖新增记录。

已检查 rootless 验收镜像存在，预定新目标 devpilot-rootless-rollback-recovery 尚不存在。下一步拟使用新建、无外网、无主机端口和无宿主 Docker socket 的环境恢复，防止恢复后的后台任务调用 GitHub/Dokploy/通知端点。尚未创建目标或执行恢复，等待人工确认；不覆盖现有实例、旧恢复环境或其数据库。该嵌套容器环境仍不等于真实内核重启/systemd 自启动验收。

## 2026-09-06：修复流式执行备份时的假成功

针对上一轮无归档却退出 0 的现象，维护测试的 Docker 替身增加可选 stdin 消费行为，再通过 `bash -s < backup.sh` 执行。新增测试不仅检查退出码，还核对归档、sidecar 和压缩 SQL 内容；修复前稳定退出 1，复现脚本剩余内容被 Compose exec 读取后未生成最终备份的问题。

`backup.sh` 的 mysqldump 调用增加 `</dev/null`：数据库导出不读取客户端 stdin，避免吞掉流式脚本。SQL 导入仍保留自己的 stdin 管道，未修改 restore.sh。修复后 maintenance/install-upgrade 两套模拟测试均通过，bash -n 和 git diff --check 通过。

随后在真实 MySQL 的 V38 实例以同样流式方式执行修复后的脚本，实际生成 `/opt/backup-stdin-verification/devpilot-20260906T144036Z-bW9nnU.tar.gz` 及 sidecar，校验通过。该备份用于验证这次缺陷修复，不覆盖此前候选恢复归档；未停止业务或执行恢复。新独立恢复仍等待人工确认，不能由这次备份成功代替恢复验证。

## 2026-09-07：真实发布与自动回滚数据的独立离线恢复

用户明确“允许”后，通过正式 fixture 脚本新建 `devpilot-rootless-rollback-recovery`。创建时内层容器/卷均为空，外层 network=none、无主机端口、AutoRemove=false，不挂载宿主 Docker socket；独立 rootless daemon。只导入镜像与备份/必要配置，没有复制旧数据库卷。外层无路由；内层 rootless 的虚拟默认路由不代表具有外网，恢复全程未连接 GitHub、Dokploy 或现有业务。

候选归档 `devpilot-20260906T143823Z-ksId0Y.tar.gz` 在目标再次核验 SHA256=`3cfdec7ae135161fb0ebe2f624f6bf0345124ab2f024ae1384224d2e185d85be`。配置从该备份提取，保持主密钥且不打印内容；本地 loopback Registry 离线导入 Server/Web，最终 digest 分别为 24949bd0…d84ae / 8120e14b…5ef89，与备份引用完全一致。新建 mysql-data/redis-data 后，真实 SQL 查询目标 schema 表数为 0。

仅对新目标执行正式 `restore.sh --install-dir /home/rootless/devpilot --archive /home/rootless/recovery-input/onboarding-rollback-backups/devpilot-20260906T143823Z-ksId0Y.tar.gz --yes`，退出 0，五个业务组件 healthy；Flyway 38 条成功记录，最高版本 38。原管理员登录通过，7 个应用、3 个服务器记录保留。新应用 3 条部署记录、故障发布 ROLLED_BACK、关联回滚 HEALTHY、rollbackOfId、原 digest，以及审批 consumedByRunId=github-34038224252-1 均与备份基线一致。autoRollback、productionApproval 和加密凭据 configured 标记保留。既有持久化夹具的加密 PUBLIC_URL 正确解密，API_KEY 仍脱敏；此项证明该恢复平台主密钥可工作，不宣称离线验证了真实外部 Token 当前有效性。

从恢复后的 Web 下载并校验 Agent SHA256SUMS，使用已有合成服务器的注册配置指向恢复平台 loopback（不使用真实业务服务器的 Agent 身份）。在恢复环境内手动启动 Agent，ONLINE 与 6 个真实内层容器 ID 全部匹配。原业务服务器在恢复库里的历史健康信息不作为离线环境实时证据；没有连接其 Agent 或执行业务部署。

验证器及安全摘要保存在 `/tmp/devpilot-rollback-recovery.EHieUP`。必要配置、原备份及 Agent 材料导出为 `private-recovery-evidence.tar.gz.enc`，AES-256-CBC/PBKDF2 200000 次、独立 recovery.key，均 0600、目录 0700；流式解密抽取原备份摘要与可信值一致。CBC 不是 AEAD，仍需核对可信摘要；密钥和副本都在本机，不称为异机密钥管理或异机备份。验证完成后停止此新验收环境，保留容器与数据，不修改当前平台或业务。此次覆盖新增真实审批/失败发布/自动回滚记录的独立恢复，仍不是主机内核重启或 systemd 自启动验收，整体稳定版尚未完成。

## 2026-09-07：Agent 安装凭据文件权限窗口修复

检查 install-agent.sh 发现原实现直接写 CONFIG_PATH 后才 chmod 0600：初次创建会继承调用者 umask，覆盖原有 0644 文件也会在写入期间保留宽权限。新增隔离安装测试在实际写入开始前核对文件权限，修复前退出 88，复现该问题。

安装器现在设置 umask 077，在目标目录创建 0600 临时配置，完整写入后用 Linux mv -fT 原子替换；失败清理临时文件，不截断旧配置。隔离测试验证宽 umask 下首次写入、旧 0644 配置替换、模拟写入失败保留旧内容与临时文件清理，全部通过，bash -n/git diff --check 通过。新增测试接入 make maintenance-verify，只在显式启用的无网络临时容器运行，下载与 systemctl 为模拟，不修改运行 Agent 或重建平台镜像。真实 systemd 自启动/内核重启尚待专用虚拟机验收，虚拟机创建仍等用户确认。

## 2026-09-07：清理旧运行资源及创建专用 Linux 虚拟机

用户允许停止不用的 Docker / Java 并创建专用虚拟机。只停止旧 `devpilot-stability-lab`（AutoRemove=false，数据保留）以及 cwd 为本项目 devpilot-server 的两组遗留 Java/Maven 进程，复查四个 PID 均退出。当前 V38 恢复平台和 Dokploy 保留；SecEvolve 的两组数据库、Java，以及 pawbae 的八个 Supabase 容器尚待用户确认是否闲置。旧 `devpilot-rootless-install-candidate` 设置了 AutoRemove=true，停止可能自动删除容器及匿名卷，因此没有停止。未执行 prune，未删除镜像、容器或业务数据卷。

在私有目录 `/Users/miaomeng/.codex/devpilot-labs/host-reboot-20260907` 创建独立 Fusion 虚拟机文件，2 vCPU / 4096 MB / 30 GB 稀疏虚拟磁盘，NAT，无共享目录；不修改既有虚拟机，也不重启 Mac。Ubuntu 官方 24.04 ARM64 云镜像 SHA256=`afa139bac6f2629c1e1f2f8f34215f3a9ad9779801bcb945521ba1a45016743f` 与官方下载 SHA256SUMS 相符。使用临时 Debian 容器中的 qemu-utils 转换为 VMDK，临时转换容器完成后自动删除，仅含工具无业务数据。cloud-init 使用专用 SSH 公钥，禁用密码登录，私钥只保留本地 0600。

后台 start 曾返回成功，但随后 vmrun list 为 0，不能以命令退出码判定运行成功。已打开 Fusion 并请求界面方式启动；图形检查受 macOS 辅助功能/录屏权限未完成所阻，已请用户核对窗口提示。本条记录只证明文件创建和镜像校验，不代表干净 Linux 安装、systemd 自启动或内核重启通过。后续必须重新确认 VM 实际运行状态再继续验收。

## 2026-09-07：Linux VM 正式安装、systemd Agent 与真实内核重启通过

> 后续复查发现启动后的 NTP 大幅回拨：本节短时间检查不足以证明长期正常，原“通过”结论撤回；以下保留原始观测，修复与重新验收见后续记录。

用户确认 SecEvolve/pawbae 可停止且 VM 已运行。重新核对进程 cwd 后 TERM 停止 SecEvolve 的 Maven/Java；停止 `secevolve-postgres` 和八个 `supabase_*_pawbae-line-a` 容器。`secevolve-real-run-check` 为 AutoRemove=true，采用 pause 保留容器和匿名卷，并明确向用户说明是暂停而非停止；暂停不等于释放内存。没有删除这些容器、镜像或业务卷。原 V38 恢复平台和 Dokploy 保留运行。

Fusion 确认恰有一台运行 VM，路径为上述专用 vmx，IP 192.168.180.130；专用 SSH 密钥登录通过。Ubuntu aarch64、cloud-init done、systemd running，根分区自动扩至约 29 GiB。首次执行原样 `scripts/install.sh --offline`，脚本在无 Docker 的干净系统上安装 Ubuntu 发行版 Docker 29.1.3 / Compose 2.40.3，并正确因缺少离线镜像退出 1，未创建 /opt/devpilot。之后通过 Docker save/SSH load 导入本地 `devpilot/server:stability-creation-v38`、`devpilot/web:stability-creation-v38` 和三项基础镜像，再原样执行正式安装器，退出 0，五个服务 healthy，地址 `http://192.168.180.130:18080`。此项是本地候选镜像离线安装，不外推为正式发布标签在线拉取验证。

使用已有 `test-persistence-api.sh` 的 seed / seed-business / seed-setup 创建隔离验收管理员、服务器、应用、加密变量及合成部署平台凭据。未向 GitHub 或真实 Dokploy 发送凭据，也未部署业务。使用正式 install-agent.sh，从该 VM 平台的 downloads 下载 linux/arm64 二进制并核验 SHA256，安装真实 systemd service，退出 0。docker 和 devpilot-agent 均 enabled/active，Agent 配置为 root:600；在线状态及五个实际容器 ID 与 Docker 精确匹配。

仅在该 VM 内执行 `sudo systemctl reboot`，SSH 曾真实断开。内核 boot_id 从 `5591b65a-9776-4bc4-8a14-10ce54ebd6f5` 变为 `474c67cf-6006-4959-8c6b-96f5f3878bff`，boot timestamp 增加。没有手动启动 Docker/Agent/Compose；重连后两项 systemd 服务 active/enabled、五个原容器 healthy，MySQL/Redis 仍挂载命名持久卷。重启后再登录原管理员，服务器身份、应用环境 revision、非敏感加密变量解密、敏感变量脱敏、初始化设置和合成凭据 configured 状态均通过；Agent 心跳晚于新 boot timestamp，容器 ID 与重启前一致，.env 与 Agent 配置 SHA256 均未变化。

私有证据在 `/Users/miaomeng/.codex/devpilot-labs/host-reboot-20260907`：`before-reboot.json`、`after-reboot.json`、`verify-reboot.mjs` 和 persistence 子目录。后者包含验收身份，文件 0600、目录 0700，不提交仓库，不输出明文。该 VM 保持运行。此项补齐真实 Linux 内核重启和正式 Agent systemd 自启动；该新库尚不含之前实际审批/发布/回滚历史，因此不声称这些历史已在本 VM 内核重启中逐项验证。既有真实发布记录独立恢复证据仍见前文。回调丢失恢复、通知其他异常路径、正式版本交付等剩余目标不因此完成。

## 2026-09-07：延后复查揭示 RTC 回拨，撤回过早的重启通过结论

继续准备 Agent 离线通知真实测试时，登录 API 返回 code 50000。尚未创建告警规则/订阅或停止 Agent。读取后端异常发现 Snowflake `Clock moved backwards`，回拨量约 28,504,000 ms；系统日志显示 Ubuntu 从 09:18 跳到 01:18 UTC。Mac UTC 与 NTP 校正后的系统时间一致，但虚拟 RTC 仍是 09:xx，Linux 配置 RTC in local TZ=no。这说明此前仅在 NTP 完成前短时间验证的重启结果不足，已明确撤回而非继续声称通过。

仅对专用测试 VM 正常关机并设置 `rtc.diffFromUTC="0"`，但实际重新启动后仍有 8 小时偏差，因此没有把该配置写入等同于修复。随后停止测试后端、网关和 Agent，禁用 VMware Tools 的周期时间同步（保留 Linux NTP），从 Ubuntu 官方包安装 util-linux-extra 提供 hwclock；待 NTP 校正与 Mac UTC 一致后执行 `hwclock --systohc --utc`。再次内核重启，RTC 与系统 UTC 均一致。恢复该测试后端、网关、Agent 后原管理员登录、加密变量与初始化配置、Agent 和五个容器验证通过；没有修改业务数据库、重置密钥或删除数据。

强化私有 verify-reboot.mjs：重启前后均要求来宾 UTC 与 Mac UTC 偏差小于 5 秒，NTPSynchronized=yes；用 `utc-fixed-` 前缀保留独立基线，不覆盖原错误时钟阶段的证据。重新启动验收已开始，必须在首次 NTP 同步完成后再核对，不能只检查 systemd active 或 Docker healthy。部署文档增加 RTC/NTP 人工核对及回拨故障提示；当前安装器尚未强制同步检查，运行中大幅回拨的通用 ID 生成器容错也尚未实现。

重新验收于 01:27:39 UTC 完成：boot_id 从 `89aeee20-d536-49dc-b19b-1ff653223bf3` 变为 `b9509733-774f-4d35-a303-fe78f4347492`；首次 NTP 同步后前后时钟偏差分别约 0.764 / 0.853 秒。原管理员登录、加密配置、Agent 身份、两项 enabled/active 服务和五个相同 ID 的健康容器均通过，配置文件摘要一致。新的 `utc-fixed-before-reboot.json` / `utc-fixed-after-reboot.json` 才是修正后的主机重启证据；旧库中异常时间阶段写入的历史仍保留，不声称已被修复。通知端到端测试未完成，后续继续。

## 2026-09-07：真实 Agent 离线与恢复 Webhook、重复抑制

## 2026-09-07：真实应用持续不健康与恢复通知

在已升级的 Linux VM 新建独立 loopback HTTP 健康夹具和应用 `2096775428748468226`（vm-health-acceptance / TEST / recordedVersion=fixture-v1），由正式 Agent 的健康任务实际访问 127.0.0.1:18901/health。初始 HEALTHY 无告警；01:40:22 切为 503，01:40:26 的 Agent 检查为 UNHEALTHY，01:40:35 尚无告警，符合 durationSeconds=20 的等待行为。之后产生告警 `2096775656285265922`，真实 Webhook firing 事件 `0749daed-f7e9-478f-a3d2-da266b8f520f` 一次成功。

随后只停止测试 Agent；01:41:58 API 明确 OFFLINE，原故障仍 FIRING，通知仍只有一条，没有将缺失数据当作恢复。重启原 Agent 后收到新鲜 UNHEALTHY，未重复告警。01:42:31 将夹具恢复 200，01:42:34 的真实健康探测为 HEALTHY，01:42:37 同一事件 RESOLVED，恢复 Webhook `1c93d7b5-a1e4-4c08-b76c-6d36dd166fe6` 一次成功。继续观察后保持两条投递，接收端实际请求也只有两条；两者 HMAC 与 delivery ID 均有效，subject 指向同一 alert ID，applicationId/name、TEST 环境、fixture-v1 记录版本、/applications/{id} 和 reason 均断言通过。

本次健康服务是 VM 上独立 systemd Python HTTP 夹具，不是 Docker 应用镜像发布；没有直接写数据库或伪造 Agent 报告，也不声称验证了 runtime digest。私有证据目录中 app-observation-*.json、app-received.jsonl、app-verification.json 保存完整安全摘要。verify-app-alert.mjs 在清理前通过；清理后 app-status.json 已成为最终状态，不能直接重跑健康阶段验证器代替历史证据。禁用仅本次规则和订阅、清除测试应用健康检查 URL，然后停止临时 HTTP/接收服务；保留应用、告警、通知与审计记录，应用健康按无检查状态为 UNKNOWN。Agent active、五个平台组件 healthy，Registry 保持回环运行。发布/回滚通知和其他稳定版剩余项仍待继续。

### 前序 Agent 通知及候选升级记录

2026-09-07 告警/审计真实主机重启补验：在候选已升级、测试服务已清理的专用 VM，通过认证 API 保存固定审计时间范围的基线。再次只重启该 VM，boot_id 从 b9509733-774f-4d35-a303-fe78f4347492 变为 298c1b6e-b3eb-476c-9358-b186c88220f1。等待 NTP 同步后核对 UTC 与 Mac 偏差小于 5 秒；Docker/Agent 及五个平台组件自动恢复健康，未手动重启服务。两条规则与两个订阅完整 API 配置（均保持禁用）、两条已恢复告警的 ID/status/resolvedAt、四条投递 ID/eventId/status/attemptCount，以及固定范围 34 条审计记录的 ID/action/result/resourceId 均与基线逐项一致。未比较或输出审计请求正文，不能将该断言外推为全部敏感字段逐字校验。原管理员、服务器/Agent、加密变量解密/脱敏和初始化配置再验证通过。

私有证据为 verify-history-reboot.mjs、history-before.json、history-after.json。规则和订阅没有因重启重新启用，临时 HTTP 接收端和健康夹具没有重新创建。此项补齐此前新告警、通知与审计历史的内核重启证据；该 VM 不含原 Dokploy 实际发布/回滚历史，后者的独立恢复与主机重启证据范围仍须区分。故障发布人工确认尚未收到，未调用新的审批或部署。

2026-09-07 Java 21 交付门禁补验：在 maven:3.9-eclipse-temurin-21 隔离容器跑最新全量 mvn test，实际退出 0；30 个 Surefire suite / 127 tests / failures=0 / errors=0 / skipped=1，报告 java.version 全部为 21.0.12。跳过项仍是显式 opt-in Dokploy 实测，没有将其算作真实平台验证。Trivy 0.65.0 源码扫描保持 vuln,secret,misconfig/include-dev-deps/HIGH,CRITICAL/exit-code 1，退出 0且发现数组为空。另在 JDK 21 下使用 CycloneDX 2.9.1、includeTestScope=true 重新生成 BOM，校验所有组件版本存在且包含 spring-security-test/mockito-core，随后 SBOM 漏洞扫描退出 0、HIGH/CRITICAL=0。报告为私有 VM 目录 alert-upgrade/source-scan.json、sbom/bom.json、sbom-scan.json；日志 /tmp/devpilot-java21-final-regression.log、/tmp/devpilot-source-final-scan.log、/tmp/devpilot-java21-sbom-scan.log。测试/扫描临时容器正常结束，无发布/Key/Secrets/业务变更。真实故障发布仍等待本次人工确认，整体目标未完成。

2026-09-07 文档交付核对：git tag --list 为空，gh release list --repo miaomeng1/DevPilot --limit 5 成功但无发布记录，工作区仍有未提交候选改动。README 新增当前源码构建 → 安装器 --offline 的明确路径，说明前面的构建/拉取仍需网络、同一本机 Docker daemon、密钥生成/持久化布局和 digest 升级要求；保留独立的源码开发 Compose 路径并说明不能混用默认安装器维护命令。部署指南移除“脚本可下载即代表正式镜像可用”的暗示，示例先下载审阅并显式指定验证过的镜像；陈旧 V27 待验收句改为链接累计证据。install/upgrade --help 与参数一致，22 个相对文件链接存在，git diff --check 通过。此项未执行新的全新安装，也不表示未提交代码已发布到 GitHub。再次故障发布的人工确认仍未收到。

2026-09-07 01:44 UTC 发布通知准备：只读核对原 onboarding-acceptance 仍运行 ce61a0df…6a039 健康 digest，原三条发布/回滚记录及已消费审批不变；Dokploy 临时 Key 对原 v4 应用的只读请求成功，没有擅自轮换 Key。再次故障发布需要新的人工确认，已询问用户是否允许仅该新测试应用发布已验收故障 digest 并恢复 onboarding-v1；未复用旧审批、未再次触发 workflow 或生产部署。

等待确认期间新增 DeploymentNotificationPayloadTests，四种 release/rollback 成功失败组合核对应用/环境/服务器、原始镜像 digest、类型/状态、详情入口和原因，并验证潜在含密钥的 provider logs 不进入载荷；回滚订阅保留通用 deployment 事件兼容行为。四项测试加三项 AutomationWebhookIntegrationTests 共七项通过，零失败错误跳过；日志 /tmp/devpilot-deployment-notification-regression.log。此为服务载荷及本地 HTTP 集成回归，不作为真实 Dokploy 发布/回滚通知证据。

后续部署记录：完整后端 mvn test 退出 0，29 个 suite / 123 项测试 / 0 失败错误 / 1 项显式 opt-in Dokploy 实测跳过；日志 /tmp/devpilot-alert-full-regression.log。通过正式 Dockerfile 的 JDK 21 构建 `devpilot/server:stability-alert-context-v38`；Trivy 0.65.0 image vuln,secret HIGH/CRITICAL exit-code=1 门禁退出 0，结果 0，不使用 ignore-unfixed 或绕过扫描。报告位于私有 VM 目录 alert-upgrade/image-scan.json。本次不是重复全源码/SBOM扫描。新旧 JAR 中数据库迁移资源拼接摘要均为 36f5f11f8f02a8d0a2009f900f65b95f3de14fc929cdeef95ad6e375b1c83513。

在测试 VM 启动仅 127.0.0.1:15000 的独立 Registry，保留专用命名卷；导入候选与已有 Web，未推 GHCR。正式 upgrade.sh 使用 Server digest a201fb12bb5555098b4783325b826acd21b1c76b2d59c0519da57823b1c0aefc、Web digest bfcf1ca579150ba2fdf8caf2c5ddb556fdc9733cd5df09c6ab764c6f5a14b919，确认迁移审阅后先停写并备份。归档 /var/backups/devpilot-alert-upgrade/devpilot-20260907T013712Z-SPpzQn.tar.gz，SHA256=8a14d82ddba09cc7f8adc891f977052bff15c43217a36294159bac12a8905390，含原密钥及旧镜像配置。升级脚本退出 0，备份状态回报成功；不是恢复验收。原管理员、加密变量/初始化配置、Agent ONLINE、原告警与两条成功签名投递的 ID/eventId/次数全部保留，当前 Server 精确 digest 及 healthy 已核对。原 Dokploy 与其他平台未升级；Registry 保留用于此候选后续维护。应用不健康通知新增字段仍需下一轮真实触发验证。

后续代码改进（尚未部署运行镜像）：自动化告警载荷补充 reason/detailsPath；同服务器的有效应用资源补 applicationId/applicationName/environment/recordedVersion。记录版本不冒充实时 digest，已删除/不可解析/迁移服务器的资源回退 /alerts，不附加无关应用数据。原 message 保持兼容；RESOLVED 说明保留“规则变更也可能解除”的边界，不将人工禁用规则当作健康恢复。新增 AutomationAlertPayloadTests，修复前 4 项断言失败；最终补入删除场景后，5 项载荷测试、3 项自动化集成和 6 项告警集成共 14 项通过，零失败/错误/跳过。日志 /tmp/devpilot-alert-payload-before.log 与 /tmp/devpilot-alert-payload-final.log；本机 JDK 25 执行项目配置的编译目标，非全部后端回归。原 V38 实例未重建，真实应用不健康通知内容验收仍待升级后继续。

继续复核 VM UTC/NTP、管理员登录和 Agent ONLINE 正常，告警/通知为空。通过认证 API 在新测试 VM 创建一个限定该服务器的 AGENT_OFFLINE 规则（durationSeconds=0，使用原 heartbeat timeout 和调度周期），以及仅订阅 ALERT_FIRING / ALERT_RESOLVED 的专用 Webhook。临时 Python 接收端以 systemd-run 管理，只进入该测试后端的网络命名空间并绑定 127.0.0.1:18889，不发布到 Mac 或外网；私有签名凭据 0600，不打印 payload 或密钥。

仅停止 VM 的 devpilot-agent systemd 服务，平台五个组件和业务均不停止。最后心跳 01:28:33 UTC，随后 Agent 状态变为 OFFLINE。真实 MySQL 生成告警 `2096772783008931841`，状态 FIRING、metricType=AGENT_OFFLINE。通知事件 `cecb2489-3d45-4a0b-a58d-e7c8e8aa72f3`，投递 `2096772783076040706` 为 SUCCEEDED / HTTP 204 / attemptCount=1；实际接收端核对 HMAC-SHA256、delivery header 与 eventId 相符。离线继续经历多个 10 秒评估周期，仍只有该告警与一条请求。

01:29:56 UTC 启动同一个 Agent，未重新注册/轮换 Token。01:30:10 查询同一告警 RESOLVED，恢复事件 `64739c8d-dcf4-4524-a546-ac2c4b6e848f`，投递 `2096772951129219073` 同样一次成功/204。恢复后继续观察多个周期，01:30:40 仍恰好两条投递；真实接收记录共两行，分别 firing/resolved，签名均有效且 subject 指向同一 alert ID。没有直接写数据库或调用内部告警触发方法。

`alert-control.mjs`、`alert-received.jsonl`、`verify-alert.mjs`、`alert-verification.json` 保存在上述私有 VM 证据目录。接收文件为 root:600，首次 scp 被拒，随后仅导出一个 devpilot:600 的无凭据摘要副本；没有放宽原文件权限。最终断言通过，禁用专用订阅和规则后停止临时接收服务，保留告警、投递及审计记录。Agent active、五个平台组件 healthy。该项证明真实 Agent 离线/恢复通知及有限观察窗口的重复抑制，不代替应用持续不健康、真实发布/回滚通知或通知 payload 完整字段的验收；整体稳定版仍未完成。
