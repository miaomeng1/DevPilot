# Deployment

## Source deployment

### 主机时间要求 / Clock synchronization

启动平台前确认主机与 Agent 使用准确的 UTC 时间，并启用 NTP/chrony/systemd-timesyncd。Linux 虚拟机还需确认虚拟硬件 RTC 使用 UTC；只检查时区或容器 `healthy` 不够。可对照 `date -u`、`timedatectl` 和 `sudo hwclock --show`，并在重启及首次网络时间同步后再次核对。

大幅回拨可能使当前 Snowflake ID 生成器报 `Clock moved backwards`，导致登录或其他数据库写入失败；JWT、心跳新鲜度和调度时间也会受影响。先停止写入、保留日志与备份，修复宿主/虚拟硬件时钟和 NTP 的冲突，再按维护流程恢复并验证登录及持久化。不要删除数据卷、重置主密钥，或把重启后暂时健康视为问题已解决。安装器会限时只读查询 `timedatectl`：未确认已同步且 RTC 为 UTC 时显示风险提示；工具缺失或无法查询也会提示，不自动改时间或阻止非 systemd 主机安装。该提示不替代人工核对实际 UTC、NTP 及重启后的时钟行为，升级前也需重新核对。

Copy `.env.example` to `.env`, replace every placeholder secret, set `DEV_PILOT_PUBLIC_URL` to the URL users and Agents can reach, then run:

```bash
docker compose --env-file .env -f deploy/docker-compose.yml up -d --build
```

The source Compose project builds the Spring Boot server, Vue console, and Linux `amd64`/`arm64` Agent artifacts. The public Nginx gateway exposes the UI and API on `PUBLIC_PORT` (8080 by default). MySQL and Redis are private to the Compose network.

Open `DEV_PILOT_PUBLIC_URL`, create the first administrator, then use **Servers → Add server** to obtain a one-time Agent token and installation command.

### 初始化向导

首次创建管理员后进入 `/setup`；已有管理员也可从“初始化 Setup”进入。页面集中展示管理员、平台访问地址、Dokploy 连接、目标服务器和 Agent 心跳状态。

- 保存的访问地址用于后续生成 Agent 安装命令；不会自动调整 DNS/TLS、反向代理、Cookie Secure 配置或已安装 Agent。外部可达性明确显示为需要人工确认。
- Dokploy Key 加密保存，响应不返回 Key。保存与只读验证是两个操作；验证只读取项目和服务器，15 分钟后证据过期，不代表具备全部发布权限或配额充足。
- 修改 Dokploy 地址必须重新提供 Key；同一地址留空保留原 Key。版本冲突要求刷新，不覆盖其他管理员已保存的配置。
- 从服务器管理创建服务器并取得一次性 Agent 安装命令，再回向导选择服务器、保存并刷新心跳。心跳缺失或过期不等同于业务应用故障。

项目接入向导可明确选择“复用初始化中的 Dokploy 授权”：浏览器只提交选择和配置 revision，服务端读取保存的地址和 Key，不返回 Key。初始化连接在识别与任务创建之间改变时要求刷新重新识别，不自动换到新部署目标。任务创建后保存自己的加密凭据快照，后续修改初始化授权不会静默修改正在执行的任务。仓库与 Registry 授权仍需按项目提供；过期任务继续通过原任务重新授权入口处理。

初始化向导也可直接添加服务器、保存选中项并显示一次性安装命令。先保存连接配置再添加；命令只在当前页面内存显示，可手动隐藏，刷新后不可再次获取。若创建或保存选择请求中断，先刷新服务器列表核对，勿重复添加。初始化页面的已验证状态不等于整个 CI/CD 已配置完成。各版本浏览器交互、MySQL 升级及剩余限制以 [稳定性验收记录](stability-validation.md) 为准，不能将单个页面的成功当作整个平台验收。

## Hosted one-line installer

