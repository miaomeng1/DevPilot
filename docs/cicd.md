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

## GitHub Actions

Workflow: `.github/workflows/cicd.yml`

1. Enable GitHub Actions and Packages for the repository.
2. Create a protected `production` environment. Add required reviewers for manual approval if appropriate.
3. In DevPilot, configure the application's repository, protected branch, deployment provider API and resource ID. Copy the one-time callback secret.
4. Add these protected environment secrets:
   - `DEVPILOT_CICD_CALLBACK_URL` — the absolute DevPilot `/api/cicd/webhooks/<application-code>` URL.
   - `DEVPILOT_CICD_CALLBACK_SECRET` — the one-time secret displayed by DevPilot.
5. Configure the deployment platform to use these images with tag `sha-<commit>`:
   - `ghcr.io/<owner>/devpilot-server`
   - `ghcr.io/<owner>/devpilot-web`
   - `ghcr.io/<owner>/devpilot-agent`
6. Push to `main` or `master`. Pull requests build but never publish or deploy.

The deploy job depends on every image build, and image builds depend on both quality and security jobs. A failed test or scan therefore cannot reach production.

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
