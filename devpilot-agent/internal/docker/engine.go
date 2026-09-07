package docker

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"strings"
	"sync"
	"time"

	"github.com/moby/moby/api/pkg/stdcopy"
	"github.com/moby/moby/api/types/container"
	"github.com/moby/moby/api/types/swarm"
	"github.com/moby/moby/client"
)

type Snapshot struct {
	AgentVersion  string              `json:"agentVersion,omitempty"`
	Available     bool                `json:"available"`
	EngineVersion string              `json:"engineVersion,omitempty"`
	ErrorMessage  string              `json:"errorMessage,omitempty"`
	Images        int                 `json:"images"`
	Volumes       int                 `json:"volumes"`
	Networks      int                 `json:"networks"`
	CollectedAt   time.Time           `json:"collectedAt"`
	Containers    []ContainerSnapshot `json:"containers"`
}

type ContainerSnapshot struct {
	ContainerID    string     `json:"containerId"`
	Name           string     `json:"name"`
	Image          string     `json:"image"`
	State          string     `json:"state"`
	Status         string     `json:"status"`
	Health         string     `json:"health,omitempty"`
	CPUUsage       float64    `json:"cpuUsage"`
	MemoryUsage    uint64     `json:"memoryUsage"`
	MemoryLimit    uint64     `json:"memoryLimit"`
	NetworkRX      uint64     `json:"networkRx"`
	NetworkTX      uint64     `json:"networkTx"`
	IPAddress      string     `json:"ipAddress,omitempty"`
	Ports          []string   `json:"ports"`
	CreatedAt      time.Time  `json:"createdAt"`
	StartedAt      *time.Time `json:"startedAt,omitempty"`
	RestartCount   int        `json:"restartCount"`
	NetworkMode    string     `json:"networkMode,omitempty"`
	ComposeProject string     `json:"composeProject,omitempty"`
	ComposeService string     `json:"composeService,omitempty"`
	RuntimeKey     string     `json:"runtimeKey,omitempty"`
	Volumes        []string   `json:"volumes"`
	Environment    []string   `json:"environment"`
}

type Engine struct {
	client *client.Client
}

func NewEngine() (*Engine, error) {
	engineClient, err := client.New(client.FromEnv)
	if err != nil {
		return nil, fmt.Errorf("create Docker client: %w", err)
	}
	return &Engine{client: engineClient}, nil
}

func (e *Engine) Close() error {
	return e.client.Close()
}

func (e *Engine) Snapshot(ctx context.Context) Snapshot {
	now := time.Now().UTC()
	result := Snapshot{CollectedAt: now, Containers: []ContainerSnapshot{}}
	version, err := e.client.ServerVersion(ctx, client.ServerVersionOptions{})
	if err != nil {
		result.ErrorMessage = truncate(err.Error(), 500)
		return result
	}
	result.Available = true
	result.EngineVersion = version.Version
	containers, err := e.client.ContainerList(ctx, client.ContainerListOptions{All: true})
	if err != nil {
		result.Available = false
		result.ErrorMessage = truncate(err.Error(), 500)
		return result
	}
	if images, imageErr := e.client.ImageList(ctx, client.ImageListOptions{All: true}); imageErr == nil {
		result.Images = len(images.Items)
	}
	if volumes, volumeErr := e.client.VolumeList(ctx, client.VolumeListOptions{}); volumeErr == nil {
		result.Volumes = len(volumes.Items)
	}
	if networks, networkErr := e.client.NetworkList(ctx, client.NetworkListOptions{}); networkErr == nil {
		result.Networks = len(networks.Items)
	}
	result.Containers = e.collectContainers(ctx, containers.Items)
	// Swarm publishes ports on the service, not on individual task containers.
	// Workers without manager permissions retain their container-level inventory.
	if services, serviceErr := e.client.ServiceList(ctx, client.ServiceListOptions{}); serviceErr == nil {
		appendSwarmPorts(result.Containers, services.Items)
	}
	return result
}

func appendSwarmPorts(containers []ContainerSnapshot, services []swarm.Service) {
	byRuntime := make(map[string][]string)
	for _, service := range services {
		for _, port := range service.Endpoint.Ports {
			if port.PublishedPort == 0 {
				continue
			}
			key := "swarm:" + service.Spec.Name
			byRuntime[key] = append(byRuntime[key], fmt.Sprintf("Swarm %s :%d→%d/%s", port.PublishMode, port.PublishedPort, port.TargetPort, port.Protocol))
		}
	}
	for i := range containers {
		containers[i].Ports = append(containers[i].Ports, byRuntime[containers[i].RuntimeKey]...)
	}
}

