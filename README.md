# DevPilot

## 自动接入 CI/CD

发布中心新增“自动接入新项目”：授权后读取仓库、创建部署项目/应用、配置端口与变量、加密写入 CI Secrets，并提交流水线 PR/MR。无需先运行业务容器；生产发布保留人工确认。支持范围、权限及首次验收见 [自动接入指南](docs/automatic-onboarding.md)。

日常操作见 [个人发布与回滚指南](docs/personal-delivery-guide.md)。[本地真实验收记录](docs/local-e2e-2026-09-05.md) 单独列出已验证场景与限制。

> 面向个人服务器、小型团队和实验环境的自托管 DevOps / CI/CD 控制面。

DevPilot 将服务器、Docker 容器、应用、流水线、镜像、部署、健康检查、告警、审计与回滚集中到一个控制台。它不在 Agent 上开放任意 Shell，而是通过受限操作连接 Linux 主机，并把构建与部署交给成熟的 CI 和部署平台执行。

## 核心架构 Architecture

```text
GitHub / GitLab
      ↓
GitHub Actions / GitLab CI / Woodpecker
      ↓  测试 · Trivy 安全扫描 · 多架构镜像构建
GHCR / GitLab Container Registry
      ↓  经人工确认的 image@sha256:digest（GitHub 新模板）
DevPilot  ─────────→  Coolify / Dokploy
   ↑                       ↓
   └──── Agent 健康验证 ← 生产服务器 / Docker
              ↓
      人工回滚 / 明确启用后自动回滚
```

构建任务在 GitHub Actions、GitLab CI 或 Woodpecker 中执行；生产服务器只拉取已经通过质量和安全门禁的镜像。DevPilot 负责验证流水线凭证、执行部署策略、观察运行状态并保留完整审计证据。

## 主要能力 Features

- **CI/CD 发布中心**：接入 GitHub、GitLab、Woodpecker，查看 Commit、测试、安全扫描、镜像和生产部署状态
- **精确镜像发布**：GitHub 新模板保存构建凭证，人工选择成功构建后按 digest 发布、不重新构建；旧 sha 标签流程仍兼容，但标签本身并非不可变
- **受控部署**：只有测试和安全门禁通过后，才调用 Coolify / Dokploy 部署精确镜像；同一应用的发布持久排队、严格串行
- **发布前检查**：在一个面板汇总 Provider、Agent、健康端点、磁盘、变量与并发状态；阻断条件恢复后排队版本自动继续
- **环境晋级**：将 STAGING 已验证健康的同一不可变镜像晋级到 PRODUCTION，不重复构建，并保留来源证据与目标独立回滚
- **PR / MR 临时预览**：为可信同仓库分支部署隔离 Preview URL，使用独立回调密钥，关闭或 TTL 到期后自动回收
- **安全运行配置**：环境变量模板、增删改差异、Revision 防覆盖、Secret 永不回显，以及 Coolify 发布前增量同步
- **健康检查与回滚**：部署完成后核对目标容器与新的 Agent 探测；支持人工回滚，自动回滚需要明确开启，且依赖上一健康版本
- **应用工作台**：关联镜像、容器、服务器、容器 IP、宿主机端口、Access URL 和 Health URL
- **自动发现与行动中心**：识别尚未纳管的容器并预填应用信息，首页直接提示当前异常和下一步操作
- **个人服务模板**：一键安装 Uptime Kuma、Gitea、Audiobookshelf；固定版本、命名卷、资源上限，Web 端口默认仅绑定 `127.0.0.1`
- **服务器监控**：CPU、负载、内存、磁盘、网络及 1h / 6h / 24h / 7d 趋势
- **容量与部署建议**：输入新服务的内存/磁盘预估，按硬门槛过滤并透明评分，单机也能判断现在是否适合继续部署
- **标准可观测性出口**：专用 Token 保护的 Prometheus scrape 与可选 OTLP/HTTP 推送，导出低基数控制面指标
- **个人自动化接口**：可撤销的一次性只读 API Token、版本化 `/api/v1`，以及带 HMAC 签名和持久重试的 CloudEvents Webhook
- **磁盘水位保护**：80% 预警、90% 高危；95% 或低于 2 GiB 时暂停新发布，空间恢复后自动继续
- **Docker 管理**：容器发现、资源统计、启动、停止、重启、删除以及实时 WebSocket 日志
- **Compose Stack 视图**：按 Compose 标签自动关联服务，汇总健康与资源状态，并受控顺序重启运行中的服务
- **Nginx 安全变更**：暂存编辑 → `nginx -t` → 备份 → 替换 → Reload，支持历史记录与回滚
- **告警中心**：磁盘、持续离线、健康失败与 10 分钟容器重启风暴检测，支持飞书、企业微信和 Discord Webhook
- **通知路由与静默**：按严重级别/服务器发送到多个接收端，支持跨午夜 Quiet Hours、一次性维护窗口和 Critical 绕过
- **权限与审计**：ADMIN / DEVELOPER / VIEWER RBAC，关键操作审计与敏感字段结构化脱敏
- **自托管交付**：Docker Compose、MySQL 8、Redis、Linux Agent、systemd 安装及备份恢复脚本
- **备份维护中心**：主机脚本完成数据库归档与 SHA-256 自检后，签名上报凭证，并记录隔离恢复演练结果

