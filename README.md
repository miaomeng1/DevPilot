# DevPilot

DevPilot Developer Cloud Console is a lightweight, self-hosted DevOps control plane for personal servers, small teams, and labs. A Vue console and Spring Boot control plane manage outbound-connected Go Agents on Linux hosts—without exposing an arbitrary shell API.

## V1 capabilities

- JWT login, rotating refresh sessions, login lockout, self-service password changes, first-run administrator setup, and ADMIN/DEVELOPER/VIEWER RBAC
- Linux inventory, Agent heartbeat/offline detection, CPU/load/memory/disk/network monitoring, and 1h/6h/24h/7d trends
- Docker discovery, resource statistics, typed start/stop/restart/remove commands, masked environment values, and live WebSocket logs
- Application catalog, container binding, Agent-executed health checks, and release history
- Nginx discovery plus staged edit → `nginx -t` → backup → replace → reload, with history and rollback
- Stateful alert rules, FIRING/ACKNOWLEDGED/RESOLVED lifecycle, encrypted webhook configuration, native Feishu/WeCom/Discord payloads, and durable retries
- Structurally redacted audit trail, user administration, system branding, theme, token/Agent/metric/log policy settings
- MySQL 8 migrations, Redis raw-metric retention, Docker Compose, Linux Agent binaries for amd64/arm64, and systemd installation

## Quick start from source

Requirements: Docker Engine with Compose v2 and at least 2 GB of free memory.

```bash
cp .env.example .env
# Replace every change-me/replace-with value and set DEV_PILOT_PUBLIC_URL.
docker compose --env-file .env -f deploy/docker-compose.yml up -d --build
```

Open `http://localhost:8080` (or your configured public URL), create the first administrator, then select **Servers → Add server**. DevPilot generates a one-time Agent token and a ready-to-run install command. The web image serves checksum-verified Linux Agent binaries at `/downloads/`.

Never use `.env.example` credentials outside a disposable local environment. For HTTPS deployments set `DEV_PILOT_PUBLIC_URL=https://…` and `AUTH_COOKIE_SECURE=true`.

## Local development

Requirements: Java 21+, Maven 3.9+, Node.js 22+, npm, Go 1.24+, MySQL 8, and Redis 7.

```bash
# Terminal 1
cd devpilot-server
JWT_SECRET='at-least-32-random-bytes' \
DEV_PILOT_MASTER_KEY='a-separate-random-master-key' \
mvn spring-boot:run

# Terminal 2
cd devpilot-web
npm ci
npm run dev

# Terminal 3, after issuing an Agent token
cd devpilot-agent
go run ./cmd/devpilot-agent -config ./config.example.yaml
```

The Vite server proxies `/api` and `/ws` to `localhost:8080`. Flyway applies every database migration automatically. The Agent example intentionally contains an invalid URL/token; replace them with values issued by DevPilot.

## Verification

```bash
make test
make compose-config
make cicd-verify

# Individual suites
cd devpilot-server && mvn test
cd devpilot-web && npm run build
cd devpilot-agent && go test ./...
```

## Repository map

- `devpilot-server` — Java 21 / Spring Boot / MyBatis-Plus control plane
- `devpilot-web` — Vue 3 / TypeScript / Pinia / ECharts / xterm console
- `devpilot-agent` — static Go Agent using Docker SDK and gopsutil
- `deploy` — source-build Compose project and public Nginx gateway
- `scripts` — server install/upgrade/backup/restore/uninstall and Agent systemd installer
- `docs` — [architecture](docs/architecture.md), [API map](docs/api.md), and [deployment guide](docs/deployment.md)

## Security model

Passwords use BCrypt. Refresh tokens and Agent tokens are stored as hashes; webhook credentials use AES-GCM with an environment-provided master key. User operations and Agent operations use separate authentication surfaces. The Agent implements only typed Docker, Nginx, inventory, metric, health-check, and log operations—there is no endpoint that accepts a shell command. Dangerous user requests are role-checked and audited, including failed attempts, with passwords, tokens, webhook URLs, and Nginx bodies redacted before persistence.

DevPilot V1 is designed for up to roughly 50 servers, 500 containers, and 100 applications. Its CI/CD track delegates build execution to GitHub Actions, GitLab CI, or Woodpecker and deployment execution to Coolify or Dokploy; DevPilot remains the evidence, security-policy, audit, and operations control plane rather than embedding an arbitrary command runner.

## CI/CD track

The production pipeline track is now defined for GitHub Actions, GitLab CI, and Woodpecker. Successful quality and Trivy security gates produce immutable `sha-<commit>` images in GHCR or GitLab Container Registry, then trigger exactly one Coolify/Dokploy deployment target. See [the CI/CD guide](docs/cicd.md) and validate the contracts with `make cicd-verify`.
