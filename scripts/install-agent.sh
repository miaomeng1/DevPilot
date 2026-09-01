#!/usr/bin/env bash
set -Eeuo pipefail

SERVER_URL=""
AGENT_TOKEN=""
INSTALL_DIR="/opt/devpilot-agent"
CONFIG_PATH="/etc/devpilot-agent/config.yaml"
NGINX_CONFIG_PATH="/etc/nginx/conf.d"
NGINX_ENABLED="true"

usage() {
  printf '%s\n' "Usage: install-agent.sh --server URL --token TOKEN [--disable-nginx] [--nginx-config-path PATH]"
}

while (($#)); do
  case "$1" in
    --server) SERVER_URL="${2:-}"; shift 2 ;;
    --token) AGENT_TOKEN="${2:-}"; shift 2 ;;
    --install-dir) INSTALL_DIR="${2:-}"; shift 2 ;;
    --config) CONFIG_PATH="${2:-}"; shift 2 ;;
    --nginx-config-path) NGINX_CONFIG_PATH="${2:-}"; shift 2 ;;
    --disable-nginx) NGINX_ENABLED="false"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) printf 'Unknown option: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
done

[[ "$(uname -s)" == "Linux" ]] || { printf '%s\n' "DevPilot Agent supports Linux only." >&2; exit 1; }
[[ "${EUID}" -eq 0 ]] || { printf '%s\n' "Run this installer as root." >&2; exit 1; }
SERVER_URL_PATTERN='^https?://[^/?#[:space:]]+/?$'
[[ "$SERVER_URL" =~ $SERVER_URL_PATTERN ]] || { printf '%s\n' "--server must be an HTTP(S) origin without a path, query, or fragment." >&2; exit 2; }
[[ ${#AGENT_TOKEN} -ge 16 && "$AGENT_TOKEN" != *$'\n'* ]] || { printf '%s\n' "--token is missing or invalid." >&2; exit 2; }
[[ "$INSTALL_DIR" == /* && "$INSTALL_DIR" != "/" && "$INSTALL_DIR" != *[[:space:]]* ]] || { printf '%s\n' "--install-dir must be a non-root absolute path without whitespace." >&2; exit 2; }
[[ "$CONFIG_PATH" == /* && "$CONFIG_PATH" != "/" && "$CONFIG_PATH" != *[[:space:]]* ]] || { printf '%s\n' "--config must be a non-root absolute path without whitespace." >&2; exit 2; }
[[ "$NGINX_CONFIG_PATH" == /* && "$NGINX_CONFIG_PATH" != "/" && "$NGINX_CONFIG_PATH" != *[[:space:]]* ]] || { printf '%s\n' "--nginx-config-path must be a non-root absolute path without whitespace." >&2; exit 2; }
command -v curl >/dev/null || { printf '%s\n' "curl is required." >&2; exit 1; }
command -v sha256sum >/dev/null || { printf '%s\n' "sha256sum is required." >&2; exit 1; }
command -v systemctl >/dev/null || { printf '%s\n' "systemd is required." >&2; exit 1; }

case "$(uname -m)" in
  x86_64|amd64) ARCH="amd64" ;;
  aarch64|arm64) ARCH="arm64" ;;
  *) printf 'Unsupported architecture: %s\n' "$(uname -m)" >&2; exit 1 ;;
esac

SERVER_URL="${SERVER_URL%/}"
ARTIFACT="devpilot-agent-linux-${ARCH}"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TEMP_DIR"' EXIT

printf 'Downloading DevPilot Agent for linux/%s...\n' "$ARCH"
curl -fsSL --retry 3 --connect-timeout 10 "${SERVER_URL}/downloads/${ARTIFACT}" -o "${TEMP_DIR}/${ARTIFACT}"
curl -fsSL --retry 3 --connect-timeout 10 "${SERVER_URL}/downloads/SHA256SUMS" -o "${TEMP_DIR}/SHA256SUMS"
EXPECTED_LINE="$(grep -E "^[0-9a-fA-F]{64}[[:space:]]+${ARTIFACT}$" "${TEMP_DIR}/SHA256SUMS" || true)"
[[ -n "$EXPECTED_LINE" ]] || { printf '%s\n' "The release checksum is missing." >&2; exit 1; }
(cd "$TEMP_DIR" && printf '%s\n' "$EXPECTED_LINE" | sha256sum -c -)

install -d -m 0755 "$INSTALL_DIR"
install -m 0755 "${TEMP_DIR}/${ARTIFACT}" "${INSTALL_DIR}/devpilot-agent"
install -d -m 0750 "$(dirname "$CONFIG_PATH")"

yaml_quote() {
  local value=${1//"'"/"''"}
  printf "'%s'" "$value"
}

cat >"$CONFIG_PATH" <<EOF
server:
  url: $(yaml_quote "$SERVER_URL")

agent:
  token: $(yaml_quote "$AGENT_TOKEN")

collect:
  interval: 10

nginx:
  enabled: ${NGINX_ENABLED}
  configPath: $(yaml_quote "$NGINX_CONFIG_PATH")
EOF
chmod 0600 "$CONFIG_PATH"

cat >/etc/systemd/system/devpilot-agent.service <<EOF
[Unit]
Description=DevPilot Agent
Documentation=${SERVER_URL}
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=simple
ExecStart=${INSTALL_DIR}/devpilot-agent -config ${CONFIG_PATH}
Restart=always
RestartSec=5
TimeoutStopSec=20
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now devpilot-agent.service
sleep 1
systemctl --no-pager --full status devpilot-agent.service || {
  journalctl -u devpilot-agent.service -n 50 --no-pager >&2
  exit 1
}

printf '\nDevPilot Agent installed successfully.\nServer: %s\nConfig: %s\n' "$SERVER_URL" "$CONFIG_PATH"
