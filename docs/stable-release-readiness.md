# 个人自用稳定版交付检查

核对日期：2026-09-07。结论：**未达到完整稳定版完成条件**。本页是剩余工作的入口，不替代[逐次验收记录](stability-validation.md)，也不是兼容性认证。

## 已有真实证据及边界

| 目标 | 已有证据 | 不应扩大解释为 |
| --- | --- | --- |
| 安装、Agent、自启动 | 独立 Ubuntu VM 正式安装、systemd Agent、修复 RTC 后首次 NTP 同步及内核重启核验 | 所有发行版/架构、断电或 Dokploy 业务主机重启均已验证 |
| 备份、独立恢复、升级 | 独立空数据库恢复真实发布/回滚历史；VM 升级前备份及升级后登录/凭据/Agent 核验 | 新增的所有后续记录均已重新恢复，或切镜像等同数据库回退 |
| 新项目接入及交付 | 私有验收仓库从无 CI，经向导、PR、push、私有双架构镜像、人工确认、原 digest 发布 | 当前未提交候选已成为用户可下载的正式版本 |
| 故障恢复与通知 | 实际故障发布、自动回滚成功；另有 GHCR EOF 导致回滚失败通知及人工恢复到新健康容器 | 网络故障等同 Token 失效，或所有故障组合均已验证 |
| Agent/应用故障通知 | 真实离线/恢复、持续 HTTP 503/200、缺失数据不误恢复、有限窗口重复抑制 | 任意规模长期不重复，或 HTTP 夹具等同镜像发布 |
| 完全丢失回调恢复 | Run 34038096535 attempt 2 两次回调 404、平台缺记录；恢复原制品，重试复用同一记录、不部署 | 仅终态丢失并经 observer 判失败后的补正分支也已实测 |
| 日常日志 | 实际 WebSocket → Agent → Docker 日志、一次性票据重放拒绝 | 大吞吐量日志压力验收 |

## 仍须关闭的交付缺口

1. **通知实测已补齐本阶段事件种类**：构建失败、发布失败、回滚成功/失败、Agent 离线/恢复及应用持续不健康/恢复已有真实签名 HTTP 证据。回滚失败来自实际 GHCR 网络 EOF，并经人工恢复到新健康容器；没有完成原计划的无效凭据注入，不将网络故障等同 Token 失效。仍需最终版本绑定，不把测试环境所有事件种类通过扩大为任意故障组合通过。
2. **持久化证据范围**：VM 的内核重启覆盖配置、凭据、Agent、告警与通知审计；真实 Dokploy 发布/回滚记录目前另有独立恢复证据。最终验收必须说明或补齐两者交集，不能合并措辞冒充同一场景。
3. **异常路径逐项复核**：将 Token/权限、限流、端口、拉取失败、超时后远端已成功、刷新恢复逐项映射到当前实现和可复核证据。存在单测不等于全部真实平台组合通过。
4. **正式代码和版本交付**：当前 HEAD 为 `76f671c`，核对时有 179 条未提交/未跟踪状态，未找到 Git tag 或 GitHub Release。先审阅完整改动范围及敏感文件，再形成明确候选提交、版本和对应镜像/Agent 制品；不要整体盲目暂存或提前贴稳定标签。
5. **最终版本回归**：当前工作树已统一复查后端 130 项（1 项显式跳过）、Web 39 项与类型/构建、Agent 非缓存测试、维护夹具、工作流语义与 84 项 shell 门禁，源码 Trivy 门禁退出 0。最终选定提交仍需绑定版本及制品/SBOM扫描记录，不能把工作树结果称为已发布版本认证。
6. **按发布文档独立安装**：用最终交付的版本和制品从干净环境完成核心流程，不现场补 SQL、不依赖开发者私有 `/tmp` 脚本。现有 README 源码安装路径与正式发行版的体验须分别记录。
7. **临时资源收尾**：保持生产/测试资源归属清楚，清理与保留清单经过核对，不能删除仍用于业务或恢复的凭据和数据。

## 本轮临时资源

- 验收应用仍运行 `onboarding-v1`；历史故障构建处于待人工确认，**不应发布**。最新 main 已恢复健康 `onboarding-v2`，但其发布因 GHCR 网络 EOF 未成功；随后已恢复 v1，不能宣称 v2 上线成功。
- `Onboarding release notification acceptance` 订阅已禁用，专用接收进程已停止，历史证据保留。
- `Onboarding build failure acceptance`、遗留 `Stability notification fixture` 和 `Onboarding rollback failure acceptance` 均已禁用，接收进程停止，历史保留。故意失败的测试已经恢复，后续 `6a0cc85` 也已移除 main 的 HTTP 503 夹具并改为健康 v2；旧故障提交及镜像仍不可作为健康业务版本。配置及凭据未变。
- 构建回调的故障 URL 已恢复为正确地址；构建/生产 Environment 的签名密钥未轮换。
- callback-only 网关监听本机 18187，临时隧道用于验收。结束使用时停止对应进程，并同步处理环境中的临时 URL；不可只删仓库级 Secrets 后声称环境凭据已清理。
- 旧 v4 不作为下一次故障测试目标。没有云资源购买或账号权限扩张。

