#!/usr/bin/env bash
# Isolated container only: fake downloads/systemd, but writes a fixture unit in /etc.
set -Eeuo pipefail
[[ "${DEVPILOT_AGENT_INSTALL_FIXTURE:-}" == isolated-container-only ]] || exit 2
root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
test_root="$(mktemp -d)"
trap 'rm -rf -- "$test_root"' EXIT
mkdir -p "$test_root/bin" "$test_root/config" /etc/systemd/system
export DP_AGENT_FIXTURE_CONFIG="$test_root/config/config.yaml"
cat >"$test_root/bin/curl" <<'EOF'
#!/usr/bin/env bash
set -eu
output=""
while (($#)); do
  if [[ "$1" == -o ]]; then output="$2"; shift 2; else shift; fi
done
if [[ "$output" == */SHA256SUMS ]]; then
  (cd "$(dirname "$output")" && sha256sum devpilot-agent-linux-*) >"$output"
else
  printf '#!/bin/sh\nexit 0\n' >"$output"
fi
EOF
cat >"$test_root/bin/cat" <<'EOF'
#!/usr/bin/env bash
set -eu
target="$(readlink /proc/$$/fd/1)"
if [[ "$target" == "$DP_AGENT_FIXTURE_CONFIG" || "$target" == "$DP_AGENT_FIXTURE_CONFIG".tmp.* ]]; then
  [[ "$(stat -c %a "$target")" == 600 ]] || { echo 'Configuration writable before restrictive permissions are applied' >&2; exit 88; }
  [[ "${DP_AGENT_FIXTURE_FAIL_WRITE:-false}" != true ]] || exit 89
fi
exec /bin/cat "$@"
EOF
printf '#!/bin/sh\nexit 0\n' >"$test_root/bin/systemctl"
printf '#!/bin/sh\nexit 0\n' >"$test_root/bin/sleep"
chmod +x "$test_root/bin/"*
export PATH="$test_root/bin:$PATH"
args=(--server http://127.0.0.1:18081 --token synthetic-agent-token-not-a-real-secret --disable-nginx --install-dir "$test_root/agent" --config "$DP_AGENT_FIXTURE_CONFIG")
(umask 022; bash "$root_dir/scripts/install-agent.sh" "${args[@]}")
[[ "$(stat -c %a "$DP_AGENT_FIXTURE_CONFIG")" == 600 ]]
printf 'old config\n' >"$DP_AGENT_FIXTURE_CONFIG"
chmod 0644 "$DP_AGENT_FIXTURE_CONFIG"
if DP_AGENT_FIXTURE_FAIL_WRITE=true bash "$root_dir/scripts/install-agent.sh" "${args[@]}"; then exit 1; fi
[[ "$(/bin/cat "$DP_AGENT_FIXTURE_CONFIG")" == 'old config' ]]
(umask 022; bash "$root_dir/scripts/install-agent.sh" "${args[@]}")
[[ "$(stat -c %a "$DP_AGENT_FIXTURE_CONFIG")" == 600 ]]
[[ -z "$(find "$test_root/config" -name '*.tmp.*' -print -quit)" ]]
echo 'PASS: private configuration before write; failed writes preserve prior config; replacement is private.'
