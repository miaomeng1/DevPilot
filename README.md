# DevPilot

> 面向个人服务器、小型团队和实验环境的自托管 DevOps / CI/CD 控制面。

DevPilot 将服务器、Docker 容器、应用、流水线、镜像、部署、健康检查、告警、审计与回滚集中到一个控制台。它不在 Agent 上开放任意 Shell，而是通过受限操作连接 Linux 主机，并把构建与部署交给成熟的 CI 和部署平台执行。

## 核心架构 Architecture

```text
GitHub / GitLab
      ↓
GitHub Actions / GitLab CI / Woodpecker
      ↓  测试 · Trivy 安全扫描 · 多架构镜像构建
GHCR / GitLab Container Registry
      ↓  immutable sha-<commit> image
DevPilot  ─────────→  Coolify / Dokploy
   ↑                       ↓
   └──── Agent 健康验证 ← 生产服务器 / Docker
              ↓
      自动回滚上一健康镜像
```

构建任务在 GitHub Actions、GitLab CI 或 Woodpecker 中执行；生产服务器只拉取已经通过质量和安全门禁的镜像。DevPilot 负责验证流水线凭证、执行部署策略、观察运行状态并保留完整审计证据。

## 主要能力 Features

- **CI/CD 发布中心**：接入 GitHub、GitLab、Woodpecker，查看 Commit、测试、安全扫描、镜像和生产部署状态
- **不可变镜像**：使用 `sha-<commit>` 标签，支持 GHCR / GitLab Container Registry 和 amd64 / arm64
- **受控部署**：只有测试和安全门禁通过后，才调用 Coolify / Dokploy 部署精确镜像；同一应用的发布持久排队、严格串行
- **发布前检查**：在一个面板汇总 Provider、Agent、健康端点、磁盘、变量与并发状态；阻断条件恢复后排队版本自动继续
- **环境晋级**：将 STAGING 已验证健康的同一不可变镜像晋级到 PRODUCTION，不重复构建，并保留来源证据与目标独立回滚
- **PR / MR 临时预览**：为可信同仓库分支部署隔离 Preview URL，使用独立回调密钥，关闭或 TTL 到期后自动回收
- **安全运行配置**：环境变量模板、增删改差异、Revision 防覆盖、Secret 永不回显，以及 Coolify 发布前增量同步
- **健康检查与回滚**：部署完成后等待新的 Agent 探测；失败时保留旧版本并自动回滚
- **应用工作台**：关联镜像、容器、服务器、容器 IP、宿主机端口、Access URL 和 Health URL
- **自动发现与行动中心**：识别尚未纳管的容器并预填应用信息，首页直接提示当前异常和下一步操作
- **服务器监控**：CPU、负载、内存、磁盘、网络及 1h / 6h / 24h / 7d 趋势
- **磁盘水位保护**：80% 预警、90% 高危；95% 或低于 2 GiB 时暂停新发布，空间恢复后自动继续
- **Docker 管理**：容器发现、资源统计、启动、停止、重启、删除以及实时 WebSocket 日志
- **Compose Stack 视图**：按 Compose 标签自动关联服务，汇总健康与资源状态，并受控顺序重启运行中的服务
- **Nginx 安全变更**：暂存编辑 → `nginx -t` → 备份 → 替换 → Reload，支持历史记录与回滚
- **告警中心**：磁盘、持续离线、健康失败与 10 分钟容器重启风暴检测，支持飞书、企业微信和 Discord Webhook
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

要求：Docker Engine、Docker Compose v2，建议至少保留 2 GB 可用内存。

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

打开 `http://localhost:8080` 或配置的域名，创建首个管理员账号。

不要把 `.env`、API Token、Webhook Secret、JWT Secret 或 Master Key 提交到 Git。

## 接入第一台服务器

1. 进入 **服务器 Servers**。
2. 选择 **添加服务器 Add server**。
3. DevPilot 会生成一次性 Agent Token 和安装命令。
4. 在目标 Linux 主机执行安装命令。
5. Agent 上线后，服务器指标和 Docker 容器会自动出现在控制台。

Web 镜像在 `/downloads/` 提供带校验和的 amd64 / arm64 Agent 二进制文件。

## 接入应用与自动部署

1. 为业务项目准备 `Dockerfile` 和 `/healthz` 健康检查接口。
2. 在 Dokploy 或 Coolify 创建 Application，配置域名、容器端口和环境变量。
3. 在 DevPilot 的 **应用 Applications** 中从“自动发现”选择容器，确认预填信息后完成纳管。
4. DevPilot 会展示镜像、容器 IP 和真实端口映射，并可生成访问与健康检查地址。
5. 在 **发布 CI/CD** 中配置仓库、受保护分支、部署平台 API 和资源 ID。
6. 打开 **仓库接入向导**，选择技术栈并复制或下载 GitHub Actions、GitLab CI 或 Woodpecker 配置。
7. 把向导列出的变量、一次性回调 Secret 与回调 URL 存入 CI 平台的受保护 Secrets。
8. 推送代码后，CI 自动测试、扫描并构建镜像。
9. 生产审批通过后，DevPilot 触发部署、执行健康验证并在失败时回滚。

详细配置见 [CI/CD 指南](docs/cicd.md)。

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
- [API Map](docs/api.md)
- [产品路线图与竞品取舍](docs/product-roadmap.md)
