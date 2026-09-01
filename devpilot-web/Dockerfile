FROM golang:1.25-alpine AS agent-build
ARG VERSION=1.0.0
ARG COMMIT=none
ARG BUILD_DATE=unknown
WORKDIR /src
COPY devpilot-agent/go.mod devpilot-agent/go.sum ./
RUN go mod download
COPY devpilot-agent/ .
RUN mkdir -p /out && \
    CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath \
      -ldflags="-s -w -X github.com/devpilot/devpilot-agent/internal/version.Version=${VERSION} -X github.com/devpilot/devpilot-agent/internal/version.Commit=${COMMIT} -X github.com/devpilot/devpilot-agent/internal/version.Date=${BUILD_DATE}" \
      -o /out/devpilot-agent-linux-amd64 ./cmd/devpilot-agent && \
    CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath \
      -ldflags="-s -w -X github.com/devpilot/devpilot-agent/internal/version.Version=${VERSION} -X github.com/devpilot/devpilot-agent/internal/version.Commit=${COMMIT} -X github.com/devpilot/devpilot-agent/internal/version.Date=${BUILD_DATE}" \
      -o /out/devpilot-agent-linux-arm64 ./cmd/devpilot-agent && \
    cd /out && sha256sum devpilot-agent-linux-amd64 devpilot-agent-linux-arm64 > SHA256SUMS

FROM node:22-alpine AS build
WORKDIR /app
COPY devpilot-web/package*.json ./
RUN npm ci
COPY devpilot-web/ .
RUN npm run build

FROM nginx:1.29-alpine
COPY devpilot-web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
COPY --from=agent-build /out /usr/share/nginx/html/downloads
COPY scripts/install.sh /usr/share/nginx/html/install.sh
COPY scripts/install-agent.sh /usr/share/nginx/html/install-agent.sh
COPY scripts/upgrade.sh scripts/uninstall.sh scripts/backup.sh scripts/restore.sh /usr/share/nginx/html/tools/
RUN chown -R nginx:nginx /var/cache/nginx /var/run /etc/nginx/conf.d /usr/share/nginx/html && \
    touch /var/run/nginx.pid && chown nginx:nginx /var/run/nginx.pid
USER nginx
EXPOSE 8080
