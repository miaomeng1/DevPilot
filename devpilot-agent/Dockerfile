FROM --platform=$BUILDPLATFORM golang:1.25-alpine AS build
ARG VERSION=dev
ARG COMMIT=none
ARG BUILD_DATE=unknown
ARG TARGETOS
ARG TARGETARCH
WORKDIR /src
COPY go.mod go.sum* ./
RUN go mod download
COPY . .
RUN target_os="${TARGETOS:-linux}" && \
    target_arch="${TARGETARCH:-$(go env GOARCH)}" && \
    CGO_ENABLED=0 GOOS="$target_os" GOARCH="$target_arch" go build \
    -trimpath \
    -ldflags="-s -w -X github.com/devpilot/devpilot-agent/internal/version.Version=${VERSION} -X github.com/devpilot/devpilot-agent/internal/version.Commit=${COMMIT} -X github.com/devpilot/devpilot-agent/internal/version.Date=${BUILD_DATE}" \
    -o /out/devpilot-agent ./cmd/devpilot-agent

FROM alpine:3.22
RUN apk add --no-cache ca-certificates tzdata && addgroup -S devpilot && adduser -S -G devpilot devpilot
COPY --from=build /out/devpilot-agent /usr/local/bin/devpilot-agent
COPY config.example.yaml /etc/devpilot-agent/config.yaml
USER devpilot
ENTRYPOINT ["/usr/local/bin/devpilot-agent"]
