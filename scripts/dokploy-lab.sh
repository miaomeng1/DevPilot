#!/usr/bin/env bash
set -Eeuo pipefail

# Local acceptance environment only. Dokploy's supported production installer
# must run directly on a Linux host; this script intentionally isolates Swarm
# inside Docker-in-Docker so it cannot change the workstation's Docker Swarm.

command_name="${1:-status}"
lab_name="${DEVPILOT_DOKPLOY_LAB_NAME:-devpilot-dokploy-lab}"
panel_port="${DEVPILOT_DOKPLOY_LAB_PORT:-19000}"
http_port="${DEVPILOT_DOKPLOY_LAB_HTTP_PORT:-19080}"
https_port="${DEVPILOT_DOKPLOY_LAB_HTTPS_PORT:-19443}"
dind_image="${DEVPILOT_DIND_IMAGE:-docker:28-dind}"
dokploy_image="${DEVPILOT_DOKPLOY_IMAGE:-dokploy/dokploy:v0.30.3}"
postgres_image="${DEVPILOT_DOKPLOY_POSTGRES_IMAGE:-postgres:16-alpine}"
traefik_image="${DEVPILOT_DOKPLOY_TRAEFIK_IMAGE:-traefik:v3.6.7}"
docker_volume="${lab_name}-docker"
config_volume="${lab_name}-config"

require_docker() {
  local required_command
  for required_command in docker curl openssl; do
    command -v "$required_command" >/dev/null 2>&1 || {
      printf '%s is required.\n' "$required_command" >&2
      exit 1
    }
  done
  docker info >/dev/null 2>&1 || {
    printf '%s\n' 'Docker daemon is not reachable.' >&2
    exit 1
  }
}

lab_exists() {
  docker container inspect "$lab_name" >/dev/null 2>&1
}

inner_docker() {
  docker exec "$lab_name" docker "$@"
}

wait_for_inner_docker() {
  local attempt
  for attempt in $(seq 1 60); do
    if inner_docker info >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  docker logs --tail 100 "$lab_name" >&2
  printf '%s\n' 'Timed out waiting for the isolated Docker daemon.' >&2
  return 1
}

ensure_secret() {
  local secret_name="$1"
  local secret_format="$2"
  if inner_docker secret inspect "$secret_name" >/dev/null 2>&1; then
    return 0
  fi
  if [[ "$secret_format" == "hex" ]]; then
    openssl rand -hex 32 | tr -d '\n' | docker exec -i "$lab_name" docker secret create "$secret_name" - >/dev/null
  else
    openssl rand -base64 48 | tr -d '\n' | docker exec -i "$lab_name" docker secret create "$secret_name" - >/dev/null
  fi
}

ensure_postgres_image() {
  if inner_docker image inspect "$postgres_image" >/dev/null 2>&1; then
    return 0
  fi
  docker image inspect "$postgres_image" >/dev/null 2>&1 || docker pull "$postgres_image"
  docker save "$postgres_image" | docker exec -i "$lab_name" docker load >/dev/null
}

ensure_postgres_service() {
  if inner_docker service inspect dokploy-postgres >/dev/null 2>&1; then
    inner_docker service update --detach=true --no-resolve-image \
      --image "$postgres_image" dokploy-postgres >/dev/null
    return 0
  fi
  inner_docker service create --detach=true --no-resolve-image \
    --name dokploy-postgres \
    --constraint 'node.role==manager' \
    --network dokploy-network \
    --env POSTGRES_USER=dokploy \
    --env POSTGRES_DB=dokploy \
    --secret source=dokploy_postgres_password,target=/run/secrets/postgres_password \
    --env POSTGRES_PASSWORD_FILE=/run/secrets/postgres_password \
    --mount type=volume,source=dokploy-postgres,target=/var/lib/postgresql/data \
    "$postgres_image" >/dev/null
}

ensure_dokploy_service() {
  local current_image
  if ! inner_docker image inspect "$dokploy_image" >/dev/null 2>&1 || \
      [[ "${DEVPILOT_DOKPLOY_REFRESH:-false}" == "true" ]]; then
    inner_docker pull "$dokploy_image"
  fi
  if inner_docker service inspect dokploy >/dev/null 2>&1; then
    current_image="$(inner_docker service inspect -f '{{.Spec.TaskTemplate.ContainerSpec.Image}}' dokploy)"
    if [[ "$current_image" != "$dokploy_image" || \
          "${DEVPILOT_DOKPLOY_REFRESH:-false}" == "true" ]]; then
      inner_docker service update --detach=true --no-resolve-image \
        --image "$dokploy_image" --force dokploy >/dev/null
    fi
    return 0
  fi
  inner_docker service create --detach=true --no-resolve-image \
    --name dokploy \
    --replicas 1 \
    --network dokploy-network \
    --mount type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock \
    --mount type=bind,source=/etc/dokploy,target=/etc/dokploy \
    --mount type=volume,source=dokploy,target=/root/.docker \
    --secret source=dokploy_postgres_password,target=/run/secrets/postgres_password \
    --secret source=dokploy_auth_secret,target=/run/secrets/dokploy_auth_secret \
    --publish published=3000,target=3000,mode=host \
    --update-parallelism 1 \
    --update-order stop-first \
    --constraint 'node.role==manager' \
    --env RELEASE_TAG=latest \
    --env POSTGRES_PASSWORD_FILE=/run/secrets/postgres_password \
    --env BETTER_AUTH_SECRET_FILE=/run/secrets/dokploy_auth_secret \
    "$dokploy_image" >/dev/null
}

