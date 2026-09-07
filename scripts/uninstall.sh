#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

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

# Host-wide lock: different install directories can target the same Compose
# project. FD 7 is inherited by upgrade -> backup; never unlink this inode.
command -v flock >/dev/null || { printf '%s\n' 'flock is required for safe maintenance.' >&2; exit 1; }
host_lock=/run/devpilot-maintenance.lock
[[ ! -L "$host_lock" && ( ! -e "$host_lock" || -f "$host_lock" ) ]] || { printf '%s\n' 'Unsafe host maintenance lock.' >&2; exit 1; }
umask 077
if [[ ! /proc/$$/fd/7 -ef "$host_lock" ]]; then
  exec 7>>"$host_lock"
fi
[[ "$(stat -c '%u:%a:%h' "$host_lock")" == '0:600:1' ]] || { printf '%s\n' 'Host maintenance lock must be root-owned, mode 0600, with one link.' >&2; exit 1; }
flock -n 7 || { printf '%s\n' 'Another DevPilot installation or maintenance operation is running on this host. No service changes made; retry after it finishes.' >&2; exit 1; }

command -v flock >/dev/null || { printf '%s\n' 'flock is required for safe maintenance.' >&2; exit 1; }
[[ ! -L "$INSTALL_DIR/.devpilot-maintenance.lock" ]] || { printf '%s\n' 'Maintenance lock must not be a symlink.' >&2; exit 1; }
# Uninstall is never a child of upgrade/backup: obtain its own lock description.
exec 9>>"$INSTALL_DIR/.devpilot-maintenance.lock"
flock -n 9 || { printf '%s\n' 'Another installation or maintenance operation is running. Nothing was removed.' >&2; exit 1; }
compose_saved() (
  grep -qx 'name: devpilot' "$INSTALL_DIR/docker-compose.yml" || {
    printf '%s\n' 'Expected installer-managed Compose project name: devpilot. Refusing a renamed or custom project.' >&2; exit 1;
  }
  # Installer-managed services always belong to devpilot. A parent shell must
  # not redirect maintenance to another project, file, profile or env file.
  unset COMPOSE_PROJECT_NAME COMPOSE_FILE COMPOSE_PROFILES COMPOSE_ENV_FILES COMPOSE_DISABLE_ENV_FILE
  while IFS='=' read -r config_key _; do
    [[ "$config_key" =~ ^[A-Z][A-Z0-9_]*$ ]] || continue
    unset "$config_key"
  done < "$INSTALL_DIR/.env"
  docker compose --project-name devpilot --env-file "$INSTALL_DIR/.env" -f "$INSTALL_DIR/docker-compose.yml" "$@"
)

cd "$INSTALL_DIR"
compose_saved config -q
if [[ "$PURGE" == "true" ]]; then
  compose_saved down --volumes --remove-orphans
else
  compose_saved down --remove-orphans
fi

printf 'DevPilot containers removed. Configuration remains at %s.\n' "$INSTALL_DIR"
if [[ "$PURGE" == "true" ]]; then
  printf '%s\n' "Persistent MySQL and Redis volumes were permanently removed."
else
  printf '%s\n' 'Persistent volumes were retained. Keep the existing configuration and encryption key for recovery.'
fi
