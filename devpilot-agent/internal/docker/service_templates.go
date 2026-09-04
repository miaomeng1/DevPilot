package docker

import (
	"context"
	"fmt"
	"io"
	"net"
	"regexp"
	"strconv"
	"strings"

	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/api/types/image"
	"github.com/docker/docker/api/types/mount"
	"github.com/docker/docker/api/types/volume"
	"github.com/docker/docker/errdefs"
	"github.com/docker/go-connections/nat"
)

var instanceNamePattern = regexp.MustCompile(`^[a-z][a-z0-9-]{1,62}[a-z0-9]$`)

type serviceTemplate struct {
	image       string
	port        int
	memoryBytes int64
	environment []string
	volumes     []templateVolume
}

type templateVolume struct {
	suffix string
	target string
}

var serviceTemplates = map[string]serviceTemplate{
	"uptime-kuma": {
		image: "louislam/uptime-kuma:2.5.0", port: 3001, memoryBytes: 768 * 1024 * 1024,
		volumes: []templateVolume{{suffix: "data", target: "/app/data"}},
	},
	"gitea": {
		image: "gitea/gitea:1.27.2", port: 3000, memoryBytes: 1024 * 1024 * 1024,
		environment: []string{"USER_UID=1000", "USER_GID=1000"},
		volumes:     []templateVolume{{suffix: "data", target: "/data"}},
	},
	"audiobookshelf": {
		image: "ghcr.io/advplyr/audiobookshelf:2.36.0", port: 80, memoryBytes: 1024 * 1024 * 1024,
		volumes: []templateVolume{
			{suffix: "config", target: "/config"}, {suffix: "metadata", target: "/metadata"},
			{suffix: "audiobooks", target: "/audiobooks"}, {suffix: "podcasts", target: "/podcasts"},
		},
	},
}

func (e *Engine) InstallTemplate(ctx context.Context, templateID, instanceName string, hostPort int,
	timezone string) (string, error) {
	spec, ok := serviceTemplates[templateID]
	if !ok {
		return "", fmt.Errorf("unsupported service template %q", templateID)
	}
	if !instanceNamePattern.MatchString(instanceName) {
		return "", fmt.Errorf("invalid service instance name")
	}
	if hostPort < 1024 || hostPort > 65535 {
		return "", fmt.Errorf("host port must be between 1024 and 65535")
	}
	if !validTimezone(timezone) {
		return "", fmt.Errorf("invalid timezone")
	}
	containerName := "devpilot-" + instanceName
	if existing, inspectErr := e.client.ContainerInspect(ctx, containerName); inspectErr == nil {
		if existing.Config == nil || existing.Config.Labels["com.devpilot.template.id"] != templateID {
			return "", fmt.Errorf("container name %q is already in use", containerName)
		}
		if existing.State != nil && !existing.State.Running {
			if err := e.client.ContainerStart(ctx, existing.ID, container.StartOptions{}); err != nil {
				return "", fmt.Errorf("start existing template container: %w", err)
			}
		}
		return existing.ID, nil
	} else if !errdefs.IsNotFound(inspectErr) {
		return "", fmt.Errorf("inspect template container: %w", inspectErr)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:"+strconv.Itoa(hostPort))
	if err != nil {
		return "", fmt.Errorf("loopback port %d is unavailable: %w", hostPort, err)
	}
	if err = listener.Close(); err != nil {
		return "", fmt.Errorf("release loopback port probe: %w", err)
	}

	pull, err := e.client.ImagePull(ctx, spec.image, image.PullOptions{})
	if err != nil {
		return "", fmt.Errorf("pull image %s: %w", spec.image, err)
	}
	if _, err = io.Copy(io.Discard, pull); err != nil {
		pull.Close()
		return "", fmt.Errorf("read image pull result: %w", err)
	}
	if err = pull.Close(); err != nil {
		return "", fmt.Errorf("close image pull stream: %w", err)
	}

	mounts := make([]mount.Mount, 0, len(spec.volumes))
	for _, item := range spec.volumes {
		name := "devpilot-" + instanceName + "-" + item.suffix
		if _, err = e.client.VolumeCreate(ctx, volume.CreateOptions{Name: name, Labels: map[string]string{
			"com.devpilot.managed": "true", "com.devpilot.template.id": templateID,
			"com.devpilot.instance": instanceName,
		}}); err != nil {
			return "", fmt.Errorf("create persistent volume %s: %w", name, err)
		}
		mounts = append(mounts, mount.Mount{Type: mount.TypeVolume, Source: name, Target: item.target})
	}

	config, hostConfig := buildTemplateConfiguration(templateID, instanceName, timezone, hostPort, spec, mounts)
	created, err := e.client.ContainerCreate(ctx, config, hostConfig, nil, nil, containerName)
	if err != nil {
		return "", fmt.Errorf("create template container: %w", err)
	}
	if err = e.client.ContainerStart(ctx, created.ID, container.StartOptions{}); err != nil {
		_ = e.client.ContainerRemove(context.Background(), created.ID,
			container.RemoveOptions{Force: true, RemoveVolumes: false})
		return "", fmt.Errorf("start template container (is port %d available?): %w", hostPort, err)
	}
	return created.ID, nil
}

func buildTemplateConfiguration(templateID, instanceName, timezone string, hostPort int, spec serviceTemplate,
	mounts []mount.Mount) (*container.Config, *container.HostConfig) {
	containerPort := nat.Port(strconv.Itoa(spec.port) + "/tcp")
	initProcess := true
	config := &container.Config{
		Image:        spec.image,
		Env:          append([]string{"TZ=" + timezone}, spec.environment...),
		ExposedPorts: nat.PortSet{containerPort: struct{}{}},
		Labels: map[string]string{
			"com.devpilot.managed": "true", "com.devpilot.template.id": templateID,
			"com.devpilot.instance": instanceName,
		},
	}
	hostConfig := &container.HostConfig{
		PortBindings: nat.PortMap{containerPort: []nat.PortBinding{{
			HostIP: "127.0.0.1", HostPort: strconv.Itoa(hostPort),
		}}},
		RestartPolicy: container.RestartPolicy{Name: "unless-stopped"},
		SecurityOpt:   []string{"no-new-privileges:true"},
		LogConfig: container.LogConfig{Type: "json-file", Config: map[string]string{
			"max-size": "10m", "max-file": "3",
		}},
		Resources: container.Resources{Memory: spec.memoryBytes},
		Mounts:    mounts,
		Init:      &initProcess,
	}
	return config, hostConfig
}

func validTimezone(value string) bool {
	if value == "" || len(value) > 64 || strings.Contains(value, "..") || strings.HasPrefix(value, "/") {
		return false
	}
	for _, part := range strings.Split(value, "/") {
		if part == "" {
			return false
		}
		for _, character := range part {
			if !((character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z') ||
				(character >= '0' && character <= '9') || strings.ContainsRune("_+.-", character)) {
				return false
			}
		}
	}
	return true
}
