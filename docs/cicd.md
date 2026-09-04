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

## Repository onboarding generator

After saving an application CI/CD configuration, open **仓库接入向导 · Repository onboarding** in the release center. Choose Node.js, Java, Go, or a Docker multi-stage test preset; confirm the target image repository; then copy or download the generated file:

- GitHub Actions: `.github/workflows/devpilot.yml`
- GitLab CI: `.gitlab-ci.yml`
- Woodpecker: `.woodpecker/devpilot.yml`

The generated pipeline includes its own quality gate, Trivy source/dependency/secret/IaC scan, immutable `sha-<commit>` image build, serialized production task, and signed DevPilot callback. It never embeds the callback secret. Create every listed variable in the CI platform's protected secret store and use the one-time secret shown by DevPilot. If the generated callback URL contains `localhost` or `127.0.0.1`, first expose DevPilot through a production DNS name with HTTPS and regenerate the file; a hosted CI runner cannot reach a loopback address.

## Runtime environment and secrets

The release center includes reusable Web, database, and S3-compatible templates plus a custom variable editor. DevPilot encrypts every value at rest, never returns Secret values, removes values from audit payloads, and uses an optimistic Revision check so an older browser tab cannot overwrite a newer edit. The review panel shows keys that will be added, changed, or removed before Save is enabled.

## Release preflight

Before a release, DevPilot evaluates the pipeline contract, provider credentials, signed callback, automatic deployment policy, Agent connectivity, health endpoint, recent disk capacity, managed environment variables, deployment concurrency, current container, and latest artifact. The CI/CD page presents every result as `PASS`, `WARN`, or `BLOCK`, with a direct action for each fixable item. A blocking result keeps the immutable image in the durable queue instead of contacting the deployment provider; the queue reconciler automatically continues after the condition recovers.

This follows the same separation used by mature delivery systems: [GitHub Environments](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/control-deployments) enforce protection rules before a job can access deployment secrets, while runtime readiness is verified independently. Health checks are mandatory for DevPilot's post-deployment verdict because an accepted Provider request does not prove that the replacement is ready to serve traffic; [Coolify's health-check guidance](https://coolify.io/docs/knowledge-base/health-checks) makes the same distinction for routing and rolling updates.

## Environment promotion

Model long-lived `DEV`, `TEST`, `STAGING`, and `PRODUCTION` targets as separate DevPilot applications. Applications in one promotion path must use the same repository but keep their own deployment Provider resource, encrypted runtime variables, access URL, health endpoint, and rollback history. After a lower environment has a `HEALTHY` deployment, open **Environment promotion** and select a higher target. DevPilot promotes the exact digest or `sha-*` image without rebuilding it, records the source application and deployment, runs the target's release preflight, and performs a fresh target health verification.

This mirrors the environment tiers and deployment history described by [GitLab Environments](https://docs.gitlab.com/ci/environments/) while keeping the flow small enough for a personal server. Pull-request preview environments remain Provider-managed for now; when using Coolify, follow its [Preview Deployments](https://next.coolify.io/docs/applications/deployments/preview-deployments) guidance and never reuse production credentials for untrusted preview code.

For **Coolify API mode**, DevPilot uses the official per-application [list](https://next.coolify.io/docs/api/endpoints/applications/list-envs-by-application-uuid), [create](https://next.coolify.io/docs/api/endpoints/applications/create-env-by-application-uuid), [update](https://next.coolify.io/docs/api/endpoints/applications/update-env-by-application-uuid), and [delete](https://next.coolify.io/docs/api/endpoints/applications/delete-env-by-application-uuid) endpoints. It upserts desired keys and deletes only keys that a previous successful DevPilot sync managed. Give the provider token the minimum `read:sensitive`, `write`, and `deploy` scopes required by these operations. A dirty Revision is synchronized before the image is changed, so a failed environment sync blocks that release.

DevPilot intentionally does not automate Dokploy environment replacement yet. Dokploy's current `application.saveEnvironment` operation replaces the complete block; its upstream project documents that partial automation can [delete existing secrets](https://github.com/Dokploy/dokploy/issues/4525). The UI explains this limitation and the deployment gate stops rather than silently running with unsynchronized values.

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

Deployments are serialized per application. If another successful pipeline arrives while a release, verification, or rollback is active, DevPilot records it as `QUEUED` instead of racing the provider. The queue is stored in MySQL, survives a control-plane restart, and starts the oldest pending release automatically after the active deployment reaches a terminal state. Manual rollback is rejected while that application has an active deployment.

Before starting a new release, DevPilot also checks fresh Agent disk telemetry for the target server. At 95% utilization, or below 2 GiB free on a normal-sized disk, the release stays `QUEUED` with an actionable reason. It resumes automatically after the next safe metric arrives. Emergency rollback remains available because restoring a known healthy version can be more important than the new-release guard.

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
