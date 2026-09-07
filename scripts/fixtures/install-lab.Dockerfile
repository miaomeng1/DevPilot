# Local acceptance only. Use start-rootless-install-lab.sh for volatile runtime state.
# Namespace-root installation is not a real host reboot/systemd acceptance test.
FROM docker:28-dind-rootless
USER root
RUN apk add --no-cache bash curl openssl iproute2 util-linux jq ca-certificates
COPY scripts/fixtures/rootless-lab-exec.sh /usr/local/bin/devpilot-lab-exec
RUN chmod 0755 /usr/local/bin/devpilot-lab-exec
USER rootless
CMD ["dockerd", "--host=unix:///run/user/1000/docker.sock"]