func (e *Engine) Execute(ctx context.Context, containerID, action string) error {
	switch strings.ToUpper(action) {
	case "START":
		_, err := e.client.ContainerStart(ctx, containerID, client.ContainerStartOptions{})
		return err
	case "STOP":
		timeout := 10
		_, err := e.client.ContainerStop(ctx, containerID, client.ContainerStopOptions{Timeout: &timeout})
		return err
	case "RESTART":
		timeout := 10
		_, err := e.client.ContainerRestart(ctx, containerID, client.ContainerRestartOptions{Timeout: &timeout})
		return err
	case "REMOVE":
		_, err := e.client.ContainerRemove(ctx, containerID, client.ContainerRemoveOptions{RemoveVolumes: false, Force: false})
		return err
	default:
		return fmt.Errorf("unsupported Docker action %q", action)
	}
}

func (e *Engine) StreamLogs(ctx context.Context, containerID string, lines int, follow bool,
	send func(string) error) error {
	if lines != 500 {
		lines = 100
	}
	inspection, err := e.client.ContainerInspect(ctx, containerID, client.ContainerInspectOptions{})
	if err != nil {
		return fmt.Errorf("inspect container for logs: %w", err)
	}
	inspect := inspection.Container
	stream, err := e.client.ContainerLogs(ctx, containerID, client.ContainerLogsOptions{
		ShowStdout: true, ShowStderr: true, Timestamps: true, Follow: follow, Tail: fmt.Sprint(lines),
	})
	if err != nil {
		return fmt.Errorf("open container logs: %w", err)
	}
	defer stream.Close()
	writer := &lineWriter{send: send}
	if inspect.Config != nil && inspect.Config.Tty {
		_, err = io.Copy(writer, stream)
	} else {
		_, err = stdcopy.StdCopy(writer, writer, stream)
	}
	if flushErr := writer.flush(); err == nil {
		err = flushErr
	}
	if err != nil && ctx.Err() == nil {
		return fmt.Errorf("stream container logs: %w", err)
	}
	return ctx.Err()
}

type lineWriter struct {
	buffer []byte
	send   func(string) error
}

func (w *lineWriter) Write(data []byte) (int, error) {
	w.buffer = append(w.buffer, data...)
	for {
		index := -1
		for position, value := range w.buffer {
			if value == '\n' {
				index = position
				break
			}
		}
		if index < 0 {
			break
		}
		line := strings.TrimSuffix(string(w.buffer[:index]), "\r")
		w.buffer = w.buffer[index+1:]
		if err := w.send(line); err != nil {
			return len(data), err
		}
	}
	return len(data), nil
}

func (w *lineWriter) flush() error {
	if len(w.buffer) == 0 {
		return nil
	}
	line := string(w.buffer)
	w.buffer = nil
	return w.send(line)
}

func (e *Engine) collectContainers(ctx context.Context, summaries []container.Summary) []ContainerSnapshot {
	if len(summaries) == 0 {
		return []ContainerSnapshot{}
	}
	workerCount := 8
	if len(summaries) < workerCount {
		workerCount = len(summaries)
	}
	jobs := make(chan container.Summary)
	results := make(chan ContainerSnapshot, len(summaries))
	var workers sync.WaitGroup
	for range workerCount {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for summary := range jobs {
				results <- e.collectContainer(ctx, summary)
			}
		}()
	}
	go func() {
		for _, summary := range summaries {
			jobs <- summary
		}
		close(jobs)
		workers.Wait()
		close(results)
	}()
	collected := make([]ContainerSnapshot, 0, len(summaries))
	for snapshot := range results {
		collected = append(collected, snapshot)
	}
	return collected
}

