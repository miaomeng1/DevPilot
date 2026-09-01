#!/usr/bin/env bash
set -Eeuo pipefail

INSTALL_DIR="${DEVPILOT_INSTALL_DIR:-/opt/devpilot}"
[[ "${EUID}" -eq 0 ]] || { printf '%s\n' "Run this script as root." >&2; exit 1; }
[[ "$INSTALL_DIR" == /* && "$INSTALL_DIR" != "/" && "$INSTALL_DIR" != *[[:space:]]* ]] || { printf '%s\n' "Install directory must be a non-root absolute path without whitespace." >&2; exit 2; }
[[ -f "$INSTALL_DIR/docker-compose.yml" && -f "$INSTALL_DIR/.env" ]] || { printf '%s\n' "DevPilot is not installed in ${INSTALL_DIR}." >&2; exit 1; }

cd "$INSTALL_DIR"
docker compose --env-file .env pull
docker compose --env-file .env up -d --remove-orphans
docker compose --env-file .env ps
printf '%s\n' "DevPilot upgrade applied. Database migrations run automatically at server startup."
