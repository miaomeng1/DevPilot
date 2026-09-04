# DevPilot 产品路线图

> 目标：做成个人开发者管理一台或少量服务器时，部署路径最短、故障信息最清楚、日常维护最省心的 DevOps 控制台。

## 竞品启发（2026-09）

| 产品 | 值得借鉴的核心能力 | DevPilot 的取舍 |
| --- | --- | --- |
| [Coolify](https://coolify.io/docs/get-started/concepts) | 项目/环境/资源层级、Git 与镜像部署、自动域名证书、通知、备份 | 保留部署平台集成，避免重复实现完整 PaaS；强化统一状态与操作入口 |
| [Dokploy](https://docs.dokploy.com/docs/core/backups) | 应用与数据库管理、S3 备份/恢复、部署并发、监控 | 优先补齐可验证备份、恢复演练和个人服务器容量保护 |
| [Portainer](https://docs.portainer.io/user/docker/stacks/webhooks) | Docker 清单、Stack、Registry 与 Webhook 更新 | 强化自动发现和从现有容器快速纳管，不开放任意 Shell |
| [Argo CD](https://argo-cd.readthedocs.io/en/stable/user-guide/auto_sync/) | 期望状态、差异检测、自动同步、自愈与重试 | 在非 Kubernetes 场景引入“期望镜像 vs 实际容器”的漂移检测 |
| [GitHub Actions](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/control-deployments) | Environment、审批、并发控制、部署记录 | 继续让 CI 平台负责构建门禁，DevPilot 负责部署编排、验证与审计 |
| [GitLab CI/CD](https://docs.gitlab.com/ci/environments/) | 环境历史、受保护变量、资源组并发和自动回滚 | 对 GitLab 保持同等接入体验，并统一展示两种 CI Provider 的部署证据 |

## 产品原则

1. **先给答案，再给数据**：首页首先回答“有什么问题、下一步做什么”。
2. **自动发现，人工确认**：从 Agent 清单预填镜像、端口与应用信息，敏感或有风险的操作仍需确认。
3. **部署必须可解释**：每次发布都能追溯 Commit、测试、安全扫描、镜像、Provider、健康验证和回滚。
4. **一台服务器也安全**：控制面故障不影响业务容器；限制资源、监控磁盘、备份到异机或对象存储。
5. **默认安全**：最小权限、密钥只显示一次、敏感字段加密、危险操作审计，不提供任意远程命令。

## 交付阶段

### P0 · 日常可用（进行中）

- [x] 浅色优先的中英双语控制台
- [x] 首页行动中心：离线服务器、停止容器、异常应用和活动告警
- [x] 自动发现未纳管容器，一键预填应用、版本和访问端口
- [x] GitHub / GitLab / Woodpecker 流水线凭证与不可变镜像
- [x] Coolify / Dokploy 精确镜像部署、健康验证和自动回滚
- [x] 期望镜像与实际运行镜像的漂移提示
- [x] 全局部署活动流与失败原因聚合

### P1 · 可靠维护

- [x] S3 兼容的配置与数据库定时备份，含远端对象大小核验与失败显式返回
- [x] 备份新鲜度、大小、SHA-256 校验结果与恢复演练状态
- [x] 磁盘水位保护、安全清理建议与空间恢复后自动继续发布
- [x] 容器 10 分钟重启风暴、持续离线和健康检查失败通知（含一键推荐规则）
- [x] 发布并发锁与持久队列，确保同一应用最多一个部署执行

### P2 · 更快交付

- [x] 仓库接入向导与 GitHub Actions / GitLab CI / Woodpecker 可执行 Workflow 生成器
- [ ] Preview / Staging 环境与临时访问地址
- [x] 环境变量模板、Revision 差异预览、全量加密与 Coolify 安全增量同步
- [x] Compose 项目（Stack）自动分组、关联服务视图与可审计的顺序重启
- [ ] 常用个人服务模板，但模板默认不暴露高风险端口

### P3 · 多机与扩展

- [ ] 多服务器调度建议与容量评分
- [ ] OpenTelemetry / Prometheus 数据导出
- [ ] 通知路由、静默窗口与维护窗口
- [ ] 稳定公开 API 与 Webhook 事件订阅

## 验收指标

- 新用户从登录到第一台服务器在线不超过 10 分钟。
- 已有 Docker 容器从发现到纳管不超过 30 秒。
- 任意失败发布在一个页面内能看到失败阶段、错误摘要、日志入口和回滚结果。
- 首页无需逐页查看即可识别所有高优先级异常。
- 备份状态可验证，并能记录最近一次恢复演练。
