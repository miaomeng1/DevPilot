#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root_dir"

required_files=(
  .github/workflows/cicd.yml
  .gitlab-ci.yml
  .woodpecker/cicd.yaml
  deploy/compose.registry.yml
  deploy/nginx/callback-only.conf
  scripts/cicd/notify-devpilot.sh
  scripts/backup.sh
  scripts/restore.sh
  scripts/upgrade.sh
)

for path in "${required_files[@]}"; do
  test -s "$path" || { echo "missing required CI/CD artifact: $path" >&2; exit 1; }
done

grep -q 'needs: \[quality, security\]' .github/workflows/cicd.yml
grep -q 'needs: \[quality, security, images\]' .github/workflows/cicd.yml
grep -q 'sha-${GITHUB_SHA::12}' .github/workflows/cicd.yml
grep -Fq 'CI_IMAGE_URI: ${{ env.IMAGE_PREFIX }}-web' .github/workflows/cicd.yml
grep -q 'needs: \[server-test, web-test, agent-test, security-gate\]' .gitlab-ci.yml
grep -q 'DEVPILOT_IMAGE_TAG is required' deploy/compose.registry.yml
grep -q 'X-DevPilot-Signature: sha256=' scripts/cicd/notify-devpilot.sh
grep -q 'single-transaction' scripts/backup.sh
grep -q 'DEV_PILOT_MASTER_KEY does not match' scripts/restore.sh
grep -q 'limit_except POST' deploy/nginx/callback-only.conf
grep -q 'return 404' deploy/nginx/callback-only.conf
bash -n scripts/cicd/notify-devpilot.sh scripts/install.sh scripts/install-agent.sh \
  scripts/upgrade.sh scripts/uninstall.sh scripts/backup.sh scripts/restore.sh scripts/test-maintenance.sh

if grep -RInE '(password|token|secret)[[:space:]]*[:=][[:space:]]*[A-Za-z0-9_./+-]{16,}' \
  .github .gitlab-ci.yml .woodpecker deploy/compose.registry.yml \
  | grep -vE 'from_secret|secrets\.|\$\{[A-Z0-9_]+\}'; then
  echo 'possible hard-coded CI/CD secret detected' >&2
  exit 1
fi

docker compose --env-file .env.example \
  -f deploy/docker-compose.yml \
  -f deploy/compose.registry.yml \
  config --quiet \
  2> >(grep -v 'DEVPILOT_IMAGE_PREFIX is required\|DEVPILOT_IMAGE_TAG is required' >&2) || {
    DEVPILOT_IMAGE_PREFIX=ghcr.io/example/devpilot DEVPILOT_IMAGE_TAG=sha-000000000000 \
      docker compose --env-file .env.example -f deploy/docker-compose.yml \
      -f deploy/compose.registry.yml config --quiet
  }

echo 'CI/CD configuration verification passed.'
