# API overview

All responses use `{ "code": 0, "message": "success", "data": ... }`. Browser APIs require `Authorization: Bearer <access-token>` except setup/login/refresh and public settings. Refresh tokens are rotating HttpOnly cookies. Agent APIs use either the registration token in the initial JSON body or `X-DevPilot-Agent-Token` after registration.

## Authentication and administration

- `GET /api/auth/setup/status`, `POST /api/auth/setup`
- `POST /api/auth/login`, `POST /api/auth/refresh`, `POST /api/auth/logout`, `PUT /api/auth/password`
- `GET|POST /api/users`, `PUT|DELETE /api/users/{id}`, `PUT /api/users/{id}/password`
- `GET|PUT /api/settings`, `GET /api/system/public-settings`
- `GET /api/audit`, `GET /api/audit/actions`

## Infrastructure and monitoring

- `GET|POST /api/servers`, `GET|DELETE /api/servers/{id}`
- `GET /api/servers/{id}/metrics?range=1h|6h|24h|7d`
- `GET /api/dashboard?range=1h|6h|24h`
- `GET /api/monitor?range=1h|6h|24h|7d`

## Docker and logs

- `GET /api/docker/overview`, `GET /api/docker/containers`, `GET /api/docker/containers/{id}`
- `POST /api/docker/containers/{id}/start|stop|restart|remove`
- `POST /api/docker/containers/{id}/logs/ticket`
- Browser stream: `/ws/logs`; Agent relay: `/ws/agent/logs`

## One-click service templates

- `GET /api/service-templates` — curated template catalog and explicit image versions.
- `GET /api/service-templates/installations` — durable installation history and current stage.
- `POST /api/service-templates/{templateId}/installations` — ADMIN-only asynchronous installation request.
- `GET /api/agent/service-templates/installations/next` — authenticated Agent task claim.
- `POST /api/agent/service-templates/installations/{id}/result` — authenticated typed execution result.

The browser request accepts only a catalog ID, validated instance name, online server, environment, loopback port, and IANA timezone. The Agent owns the allow-listed runtime specification; no API accepts an image override, Compose document, host path, privileged flag, or shell command. Successful installation is not reported as `READY` until the created container appears in a fresh Docker snapshot and DevPilot registers its application record.

## CI/CD

- `GET|PUT /api/cicd/configurations/{applicationId}`
- `GET /api/cicd/applications/{applicationId}/runs`
- `GET /api/cicd/applications/{applicationId}/deployments`
- `GET /api/cicd/applications/{applicationId}/readiness`
- `GET /api/cicd/applications/{applicationId}/promotion-targets`
- `POST /api/cicd/applications/{applicationId}/deployments/{deploymentId}/promote`
- `GET /api/cicd/applications/{applicationId}/previews`
- `DELETE /api/cicd/applications/{applicationId}/previews/{pullRequestId}`
- `GET|PUT /api/cicd/applications/{applicationId}/environment`
- `POST /api/cicd/applications/{applicationId}/environment/sync`
- `POST /api/cicd/applications/{applicationId}/deployments/{deploymentId}/rollback`
- `POST /api/cicd/webhooks/{applicationCode}/previews` (separate Preview HMAC secret; `DEPLOY` / `CLOSE`)
- Signed CI callback: `POST /api/cicd/webhooks/{applicationCode}` with `X-DevPilot-Signature: sha256=<HMAC>`

Provider URLs and tokens are write-only encrypted settings. Deployment responses include the immutable image, provider deployment ID, collected provider logs, health deadline and rollback linkage, but never provider credentials.

Application environment values are all AES-GCM encrypted at rest. Secret values are never returned by the API and may be preserved by sending `null` on a later revision. `PUT` requires `expectedRevision`; stale writes return `409`. Coolify API mode supports safe key-level synchronization. Provider synchronization deletes only keys previously managed by DevPilot and runs before image deployment when the saved revision is dirty.

## Applications, Nginx, and alerts

- `GET|POST /api/applications`, `GET|PUT|DELETE /api/applications/{id}`
- `GET|POST /api/applications/{id}/deployments`
- `GET /api/nginx/hosts`, `GET /api/nginx/hosts/{serverId}`
- `GET /api/nginx/configs`, `GET|PUT /api/nginx/configs/{id}`
- `GET /api/nginx/configs/{id}/history`, `POST /api/nginx/configs/{id}/history/{historyId}/rollback`
- `GET|POST /api/alerts/rules`, `PUT|DELETE /api/alerts/rules/{id}`
- `GET /api/alerts`, `GET /api/alerts/summary`, `POST /api/alerts/{id}/acknowledge`
- `GET|PUT /api/alerts/webhook`

## CI/CD control plane

- `GET|PUT /api/cicd/configurations/{applicationId}` — ADMIN writes repository, branch and Coolify/Dokploy adapter settings. Deployment webhooks and callback secrets are AES-GCM encrypted; a raw callback secret is returned only when first created or explicitly rotated.
- `GET /api/cicd/applications/{applicationId}/runs` — authenticated pipeline evidence history.
- `POST /api/cicd/webhooks/{applicationCode}` — public CI callback authenticated by `X-DevPilot-Signature: sha256=<HMAC-SHA256(raw-body)>`.

The callback rejects an unexpected branch, malformed commit, mutable image reference, or a `SUCCEEDED` result whose test/security gates are not both `PASSED`. External run IDs are idempotency keys, so duplicate successful deliveries do not trigger duplicate deployments.

## Agent surface

The `/api/agent/**` namespace is separate from user authentication and has no arbitrary command endpoint. It covers registration/heartbeat, metrics, Docker snapshots and typed command polling/results, health tasks/results, Nginx inventory and typed edit results, and WebSocket log relay. Inputs are validated and control-plane requests map to a fixed operation enum.

HTTP statuses distinguish validation (`400`), missing/invalid credentials (`401`), authorization (`403`), missing resources (`404`), conflicts (`409`), and accepted asynchronous Agent commands (`202`). The JSON `code` gives the stable product-specific reason.
