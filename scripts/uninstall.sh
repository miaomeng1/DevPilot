#!/usr/bin/env bash
set -Eeuo pipefail

INSTALL_DIR="${DEVPILOT_INSTALL_DIR:-/opt/devpilot}"
PURGE="false"
ASSUME_YES="false"
while (($#)); do
  case "$1" in
    --purge) PURGE="true"; shift ;;
    --yes) ASSUME_YES="true"; shift ;;
    --install-dir) INSTALL_DIR="${2:-}"; shift 2 ;;
    -h|--help) printf '%s\n' "Usage: uninstall.sh [--purge] [--yes] [--install-dir PATH]"; exit 0 ;;
    *) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
  esac
done

[[ "${EUID}" -eq 0 ]] || { printf '%s\n' "Run this script as root." >&2; exit 1; }
[[ "$INSTALL_DIR" == /* && "$INSTALL_DIR" != "/" && "$INSTALL_DIR" != *[[:space:]]* ]] || { printf '%s\n' "Install directory must be a non-root absolute path without whitespace." >&2; exit 2; }
[[ -f "$INSTALL_DIR/docker-compose.yml" && -f "$INSTALL_DIR/.env" ]] || { printf '%s\n' "DevPilot is not installed in ${INSTALL_DIR}." >&2; exit 1; }

if [[ "$ASSUME_YES" != "true" ]]; then
  prompt="Stop and remove DevPilot containers"
  [[ "$PURGE" == "true" ]] && prompt+=" and permanently delete MySQL/Redis volumes"
  read -r -p "${prompt}? [y/N] " answer
  [[ "$answer" =~ ^[Yy]$ ]] || { printf '%s\n' "Cancelled."; exit 0; }
fi

cd "$INSTALL_DIR"
if [[ "$PURGE" == "true" ]]; then
  docker compose --env-file .env down --volumes --remove-orphans
else
  docker compose --env-file .env down --remove-orphans
fi

printf 'DevPilot containers removed. Configuration remains at %s.\n' "$INSTALL_DIR"
[[ "$PURGE" == "true" ]] && printf '%s\n' "Persistent MySQL and Redis volumes were permanently removed."