## 一台服务器也可以使用

DevPilot、Dokploy、Agent 和业务容器可以运行在同一台 Linux 服务器上：

```text
80 / 443
   ↓
Traefik / Nginx
   ├── devpilot.example.com → DevPilot
   ├── app.example.com      → 业务应用 A
   └── api.example.com      → 业务应用 B

服务器内部
   ├── DevPilot Web / Server
   ├── MySQL / Redis
   ├── DevPilot Agent
   ├── Dokploy / Coolify
   └── 业务容器
```

不同容器可以使用相同的内部端口，例如都监听 `8080`；Traefik 根据域名进行路由。需要直接暴露端口时，DevPilot 会展示类似 `0.0.0.0:3001 → 8080/tcp` 的真实映射。

## 快速开始 Quick Start

### 发行包安装（推荐）

从 [GitHub Releases](https://github.com/miaomeng1/DevPilot/releases) 下载对应版本的 `DevPilot-VERSION.tar.gz` 和校验文件，核验并解压后，按包内 `QUICK_START.md` 执行：

```bash
sudo bash install-release.sh --port 8080 --public-url http://YOUR_SERVER_IP:8080
```

发行包包含源代码、维护脚本、固定镜像 digest 的安装入口、`release.json`、双架构镜像扫描报告和 Java SBOM。私有 GHCR 镜像需先配置具有读取权限的 Registry 登录；**公开源代码不代表所有镜像公开**。实际发行状态、验收报告和限制以 Release 为准，不要使用示例镜像名或本机 Registry 地址代替发行清单。

首要验收链路为 GitHub Actions + GHCR + Dokploy；Linux amd64/arm64 制品均构建和扫描，真实主机验收使用 Ubuntu 24.04 ARM64。GitLab/Coolify 保持兼容但不宣称真实验收完成。历史逐次记录见 [稳定性验收记录](docs/stability-validation.md)。

### 自托管安装（当前源码构建路径）

在 Linux amd64/arm64 服务器上使用 Docker Engine 和 Compose v2。测试 VM 为 2 核 / 4 GB，安装盘至少留 5 GiB；构建镜像还需额外磁盘与内存，资源有限时可在同架构构建机完成后导入。先确认 UTC / NTP 正常，具体要求见 [部署指南](docs/deployment.md)。

取得你准备使用的代码版本后，从项目根目录执行：

```bash
docker build -t devpilot/server:local -f devpilot-server/Dockerfile devpilot-server
docker build -t devpilot/web:local -f devpilot-web/Dockerfile .
docker pull mysql:8.4
docker pull redis:7.4-alpine
docker pull nginx:1.29-alpine
sudo bash scripts/install.sh --offline \
  --server-image devpilot/server:local \
  --web-image devpilot/web:local \
  --port 8080 --public-url http://YOUR_SERVER_IP:8080
```

将 `YOUR_SERVER_IP` 替换为实际可达地址。此处 `--offline` 仅表示安装阶段不拉镜像；前面的源码构建/拉取仍需网络。root 与构建用户须连接同一个本机 Docker daemon。安装器会生成独立密钥，保存到 `/opt/devpilot/.env`，并等待服务健康；打开输出地址创建首个管理员。不要手动复制示例密钥，不要向已有安装目录重复安装。

后续备份、升级与恢复使用这套安装器管理的目录；升级要求可以拉取的不可变 digest，不能把本地 `:local` 标签直接当作升级参数。详见 [安装与维护](docs/deployment.md)。源码变更后必须重新构建制品，旧运行镜像不会自动更新。

### 源码开发启动（另一种目录布局）

下面保留源码 Compose 路径，适合开发与调试。它使用仓库内 `.env` 和 Compose 配置，不自动变成 `/opt/devpilot` 安装器布局；不能直接套用默认维护命令或在同一 Docker daemon 上再安装第二套同名 `devpilot` 项目。

```bash
git clone https://github.com/miaomeng1/DevPilot.git
cd DevPilot
cp .env.example .env
```

编辑 `.env`，替换所有 `change-me` / `replace-with` 值，并设置公开访问地址：

```dotenv
PUBLIC_PORT=8080
DEV_PILOT_PUBLIC_URL=http://your-server-ip:8080
AUTH_COOKIE_SECURE=false
```

生产环境应使用 HTTPS：

```dotenv
DEV_PILOT_PUBLIC_URL=https://devpilot.example.com
AUTH_COOKIE_SECURE=true
```

启动：

```bash
docker compose --env-file .env -f deploy/docker-compose.yml up -d --build
```

在运行 Docker 的主机打开 `http://localhost:8080`，其他电脑使用配置的服务器地址或域名，创建首个管理员账号。

不要把 `.env`、API Token、Webhook Secret、JWT Secret 或 Master Key 提交到 Git。

## 接入第一台服务器

1. 进入 **服务器 Servers**。
2. 选择 **添加服务器 Add server**。
3. DevPilot 会生成一次性 Agent Token 和安装命令。
4. 在目标 Linux 主机执行安装命令。
5. Agent 上线后，服务器指标和 Docker 容器会自动出现在控制台。

Web 镜像在 `/downloads/` 提供带校验和的 amd64 / arm64 Agent 二进制文件。

## 接入应用与自动部署

1. 初始化向导中连接 Dokploy、目标服务器和 Agent，完成连接验证及管理员配额确认。
2. 为业务项目准备支持的根目录 `Dockerfile`、可执行测试和健康检查接口。
3. 打开 **发布 CI/CD → 自动接入新项目**，授权仓库并检查默认分支与项目结构。
4. 确认目标服务器、环境、容器/发布端口、健康路径、变量和私有 Registry 读取凭据。
5. 向导自动创建或复用支持的部署资源、写入受保护 Secrets，并提交 CI 配置 PR；审阅后合并。
6. 推送代码后自动测试、扫描、构建镜像；构建成功仅进入“待发布确认”。
7. 在发布中心确认选定构建，再按页面指引触发发布原 digest，不重新构建。
8. 等待实际容器和新鲜健康探测通过；失败时查看原因并恢复。自动回滚必须明确开启且存在上一健康版本。

已有容器也可从“自动发现”纳管；手工配置向导作为兼容路径保留。公网 Actions 必须能访问 HTTPS 签名回调地址，不能填写仅本机可达的 localhost；临时隧道只适合验收。

详细配置见 [CI/CD 指南](docs/cicd.md)。

本仓库的三组件镜像构建与可选 Web 演示发布见 [平台自身流水线](docs/platform-workflow.md)；Web 演示发布不等于整套 DevPilot 平台升级。

## 一键安装个人服务

Agent 上线并成功上报 Docker 状态后，进入 **模板 Templates**：

1. 选择 Uptime Kuma、Gitea 或 Audiobookshelf。
2. 选择在线服务器，确认实例名、回环端口、环境和时区。
3. Agent 使用 Docker API 拉取显式版本镜像，创建受标记的持久卷和有资源上限的容器。
4. 容器被下一次清单发现后，DevPilot 自动登记应用并开启健康检查。
5. 验证服务可用后，再在 **Nginx** 中配置域名与 HTTPS。

模板不会执行任意 Compose 或 Shell，也不会公开绑定数据库、SSH 或管理端口。持久卷不等于备份；升级前仍需阅读上游发布说明并验证恢复流程。详细说明见 [个人服务模板](docs/service-templates.md)。

## 本地开发 Development

要求：Java 21+、Maven 3.9+、Node.js 22+、Go 1.24+、MySQL 8、Redis 7。

```bash
# Server
cd devpilot-server
JWT_SECRET='at-least-32-random-bytes' \
DEV_PILOT_MASTER_KEY='a-separate-random-master-key' \
mvn spring-boot:run

# Web
cd devpilot-web
npm ci
npm run dev

# Agent：先在控制台签发一次性 Agent Token
cd devpilot-agent
go run ./cmd/devpilot-agent -config ./config.example.yaml
```

Vite 开发服务器会把 `/api` 和 `/ws` 代理到 `localhost:8080`，Flyway 在 Server 启动时自动执行数据库迁移。

## 验证 Verification

```bash
make test
make compose-config
make cicd-verify
```

也可以分别执行：

```bash
cd devpilot-server && mvn test
cd devpilot-web && npm run build
cd devpilot-agent && go test ./...
```

## 目录结构 Repository Map

- `devpilot-server`：Java 21 / Spring Boot / MyBatis-Plus 控制面
- `devpilot-web`：Vue 3 / TypeScript / Pinia / ECharts / xterm 控制台
- `devpilot-agent`：基于 Docker SDK 与 gopsutil 的静态 Go Agent
- `deploy`：Docker Compose 与公网 Nginx Gateway
- `scripts`：安装、升级、备份、恢复、卸载及 Agent systemd 脚本
- `docs`：架构、API、部署与 CI/CD 文档

## 安全模型 Security

- 密码使用 BCrypt
- Refresh Token 与 Agent Token 仅保存 Hash
- Provider Token 与 Webhook Secret 使用环境提供的 Master Key 进行 AES-GCM 加密
- Agent API 只提供固定类型的 Docker、Nginx、指标、日志和健康检查操作
- 不提供接受任意 Shell 命令的接口
- 高风险操作执行角色检查并写入审计日志
- 密码、Token、Webhook URL、环境变量和 Nginx 正文会在持久化前脱敏

当前设计容量约为 50 台服务器、500 个容器和 100 个应用，适合个人基础设施、小型团队与实验环境。

## 文档 Documentation

- [系统架构](docs/architecture.md)
- [部署指南](docs/deployment.md)
- [CI/CD 指南](docs/cicd.md)
- [GitHub 状态核对与回调排查](docs/github-observation.md)
- [个人服务模板](docs/service-templates.md)
- [通知路由与维护窗口](docs/alert-routing.md)
- [容量与部署建议](docs/capacity-planning.md)
- [Prometheus / OpenTelemetry 可观测性导出](docs/observability-export.md)
- [公开 API 与事件 Webhook](docs/public-api-and-webhooks.md)
- [API Map](docs/api.md)
- [产品路线图与竞品取舍](docs/product-roadmap.md)