Web 镜像提供 `/install.sh`，但脚本可下载不代表存在可拉取的正式发行镜像。当前稳定版仍为候选，尚无正式 Release；安装器默认的 `devpilot/server:1.0.0` / `devpilot/web:1.0.0` 不能视为已发布承诺。首次部署可按 [README 源码构建路径](../README.md#快速开始-quick-start) 构建并导入镜像，再显式指定镜像运行本地脚本。

若你已有可信的制品站点和已验证镜像，请先下载、检查脚本，再执行（下面域名及镜像均为占位示例）：

```bash
curl -fsSL https://your-release-host/install.sh -o install.sh
# Review install.sh and verify the selected image references before running it.
sudo bash install.sh \
  --server-image registry.example/devpilot/server@sha256:REPLACE_WITH_VERIFIED_DIGEST \
  --web-image registry.example/devpilot/web@sha256:REPLACE_WITH_VERIFIED_DIGEST \
  --public-url https://ops.example.com
```

The installer creates `/opt/devpilot`, generates independent random database, Redis, JWT, and AES-GCM secrets, pulls the release images, starts Compose, and waits for all service health checks. It requires Linux amd64/arm64, root, a reachable Docker daemon, Compose with `--wait-timeout`, `ss` (iproute2), curl and openssl. It checks at least 5 GiB free space on the installation filesystem and rejects a listening/published public TCP port. Check Docker's own data filesystem capacity separately when it is mounted elsewhere. Use a local Docker daemon; remote contexts are not an installation target supported by this script.

The destination directory must not exist. Existing DevPilot Compose containers or labelled data volumes also block installation to prevent regenerating keys for old data. If a previous attempt failed, inspect the preserved files and use recovery instructions; do not rerun installation over them. No data is removed automatically. Image registries are overrideable:

```bash
sudo DEVPILOT_SERVER_IMAGE=registry.example/devpilot/server:1.0.0 \
  DEVPILOT_WEB_IMAGE=registry.example/devpilot/web:1.0.0 \
  bash install.sh --public-url https://ops.example.com
```

For an offline host, transfer the two selected application images plus `mysql:8.4`, `redis:7.4-alpine`, and `nginx:1.29-alpine` using `docker save`/`docker load`, install host dependencies beforehand, then add `--offline`. The installer verifies that all five image references exist and skips registry pulls. This does not bypass configuration or health checks.

An `https://` public URL automatically enables Secure refresh cookies. Keep `.env` mode `0600`, back up the `devpilot_mysql-data` volume, and never rotate `DEV_PILOT_MASTER_KEY` without re-encrypting stored webhook credentials.

### 首次安装失败后继续

新版安装器在配置生成完毕后保存 `.install-resume.sha256`，健康检查成功后写入 `.installation-complete`。如果拉取镜像或首次启动失败，保留目录、密钥和已创建的数据卷；修复网络、磁盘等错误后运行同一版本安装脚本：

```bash
sudo bash install.sh --resume --install-dir /opt/devpilot
# 已提前导入全部镜像的离线环境：
sudo bash install.sh --resume --install-dir /opt/devpilot --offline
```

重试只使用原 `.env`、Compose 和 Nginx 文件，不接受端口、URL 或镜像覆盖参数；同名 shell 环境变量不会替换已保存值。文件校验不一致、配置尚未生成完整、另一维护操作占用锁，或已经成功安装时，都拒绝自动继续。离线重试禁止 Compose 隐式拉镜像，缺失镜像应先通过 docker load 补齐。

不要删除目录重新生成主密钥，也不要清除数据卷“解决”安装失败。配置生成中途被中断、或必须修改配置的情况需人工核对后恢复，不在自动继续范围内。旧安装器没有上述标识，不能通过手工补标识冒充可恢复安装；使用既有升级/恢复流程。已在隔离 Linux Docker 环境验证真实 Registry 连接失败后导入镜像、离线继续安装以及 Docker daemon 重启后的登录和配置保留；镜像传输中途断流、宿主内核重启及突然断电尚未验收。

## Agent installation

The web image publishes checksum-protected Agent binaries for Linux `amd64` and `arm64`. Run the command generated by the server:

```bash
curl -fsSL https://ops.example.com/install-agent.sh | \
  sudo bash -s -- --server https://ops.example.com --token dp_agent_xxx
```

The installer verifies `SHA256SUMS`, writes `/etc/devpilot-agent/config.yaml` with mode `0600`, installs the binary in `/opt/devpilot-agent`, and enables `devpilot-agent.service`. The service needs access to the Docker socket and configured Nginx directory because every remote action is implemented through an allow-listed Agent operation.

Useful commands:

```bash
sudo systemctl status devpilot-agent
sudo journalctl -u devpilot-agent -f
sudo /opt/devpilot-agent/devpilot-agent -version
```

## Upgrade and uninstall

Download the maintenance tools from the running web image once, then keep them root-owned:

```bash
sudo install -d -m 0750 /opt/devpilot/bin
for tool in upgrade uninstall backup restore; do
  curl -fsS "https://ops.example.com/tools/${tool}.sh" | sudo tee "/opt/devpilot/bin/${tool}.sh" >/dev/null
  sudo chmod 0750 "/opt/devpilot/bin/${tool}.sh"
done
```

For an installer-managed deployment, obtain the server and web **digests** for the target release from your registry, review release notes and platform database migrations, and schedule downtime. Do not edit `.env` image references before running the tool: the backup must preserve the old references. Replace the placeholders below with actual digests:

```bash
sudo /opt/devpilot/bin/upgrade.sh \
  --server-image 'registry.example/devpilot/server@sha256:<64-hex-digest>' \
  --web-image 'registry.example/devpilot/web@sha256:<64-hex-digest>' \
  --migrations-reviewed --yes
```

The tool checks Compose configuration, pulls only the two exact application images, stops the backend and gateway, completes a backup, updates image references and waits for service health. It does not pull or upgrade MySQL/Redis, remove orphan services, or automatically downgrade. A failed backup prevents image-reference changes. A failure after stopping services leaves the backend and gateway stopped; inspect the cause before continuing. Backup metadata reporting may warn while the gateway is stopped; this does not invalidate a successfully created local backup.

Flyway applies forward-only platform database migrations during server startup. `--migrations-reviewed` is an administrator confirmation, not an automatic compatibility proof. No backward-compatible version pair is certified by this script. For an incompatible migration, recover the pre-upgrade database with the corresponding old images in an isolated environment before cutover; switching images alone is not sufficient. Source-build Compose deployments require their own reviewed image/config update rather than this installer-specific command.

Do not rotate `DEV_PILOT_MASTER_KEY`; it protects stored provider credentials. `uninstall.sh` removes containers but preserves data volumes and configuration, and returns success when removal succeeds. Add `--purge` only when MySQL and Redis data may be permanently deleted. Uninstall requires confirmation (or explicit `--yes`), validates the saved Compose configuration and obtains the same maintenance lock before any removal. Lock conflicts and Docker errors return non-zero without claiming that removal succeeded.

## Backup and disaster recovery

`backup.sh` creates a mode-`0600` archive containing a transactionally consistent MySQL dump, `.env`, Compose definition, Nginx configuration, manifest and SHA-256 sidecar. The archive contains production secrets and must be encrypted, copied off-host and covered by retention policy. Redis contains short-retention raw metrics and is deliberately excluded; durable users, applications, audit history, alerts and CI/CD state live in MySQL.

升级期间网关会停止，因此新版 `upgrade.sh` 将备份报告暂存至备份目录下 `.upgrade-report.*/report.json`，等待服务健康后再签名上报。报告只包含归档名、大小、校验和、时间和目标类型，不含主密钥。上报失败不等于备份失败，也不会将已健康的服务停掉；终端会打印带确切路径的 `backup.sh --install-dir DIR --report-only FILE` 重试命令。该命令只上报记录，不重新备份、不启动或停止服务。请先核对平台连接及报告配置，再重试；同一校验和由后端去重。平台接受报告不等于恢复验证成功。

若升级在恢复服务前失败，待上报文件仍保留。先按故障恢复流程恢复平台健康，再使用该文件上报，不要为了上报而直接启动迁移不完整的数据库应用。`--report-only` 只接受管理员可信、root 所有、0600、单硬链接的普通文件，勿提交来自未知来源的报告。升级脚本和备份脚本需要配套更新。

When `MAINTENANCE_REPORT_SECRET` and `DEV_PILOT_PUBLIC_URL` are present in `.env`, the script verifies the checksum locally and sends only signed metadata (archive filename, size, SHA-256 and creation time) to **Maintenance → Backup center**. The archive and its secrets never pass through the browser or DevPilot API. Hosted installs generate an independent 384-bit report secret automatically. Existing source installs should generate one with `openssl rand -hex 48`, add it to `.env`, and recreate `devpilot-server`.

```bash
sudo /opt/devpilot/bin/backup.sh --backup-dir /mnt/encrypted/devpilot
sha256sum -c /mnt/encrypted/devpilot/devpilot-*.tar.gz.sha256
```

After one successful manual run, schedule a daily backup from the host root crontab:

```bash
(sudo crontab -l 2>/dev/null; echo '0 3 * * * /opt/devpilot/bin/backup.sh') | sudo crontab -
```

The default freshness target is 26 hours and can be overridden for the Server container with `BACKUP_FRESHNESS`. A green report proves that the local archive was created and checksum-verified; it does not prove off-host durability. Copy archives to an encrypted remote disk or S3-compatible destination and test restoration separately.

For an automatic off-host copy, install AWS CLI v2 on the host and set `BACKUP_S3_URI` in `/opt/devpilot/.env`. AWS S3, MinIO, Cloudflare R2, and compatible stores are supported. Leave the access-key fields empty when the host has an instance role; otherwise use a credential restricted to the selected bucket prefix. For non-AWS providers, also set the endpoint URL.

```dotenv
BACKUP_S3_URI=s3://my-private-backups/devpilot
BACKUP_S3_ENDPOINT_URL=https://<account>.r2.cloudflarestorage.com
BACKUP_S3_REGION=auto
BACKUP_S3_ACCESS_KEY_ID=replace-with-scoped-key
BACKUP_S3_SECRET_ACCESS_KEY=replace-with-scoped-secret
```

The script uploads both the archive and its portable checksum sidecar, then uses `HeadObject` to compare the remote object size with the local archive. Only then is the evidence marked `S3`. A failed remote copy leaves the verified local archive intact, reports it as `LOCAL`, and exits non-zero so cron or system monitoring can detect that off-host durability was not achieved. Configure bucket encryption, retention/lifecycle policy, and object-lock protection according to your provider.

Test restores on an isolated host regularly. A restore is destructive and requires `--yes`. The checksum sidecar is mandatory; the tool validates the archive checksum, an allowlist of archive members, regular-file/directory types, the compressed SQL stream, and the installed master key before stopping services. Links and duplicate archive entries are rejected. Checksums detect corruption, not malicious replacement: only restore trusted backups.

If SQL import fails, the server and gateway remain stopped because the database may be incomplete. Fix the underlying error and repeat the restore from a verified backup; do not manually start the application against a partial import. After import, Compose waits up to 180 seconds for service health. A failed startup also stops the server and gateway and exits non-zero. Health success is only the first check: verify login and encrypted settings separately.

```bash
sudo /opt/devpilot/bin/restore.sh \
  --archive /mnt/encrypted/devpilot/devpilot-20260901T120000Z.tar.gz \
  --yes
```

整机丢失时，在隔离的干净 Docker daemon 上使用备份中的配置初始化空数据库，再运行同版本恢复工具。**不要先用随机新密码初始化 MySQL，再替换整份 `.env`**：既有 MySQL 数据卷不会因环境变量变化而重置密码。目标上不得存在同名 Compose 项目或数据卷。

先从可信加密存储取得归档和 `.sha256`，核对校验和、归档成员及来源。仅在新的 `/opt/devpilot` 目录中提取 `environment.env`、`docker-compose.yml` 与 `nginx/default.conf`，将 `environment.env` 重命名为 `.env`，目录 0700、配置 0600。使用备份记录的同版本镜像；本机 Registry 地址需要先迁移等价镜像并核对镜像身份，不能随意换成 latest。

```bash
# 下列命令仅用于已准备好上述可信配置的全新隔离恢复目标。
cd /opt/devpilot
sudo docker compose --env-file .env -f docker-compose.yml up -d --wait mysql redis devpilot-web
sudo bash /path/to/same-release/scripts/restore.sh \
  --install-dir /opt/devpilot --archive /mnt/encrypted/devpilot/backup.tar.gz --yes
```

恢复副本应在启动 Server **之前**隔离外网，防止已保存的调度、回调或通知凭据触达原业务。成功后验证管理员登录、加密变量读取/脱敏、原构建与人工确认及发布/回滚关联。确认切换目标后才恢复 DNS/TLS 和外部连接；不要同时运行两套可访问原部署平台的控制面。备份不包含业务数据库/业务容器卷，这些数据需单独备份。

After an isolated or staging restore, open **维护 Maintenance → 恢复演练 Restore drill**, select the exact backup evidence, record PASSED or FAILED, and note which checks were completed. This is a signed-in operator attestation, not an automated restore claim. Keep failed drills in the history so the recovery procedure can be corrected before an incident.

## Health and troubleshooting

### 维护操作互斥

新版 install.sh、backup.sh、upgrade.sh、restore.sh、uninstall.sh 先使用主机级 `/run/devpilot-maintenance.lock`，再使用安装目录内的 `.devpilot-maintenance.lock` 和 Linux `flock`。即使两个目录指向同一 Docker Compose 项目，也不能在同一主机并发操作；为避免错误识别 Docker 目标，当前保守地串行化该主机上的全部 DevPilot 脚本维护。冲突时脚本立即非零退出并提示稍后重试，不调用 Docker、不改写配置、不开始恢复。安装预检要求 `flock`（通常由 util-linux 提供）。

升级调用的备份继承同一锁，直到升级及健康检查结束才释放。锁由内核随持有进程退出释放；锁文件正常保留，**不要手工删除锁文件来解除占用**，否则并发进程可能锁住不同文件。遇到占用先核对正在运行的维护命令，等待其结束；不要直接杀死正在导入数据库的恢复进程。

备份、恢复、升级与安装继续的 Compose 调用以安装目录 `.env` 为准：子进程清除同名 shell 环境变量，避免终端残留数据库参数、镜像引用或主密钥覆盖保存配置。不会 source/执行 `.env`，也不会改写其中的凭据；Docker 连接相关环境仍保留。此解析针对安装器生成的 `KEY=value` 格式，不支持以 shell 脚本或复杂 export 语法替代安装配置。需要更改镜像请使用升级脚本的明确参数，不要依赖临时 export 覆盖。

五个生命周期脚本还会清除继承的 `COMPOSE_PROJECT_NAME`、`COMPOSE_FILE`、`COMPOSE_PROFILES`、`COMPOSE_ENV_FILES`、`COMPOSE_DISABLE_ENV_FILE`，显式指定 `--project-name devpilot` 和安装目录中的 Compose 文件。文件必须保留安装器生成的顶层 `name: devpilot`；重命名或自定义项目会被拒绝，不会静默改为操作 devpilot。这些脚本不是任意 Compose 项目的通用维护工具。

此锁只协调同一 Linux 文件系统命名空间中这些版本的脚本，不阻止管理员直接运行 Docker/SQL，也不能与旧版不加锁脚本协同。从另一台主机或不共享 `/run` 的容器连接同一远程 Docker daemon，不受此本地锁保护；维护应统一从目标主机执行。升级维护工具时应同时更新五个脚本。若备份计划恰逢升级，应在升级结束后补跑，不能把锁冲突算成备份成功。主机锁必须是 root 所有、0600、单硬链接的普通文件，脚本拒绝符号链接或不安全属性；锁冲突不能通过删除文件绕过。

```bash
curl -fsS http://127.0.0.1:8080/healthz
docker compose --env-file .env -f deploy/docker-compose.yml ps
docker compose --env-file .env -f deploy/docker-compose.yml logs devpilot-server nginx
```

If an Agent stays pending, verify outbound connectivity to the public URL, system time, the one-time token, and `journalctl -u devpilot-agent`. If WebSocket logs disconnect behind another proxy, forward `Upgrade` and `Connection` headers and allow long read timeouts.

For CI/CD failures, work from left to right:

1. Open the CI provider run URL and confirm tests and Trivy gates passed.
2. Confirm the image exists under the exact `sha-*` tag or digest reported by the callback.
3. Check DevPilot's pipeline state for signature, branch or immutable-image rejection.
4. Check the deployment record for Coolify/Dokploy API status and collected provider logs.
5. Confirm the Agent submitted a health result after the deployment start time and before the configured deadline.
6. If health failed, verify that the previous `HEALTHY` image was selected and that the rollback deployment received a new healthy result.

Never paste `.env`, API tokens, callback secrets or raw authorization headers into tickets. Export audit and deployment evidence only after reviewing it for application-level secrets.
