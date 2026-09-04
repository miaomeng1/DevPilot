# Architecture

DevPilot is a control-plane/Agent system. Managed hosts initiate outbound HTTP and WebSocket connections, so the control plane never needs SSH credentials or an inbound port on an Agent host.

```text
Browser
  │ HTTPS + /ws/logs
  ▼
Public Nginx gateway
  ├── Vue static console
  └── Spring Boot REST/WebSocket control plane
        ├── MySQL 8 (durable state and minute aggregates)
        ├── Redis 7 (10-second metric window and transient coordination)
        └── HTTPS/WebSocket ↔ Go Agent(s)
                              ├── gopsutil host metrics
                              ├── Docker Engine SDK
                              ├── health-check HTTP client
                              └── allow-listed Nginx file/test/reload adapter
```

CI/CD adds a second, signed ingress path and keeps build execution outside the production control plane:

```text
GitHub / GitLab
  └── GitHub Actions / GitLab CI / Woodpecker
        ├── tests + Trivy security gate
        ├── immutable sha-* image → GHCR / GitLab Registry
        └── HMAC-signed result → DevPilot
                                  ├── durable pipeline evidence
                                  └── encrypted adapter → Coolify / Dokploy → managed server
```

## Component boundaries

`devpilot-web` is a same-origin single-page application. Pinia holds authenticated identity, theme, public branding, and shared server state. Axios centralizes API calls; Vue Router enforces route roles; ECharts and xterm are isolated behind reusable components.

`devpilot-server` uses controller/service/mapper layering and DTOs at its network boundaries. Spring Security validates browser JWTs and method roles. MyBatis-Plus maps durable entities, Flyway owns schema changes, scheduled services handle offline detection, metric retention, application health cadence, alert evaluation, and notification retry.

`devpilot-agent` is a single static binary. It registers once with a high-entropy token, then authenticates every Agent request independently. The control plane queues typed Docker/Nginx work; the Agent polls, validates the enum and resource identifiers, executes through a dedicated adapter, and returns a typed result. No request carries a shell command.

One-click services preserve that boundary. The browser sends only an allow-listed template ID and constrained settings. The Agent keeps its own matching runtime catalog, uses the Docker SDK to pull an explicit image version, creates named volumes, applies loopback-only port bindings plus memory/log limits, and returns the created container ID. A fresh Docker snapshot is required before the control plane creates the corresponding application record.

## Primary data flows

### Registration and telemetry

1. An administrator creates a server placeholder; the raw Agent token is displayed once and only its SHA-256 hash is stored.
2. The Agent reports host inventory and receives its server identity plus current metric interval.
3. Heartbeats update liveness and policy. The server marks a node OFFLINE after the configured timeout.
4. Raw 10-second points enter a Redis sorted set with a two-hour TTL; minute aggregates are upserted into MySQL and retained for seven days. Seven-day views downsample to five-minute buckets.
5. The read-only capacity planner combines the latest metric, Docker availability, running-container count, and active alerts. Hard feasibility checks run before weighted scoring, so an offline or dangerously full node cannot win by averaging unrelated signals.

### Docker commands and logs

1. A role-authorized browser request creates a typed command row.
2. The matching Agent claims it, performs one SDK operation, and reports success/failure.
3. Live logs use a short-lived browser ticket and two WebSocket legs: browser ↔ server ↔ Agent. Pause/search/clear are browser-only controls; stream authorization remains server-side.

### Safe Nginx changes

The Agent writes candidate content to a temporary file, runs `nginx -t`, and aborts without touching the active file on failure. On success it backs up the current file, atomically replaces it, and reloads Nginx. The server records old/new hashes, redacted audit metadata, command outcome, and rollback history.

### Alerts

Rules maintain durable condition state so duration thresholds survive restarts. A transition creates or updates an alert event and fans out durable notification rows to matching severity/server routes. Delivery uses bounded exponential retries and does not follow redirects. Recurring quiet hours and one-time server/global maintenance windows mute delivery only: evaluation and event history continue. Routes can let `CRITICAL` alerts bypass mute periods. Webhook URLs are encrypted with AES-GCM; their plaintext is never returned by an API or written to audit logs. The original single webhook remains a fallback only while no new route is enabled.

### CI/CD evidence and deployment

CI executes tests, scans and image builds on an isolated runner. DevPilot does not accept source archives or arbitrary build commands. The runner signs its exact JSON callback body with a per-application 256-bit secret. DevPilot verifies the signature in constant time, enforces the configured branch, requires immutable digest/`sha-*` image identity, verifies that a `sha-*` tag matches the reported commit, persists the run idempotently, and only then calls the selected Coolify/Dokploy deployment adapter. API mode updates the provider target to that exact image before deployment; provider URLs, tokens and callback secrets use AES-GCM encryption and are redacted from audit capture.

Provider acceptance creates a deployment lifecycle record rather than an immediate success. The reconciler collects provider build logs and waits for an Agent health result newer than the deployment stabilization boundary. A healthy result updates the application's verified version. An unhealthy result or timeout retains the failed evidence and can create a rollback deployment pointing to the most recent verified image. Manual rollback uses the same exact-image adapter and accepts only previously healthy targets.

## Authorization model

- ADMIN: full access, server/container destructive actions, Nginx changes, alert policy, audit, users, and system settings.
- DEVELOPER: operational reads, logs, application work, and non-destructive service operations; no server deletion, user management, or control-plane settings.
- VIEWER: read-only APIs and pages.

Access tokens are short-lived signed JWTs carrying the user's current session version. Rotating refresh tokens live only in SameSite HttpOnly cookies. Password, role, or account-state changes atomically advance the session version and revoke refresh tokens, so already-issued access and refresh credentials both fail immediately. Five consecutive login failures lock the account for 15 minutes; a later successful login clears the lock. Disabled, deleted, locked, or version-mismatched users are rejected even when presenting a validly signed access token.

## Persistence and failure behavior

MySQL is authoritative for identities, inventory snapshots, aggregates, commands, applications/releases, Nginx versions, alert lifecycle/queues, audit events, and settings. Redis loss temporarily removes raw high-resolution points but does not remove durable aggregates. Agent disconnection leaves queued work pending or timed out and is visible as liveness/alert state; it never silently converts a requested operation into success.
