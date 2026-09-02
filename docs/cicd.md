# DevPilot CI/CD

DevPilot ships one deployment contract with three supported CI entry points:

```text
GitHub / GitLab
  -> tests and type checks
  -> Trivy vulnerability, secret and IaC gate
  -> immutable sha-<12 character commit> images
  -> GHCR / GitLab Container Registry
  -> protected production approval
  -> Coolify or Dokploy exact-image API deployment
  -> Agent health verification
  -> automatic rollback to the latest verified image
```

The deployment host never builds application source in this mode. It pulls images that already passed the same commit's quality and security gates.

GitHub Actions publishes each component as a multi-platform manifest for
`linux/amd64` and `linux/arm64`. Build stages run on the native builder platform
and BuildKit cross-compiles the Agent, while QEMU is available for the small
target-platform runtime layers. The same immutable tag can therefore be pulled
by conventional x86 servers and ARM64 hosts without retagging.

## GitHub Actions

Workflow: `.github/workflows/cicd.yml`

1. Enable GitHub Actions and Packages for the repository.
2. Create a protected `production` environment. Add required reviewers when the repository plan supports them.
3. In DevPilot, configure the application's repository, protected branch, deployment provider API and resource ID. Copy the one-time callback secret.
4. Add these protected environment secrets:
   - `DEVPILOT_CICD_CALLBACK_URL` — the absolute DevPilot `/api/cicd/webhooks/<application-code>` URL.
   - `DEVPILOT_CICD_CALLBACK_SECRET` — the one-time secret displayed by DevPilot.
5. Configure the deployment platform to use these images with tag `sha-<commit>`:
   - `ghcr.io/<owner>/devpilot-server`
   - `ghcr.io/<owner>/devpilot-web`
   - `ghcr.io/<owner>/devpilot-agent`
6. Push to `main` or `master`. Pull requests build but never publish or deploy. A normal protected-branch push publishes the immutable images but does not deploy them.
7. For production, select **Actions → DevPilot CI/CD → Run workflow** on the protected branch. This explicit operator action reruns every gate and sends the signed deployment callback only if they all pass.

The deploy job depends on every image build, and image builds depend on both quality and security jobs. A failed test or scan therefore cannot reach production. `workflow_dispatch` is the portable manual approval gate for private repositories on plans that do not provide required environment reviewers; supported plans can enforce environment reviewers as an additional gate.

The reference acceptance application deploys `devpilot-web`. That image is
independently runnable on port `8080` and exposes `/healthz`; the control-plane
server image intentionally is not used as a standalone target because it needs
MySQL and Redis. Real installations may report any other independently runnable
application image while keeping the same immutable-tag contract.

## GitLab CI

Pipeline: `.gitlab-ci.yml`

Use protected and masked CI/CD variables `DEVPILOT_CICD_CALLBACK_URL` and `DEVPILOT_CICD_CALLBACK_SECRET`. The production callback job is manual on the default branch, giving an explicit approval gate. Images are written below `$CI_REGISTRY_IMAGE/devpilot` and tagged with the commit SHA. DevPilot verifies the callback and triggers the configured deployment provider.

## Woodpecker

Pipeline: `.woodpecker/cicd.yaml`

Create repository secrets named `registry`, `registry_username`, `registry_password`, `registry_server_repository`, `registry_web_repository`, `registry_agent_repository`, `devpilot_cicd_callback_url`, and `devpilot_cicd_callback_secret`. Woodpecker is the lightweight self-hosted alternative and builds all three images with the same immutable tag contract.

## Coolify / Dokploy target

The recommended `API · exact image` mode needs the provider base URL, a minimum-privilege API token, and the application UUID/ID. DevPilot encrypts credentials at rest and never returns them to the browser. For each successful pipeline callback it updates the provider target to the callback's exact immutable image before starting deployment:

- Coolify: update application image name/tag, then deploy the application UUID.
- Dokploy: update `dockerImage`, then deploy the application ID.

Webhook mode remains available for installations where the provider owns image selection. It cannot provide the same exact-image guarantee, so API mode is preferred.

### Isolated local Dokploy acceptance lab

For local end-to-end acceptance on a Docker workstation, run:

```bash
make dokploy-lab-start
make dokploy-lab-status
```

The panel is exposed at `http://127.0.0.1:19000`; its Traefik HTTP and HTTPS
entry points are exposed at ports `19080` and `19443`. This lab runs an isolated
Docker-in-Docker Swarm so it does not change the workstation's own Swarm state.
Its generated Postgres and authentication secrets live only in Docker Secrets.
Use `make dokploy-lab-stop` to stop it while preserving its volumes and secrets.

GitHub Actions treats each `sha-<commit>` registry tag as immutable. A rerun
reuses and verifies an existing amd64/arm64 manifest instead of overwriting it.
The pinned image is reused after the first successful download; set
`DEVPILOT_DOKPLOY_REFRESH=true` only when an explicit registry refresh is
required.

This is an acceptance environment only. The supported production deployment
remains Dokploy's installer on a dedicated Linux host with backups, firewall,
DNS and TLS configured; do not treat the nested lab as a production server.

### Restricted callback tunnel for local acceptance

GitHub-hosted runners cannot call `127.0.0.1`. For a time-bounded local
acceptance run, expose `deploy/nginx/callback-only.conf` instead of the complete
DevPilot gateway. The gateway allows `POST` only on
`/api/cicd/webhooks/<application-code>`, rate-limits requests, caps the request
body, and returns `404` for the console and every other API. Point a temporary
Cloudflare Tunnel or equivalent at this gateway, store the resulting absolute
callback URL as a protected CI environment secret, and stop the tunnel after
the acceptance run. Account-less quick tunnels have no uptime guarantee and
must not be treated as the production ingress.

For production, publish the same callback-only route through managed DNS/TLS,
or restrict it at an existing reverse proxy. CI still signs the exact request
body; the restricted ingress complements rather than replaces HMAC validation.

Import `deploy/docker-compose.yml` together with `deploy/compose.registry.yml`, then configure:

```dotenv
DEVPILOT_IMAGE_PREFIX=ghcr.io/<owner>/devpilot
DEVPILOT_IMAGE_TAG=sha-<12-character-commit>
```

Keep database, Redis, JWT and master-key variables in the deployment platform's encrypted secret store. Never commit `.env`.

CI signs the exact JSON payload with HMAC-SHA256. DevPilot rejects forged callbacks, mismatched branches, failed gates, mutable image tags, and `sha-*` tags that do not match the reported commit. A first successful event is stored idempotently and triggers Coolify/Dokploy exactly once.

After provider acceptance, a deployment is not marked healthy immediately. DevPilot waits for a fresh Agent health result collected after the deployment stabilization window. A healthy result records the exact image as a verified release. An unhealthy result or timeout fails the deployment and, when enabled, deploys the latest previously verified healthy image. Administrators can also choose any healthy deployment in the CI/CD page and trigger a manual rollback.

## Local verification

```bash
make test
make compose-config
make cicd-verify
```

`compose.registry.yml` intentionally requires an explicit image prefix and immutable tag. `scripts/verify-cicd.sh` checks gate dependencies, tag policy, secret hygiene and the final Compose merge.

## Rollback contract

Never retag an existing `sha-*` image. DevPilot rolls back by sending the stored exact image URI to the provider API. Database migrations must remain backward compatible across at least one released version so the previous application image can start safely.