完成以上工作后，仍需对原目标的全部 P0/P1 与 13 个验收场景逐项审查；本页不能被当作缩小后的完成定义。

## 异常路径证据索引（2026-09-07 收尾复核）

以下是已有证据的定位，不表示本轮重新运行所有场景。

| 场景 | 可复核入口 | 证据边界 |
| --- | --- | --- |
| 凭据/权限 | `RepositoryOnboardingClientTests.dokployMissingPermissionsFailBeforeAnyWrite`；验收记录中的 Agent Token 轮换、新 Token 注册及旧 Token 401 | 接入权限测试使用模拟响应；Agent Token 不能替代真实 Dokploy Token 失效验收 |
| API 限流 | `RepositoryOnboardingClientTests.githubInspectionStopsAtLowQuotaOrKnownReadOnlyAccess`、`GithubRunClientTests`、`CicdIntegrationTests.observerRotationRejectsOldResultAndUsesNewCredentialAfterBackoff` | 低配额检查、查询错误及退避插入点是本地测试，不曾故意耗尽 GitHub 配额 |
| 端口冲突/旧证据 | `HostPortPreflightTests`、`ProviderOnboardingRetryTests.portConflictsAndUnreadableMappingsStopBeforeAnyWrite` | 验证阻止写入和区分内部端口/发布端口；不是对任意主机端口的原子预留 |
| 超时后远端已成功 | `ProviderOnboardingRetryTests.lostPortCreationResponseIsReconciledWithoutDuplicateCreation` | 模拟端口创建后响应丢失，重试只创建一次；不可扩大为所有外部 API 都支持幂等 |
| 刷新/响应丢失恢复 | `scripts/test-stability-ui.cjs`；逐次记录“人工确认”与“浏览器刷新失败与恢复故障注入”章节 | 真实 HTTP/UI 加浏览器层断网注入；非物理网络断开 |
| Agent 离线 | VM 真实离线/恢复通知；`CicdIntegrationTests.releasePreflightQueuesWhileAgentIsOfflineAndResumesWhenOnline` | 前者是真实 Agent，后者发布排队分支为集成测试 |
| Registry 拉取失败 | Run `34078010585`、发布 `2096795051943600130`、人工恢复 `2096795530069090305` | 实际 GHCR EOF、失败通知与恢复；不证明 v2 曾成功运行 |

本轮只读环境检查：Fusion 报告验收 VM 正在运行，地址仍为 `192.168.180.130`，但 SSH banner 等待 8 秒和 HTTP 等待 5 秒均超时。未强制重启、未恢复数据库、未覆盖历史；真实发布/回滚记录与内核重启的交集仍未补齐。此结果不能单独归因于 DevPilot 服务。

接入 HTTP 错误指引新增按状态区分：401 更新凭据，403 同时提示权限与可能限流，429 等待限流恢复，5xx 提醒远端可能已执行并核对现有资源。没有新增自动重试、权限提升或部署动作。新代码晚于已有候选归档，不能沿用旧归档的哈希认证新代码。

本轮 JDK 21 离线 Maven 专项回归退出 0：`OnboardingHttpGuidanceTests` 3 项、`RepositoryOnboardingClientTests` 11 项、`HostPortPreflightTests` 2 项、`ProviderOnboardingRetryTests` 7 项，共 23 项，零失败/错误/跳过。日志 `/tmp/devpilot-onboarding-guidance-regression.log`。`git diff --check` 通过。本轮未重建或升级运行镜像，错误文案变更尚未部署。

## 验收 VM 强制重启后复核

用户明确同意后，于 2026-09-07 03:24 UTC 仅对 `host-reboot-20260907/devpilot-linux.vmx` 执行一次 Fusion hard reset。没有操作 Mac、其他 Docker 容器或恢复数据库。

重启后默认 SSH 仍超时。只读路由检查发现 `192.168.180.130` 经 `utun7`，而 VMware NAT 网卡为 `bridge101`；`curl --interface bridge101` 立即返回 HTTP 200，SSH 使用仅当前连接的 `ProxyCommand=nc -b bridge101 %h %p` 后正常。**当前连通问题有隧道路由证据，先前的超时不足以证明 VM 或 DevPilot 卡死；强制重启本身没有修正该路由。** 未修改 VPN、全局路由或持久 SSH 配置。

私有脚本 `verify-forced-reset.mjs` 验证退出 0，结果保存至验收目录 `forced-reset-verification.json`：

- boot ID 从 `298c1b6e-b3eb-476c-9358-b186c88220f1` 变为 `09c5abdb-b3a6-45c2-b8ad-ea60384be9a7`；NTP 同步、时钟差小于 5 秒。
- 五个平台容器 healthy，辅助 Registry 启动；Docker/Agent 服务 active，Agent 配置仍为 root:0600。
- 原管理员登录、服务器身份、重启后的 Agent 心跳与六个实际容器快照匹配。
- 原应用公开变量可解密，Secret 仍脱敏，初始化配置 revision 与服务器关联保留。
- 与既存重启后基线精确比较：2 条规则、2 个订阅、2 条已恢复告警、4 条成功通知及固定时间范围内 34 条审计记录一致。

该结果只覆盖现有 VM 数据；没有导入真实 Dokploy 发布/回滚历史，不能据此关闭该交集缺口，也不是全面断电可靠性认证。