func (e *Engine) collectContainer(ctx context.Context, summary container.Summary) ContainerSnapshot {
	name := summary.ID[:min(12, len(summary.ID))]
	if len(summary.Names) > 0 {
		name = strings.TrimPrefix(summary.Names[0], "/")
	}
	snapshot := ContainerSnapshot{
		ContainerID: summary.ID, Name: name, Image: summary.Image,
		State: string(summary.State), Status: summary.Status,
		CreatedAt: time.Unix(summary.Created, 0).UTC(), NetworkMode: summary.HostConfig.NetworkMode,
		Ports: formatPorts(summary), Volumes: []string{}, Environment: []string{},
	}
	snapshot.ComposeProject = strings.TrimSpace(summary.Labels["com.docker.compose.project"])
	snapshot.ComposeService = strings.TrimSpace(summary.Labels["com.docker.compose.service"])
	snapshot.RuntimeKey = runtimeKey(summary.Labels, snapshot.Name)
	if summary.NetworkSettings != nil {
		for _, endpoint := range summary.NetworkSettings.Networks {
			if endpoint != nil && endpoint.IPAddress.IsValid() {
				snapshot.IPAddress = endpoint.IPAddress.String()
				break
			}
		}
	}
	inspection, inspectErr := e.client.ContainerInspect(ctx, summary.ID, client.ContainerInspectOptions{})
	inspect := inspection.Container
	if inspectErr == nil {
		snapshot.RestartCount = inspect.RestartCount
		if inspect.State != nil {
			if inspect.State.Health != nil {
				snapshot.Health = string(inspect.State.Health.Status)
			}
			if parsed, err := time.Parse(time.RFC3339Nano, inspect.State.StartedAt); err == nil && !parsed.IsZero() {
				parsed = parsed.UTC()
				snapshot.StartedAt = &parsed
			}
		}
		if inspect.Config != nil {
			if inspect.Config.Image != "" {
				snapshot.Image = inspect.Config.Image
			}
			snapshot.Environment = maskEnvironment(inspect.Config.Env)
		}
		for _, mount := range inspect.Mounts {
			mode := "ro"
			if mount.RW {
				mode = "rw"
			}
			snapshot.Volumes = append(snapshot.Volumes,
				fmt.Sprintf("%s:%s:%s", mount.Source, mount.Destination, mode))
		}
	}
	if summary.State == container.StateRunning {
		stats, statsErr := e.client.ContainerStats(ctx, summary.ID, client.ContainerStatsOptions{})
		if statsErr == nil {
			defer stats.Body.Close()
			var payload container.StatsResponse
			if json.NewDecoder(stats.Body).Decode(&payload) == nil {
				snapshot.CPUUsage = cpuPercent(payload)
				snapshot.MemoryUsage = memoryUsage(payload)
				snapshot.MemoryLimit = payload.MemoryStats.Limit
				for _, networkStats := range payload.Networks {
					snapshot.NetworkRX += networkStats.RxBytes
					snapshot.NetworkTX += networkStats.TxBytes
				}
			}
		}
	}
	return snapshot
}

func cpuPercent(stats container.StatsResponse) float64 {
	cpuDelta := float64(stats.CPUStats.CPUUsage.TotalUsage - stats.PreCPUStats.CPUUsage.TotalUsage)
	systemDelta := float64(stats.CPUStats.SystemUsage - stats.PreCPUStats.SystemUsage)
	cores := float64(stats.CPUStats.OnlineCPUs)
	if cores == 0 {
		cores = float64(len(stats.CPUStats.CPUUsage.PercpuUsage))
	}
	if cpuDelta <= 0 || systemDelta <= 0 || cores <= 0 {
		return 0
	}
	return cpuDelta / systemDelta * cores * 100
}

func memoryUsage(stats container.StatsResponse) uint64 {
	usage := stats.MemoryStats.Usage
	if inactive, ok := stats.MemoryStats.Stats["inactive_file"]; ok && inactive < usage {
		usage -= inactive
	}
	return usage
}

func formatPorts(summary container.Summary) []string {
	ports := make([]string, 0, len(summary.Ports))
	for _, port := range summary.Ports {
		private := fmt.Sprintf("%d/%s", port.PrivatePort, port.Type)
		if port.PublicPort == 0 {
			ports = append(ports, private)
			continue
		}
		host := port.IP.String()
		if !port.IP.IsValid() {
			host = "0.0.0.0"
		}
		ports = append(ports, fmt.Sprintf("%s:%d→%s", host, port.PublicPort, private))
	}
	return ports
}

func maskEnvironment(values []string) []string {
	masked := make([]string, 0, len(values))
	markers := []string{"PASSWORD", "PASSWD", "SECRET", "TOKEN", "KEY", "CREDENTIAL", "AUTH", "COOKIE", "DSN", "WEBHOOK"}
	for _, entry := range values {
		key, value, found := strings.Cut(entry, "=")
		upper := strings.ToUpper(key)
		sensitive := false
		for _, marker := range markers {
			if strings.Contains(upper, marker) {
				sensitive = true
				break
			}
		}
		if found && containsURIUserInfo(value) {
			sensitive = true
		}
		if sensitive && found {
			masked = append(masked, key+"=******")
		} else {
			masked = append(masked, entry)
		}
	}
	return masked
}

func containsURIUserInfo(value string) bool {
	scheme := strings.Index(value, "://")
	if scheme < 0 {
		return false
	}
	userinfo := value[scheme+3:]
	at := strings.IndexByte(userinfo, '@')
	return at > 0 && strings.Contains(userinfo[:at], ":")
}

func truncate(value string, limit int) string {
	if len(value) <= limit {
		return value
	}
	return value[:limit]
}
