# Personal service templates

DevPilot's **模板 Templates** workspace installs a deliberately small, reviewed catalog for one-server and home-lab use. It borrows the searchable catalog and generated defaults of [Coolify One-Click Services](https://next.coolify.io/docs/services), the parameterized installation flow of [CapRover One-Click Apps](https://caprover.com/docs/one-click-apps), and the catalog model of [Portainer App Templates](https://docs.portainer.io/user/docker/templates), while keeping DevPilot's no-arbitrary-shell Agent boundary.

## Included catalog

- **Uptime Kuma 2.5.0** — uptime monitoring, notifications, and status pages; one `/app/data` volume; loopback port `3001` by default.
- **Gitea 1.27.2** — private Git hosting, reviews, packages, and Actions; one `/data` volume; Web loopback port `3100`; SSH is not published.
- **Audiobookshelf 2.36.0** — personal audiobook and podcast server; separate config, metadata, audiobook, and podcast volumes; loopback port `13378`.

Every catalog entry includes upstream documentation and source links in the UI. Image versions are explicit rather than floating `latest` tags. DevPilot does not silently replace an installed service when the catalog later changes.

## Installation lifecycle

1. An ADMIN selects a template, online Agent server, display name, lowercase instance name, environment, host port, and IANA timezone.
2. The control plane verifies that Docker is available, applies the existing critical disk guard plus a 256 MB free-memory floor, and rejects active duplicate names or template ports.
3. The matching Agent claims the durable task. Only one template installation runs at a time on that Agent.
4. The Agent independently resolves the built-in template definition, pulls its fixed image version, creates labeled named volumes, and creates the container through the Docker SDK.
5. Runtime defaults include `unless-stopped`, `no-new-privileges`, a memory limit, bounded JSON logs, and one Web binding on `127.0.0.1`.
6. The Agent returns the Docker container ID and immediately uploads a new inventory snapshot.
7. DevPilot changes the task from `DISCOVERING` to `READY` only after that exact container appears in inventory. It then creates an application record with a loopback health URL.

The visible states are `REQUESTED`, `CLAIMED`, `DISCOVERING`, `READY`, and `FAILED`. A claimed task times out after 15 minutes, and both the browser request and Agent result are audited.

## Safe public access

The templates deliberately bind their Web UI only to the server loopback interface. A remote browser cannot reach `http://SERVER_IP:PORT` directly. After initial health verification, create a DevPilot Nginx configuration that proxies a dedicated HTTPS domain to `http://127.0.0.1:PORT`.

This default prevents accidental exposure during the application's first-run setup. Gitea SSH and any internal management/database port remain unpublished. DevPilot never accepts a user-supplied image, Compose document, bind-mounted host path, privileged mode, or shell command through this feature.

## Data and upgrades

Named volumes survive normal container recreation, but they are not backups. Include the template volumes in the host's off-server backup policy and test restore before an upgrade. Read the upstream release notes, create a backup, and verify the service after deliberately changing its image version; the catalog does not auto-upgrade existing containers.