ensure_traefik_service() {
  # Dokploy writes the static and dynamic Traefik configuration while it
  # starts. The production installer also runs this router; the isolated lab
  # has to create it explicitly because it does not execute the host installer.
  docker exec "$lab_name" mkdir -p /etc/dokploy/traefik/dynamic
  if ! inner_docker image inspect "$traefik_image" >/dev/null 2>&1 || \
      [[ "${DEVPILOT_DOKPLOY_REFRESH:-false}" == "true" ]]; then
    inner_docker pull "$traefik_image"
  fi
  if inner_docker service inspect dokploy-traefik >/dev/null 2>&1; then
    inner_docker service update --detach=true --no-resolve-image \
      --image "$traefik_image" --force dokploy-traefik >/dev/null
    return 0
  fi
  inner_docker service create --detach=true --no-resolve-image \
    --name dokploy-traefik \
    --replicas 1 \
    --network dokploy-network \
    --constraint 'node.role==manager' \
    --mount type=bind,source=/etc/dokploy/traefik/traefik.yml,target=/etc/traefik/traefik.yml,readonly \
    --mount type=bind,source=/etc/dokploy/traefik/dynamic,target=/etc/dokploy/traefik/dynamic \
    --mount type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock \
    --publish published=80,target=80,mode=host \
    --publish published=443,target=443,protocol=tcp,mode=host \
    --publish published=443,target=443,protocol=udp,mode=host \
    "$traefik_image" >/dev/null
}

wait_for_services() {
  local attempt replicas traefik_replicas status_code
  for attempt in $(seq 1 90); do
    replicas="$(inner_docker service ls --format '{{.Name}} {{.Replicas}}' | awk '$1=="dokploy" {print $2}')"
    traefik_replicas="$(inner_docker service ls --format '{{.Name}} {{.Replicas}}' | awk '$1=="dokploy-traefik" {print $2}')"
    status_code="$(curl -sS -o /dev/null -w '%{http_code}' --connect-timeout 2 --max-time 5 \
      "http://127.0.0.1:${panel_port}/" || true)"
    if [[ "$replicas" == "1/1" && "$traefik_replicas" == "1/1" && "$status_code" =~ ^(200|302|307)$ ]]; then
      printf 'Dokploy lab ready: panel=http://127.0.0.1:%s router=http://127.0.0.1:%s (HTTP %s)\n' \
        "$panel_port" "$http_port" "$status_code"
      return 0
    fi
    sleep 2
  done
  inner_docker service ps dokploy --no-trunc >&2 || true
  printf '%s\n' 'Dokploy lab did not become ready.' >&2
  return 1
}

start_lab() {
  if ! lab_exists; then
    docker run -d --privileged \
      --name "$lab_name" \
      --restart unless-stopped \
      --env DOCKER_TLS_CERTDIR= \
      --publish "${panel_port}:3000" \
      --publish "${http_port}:80" \
      --publish "${https_port}:443" \
      --volume "${docker_volume}:/var/lib/docker" \
      --volume "${config_volume}:/etc/dokploy" \
      "$dind_image" --storage-driver=overlay2 >/dev/null
  elif [[ "$(docker inspect -f '{{.State.Running}}' "$lab_name")" != "true" ]]; then
    docker start "$lab_name" >/dev/null
  fi

  wait_for_inner_docker
  if [[ "$(inner_docker info --format '{{.Swarm.LocalNodeState}}')" != "active" ]]; then
    advertise_address="$(docker exec "$lab_name" sh -lc "ip -4 -o addr show eth0 | awk '{print \\\$4}' | cut -d/ -f1")"
    inner_docker swarm init --advertise-addr "$advertise_address" >/dev/null
  fi
  inner_docker network inspect dokploy-network >/dev/null 2>&1 || \
    inner_docker network create --driver overlay --attachable dokploy-network >/dev/null
  docker exec "$lab_name" mkdir -p /etc/dokploy
  docker exec "$lab_name" chmod 0777 /etc/dokploy
  ensure_secret dokploy_postgres_password base64
  ensure_secret dokploy_auth_secret hex
  ensure_postgres_image
  ensure_postgres_service
  ensure_dokploy_service
  ensure_traefik_service
  wait_for_services
}

show_status() {
  if ! lab_exists; then
    printf '%s\n' 'Dokploy lab is not created.'
    return 1
  fi
  docker ps -a --filter "name=^/${lab_name}$" --format '{{.Names}} {{.Status}} {{.Ports}}'
  if [[ "$(docker inspect -f '{{.State.Running}}' "$lab_name")" == "true" ]] && \
      inner_docker info >/dev/null 2>&1; then
    inner_docker service ls --format '{{.Name}} {{.Image}} {{.Replicas}}'
    curl -sS -o /dev/null -w "panel_http=%{http_code}\n" --connect-timeout 2 --max-time 5 \
      "http://127.0.0.1:${panel_port}/" || true
  fi
}

stop_lab() {
  if lab_exists && [[ "$(docker inspect -f '{{.State.Running}}' "$lab_name")" == "true" ]]; then
    docker stop "$lab_name" >/dev/null
  fi
  printf '%s\n' 'Dokploy lab stopped; containers, volumes and secrets were preserved.'
}

require_docker
case "$command_name" in
  start) start_lab ;;
  status) show_status ;;
  stop) stop_lab ;;
  *)
    printf 'Usage: %s {start|status|stop}\n' "$0" >&2
    exit 2
    ;;
esac
