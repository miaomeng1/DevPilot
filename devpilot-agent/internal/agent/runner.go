package agent

import (
	"context"
	"log/slog"
	"time"

	dockerengine "github.com/devpilot/devpilot-agent/internal/docker"
	"github.com/devpilot/devpilot-agent/internal/health"
	"github.com/devpilot/devpilot-agent/internal/host"
	"github.com/devpilot/devpilot-agent/internal/metrics"
	nginxmanager "github.com/devpilot/devpilot-agent/internal/nginx"
)

func Run(ctx context.Context, client *Client, collector *metrics.Collector, dockerEngine *dockerengine.Engine,
	nginxManager *nginxmanager.Manager, snapshot host.Snapshot, collectInterval time.Duration, logger *slog.Logger) error {
	registration, err := registerUntilAvailable(ctx, client, snapshot, logger)
	if err != nil {
		return err
	}
	interval := time.Duration(registration.HeartbeatIntervalSecond) * time.Second
	if interval < 5*time.Second || interval > 5*time.Minute {
		interval = 10 * time.Second
	}
	if collectInterval < 5*time.Second || collectInterval > 5*time.Minute {
		collectInterval = 10 * time.Second
	}
	serverCollectInterval := time.Duration(registration.MetricIntervalSecond) * time.Second
	if serverCollectInterval >= 5*time.Second && serverCollectInterval <= 5*time.Minute {
		collectInterval = serverCollectInterval
	}
	logger.Info("agent registered", "serverId", registration.ServerID,
		"serverName", registration.ServerName, "heartbeatInterval", interval.String(),
		"metricsInterval", collectInterval.String())

	heartbeatTicker := time.NewTicker(interval)
	metricsTicker := time.NewTicker(collectInterval)
	dockerTicker := time.NewTicker(collectInterval)
	commandTicker := time.NewTicker(2 * time.Second)
	healthTicker := time.NewTicker(5 * time.Second)
	nginxTicker := time.NewTicker(collectInterval)
	nginxCommandTicker := time.NewTicker(2 * time.Second)
	defer heartbeatTicker.Stop()
	defer metricsTicker.Stop()
	defer dockerTicker.Stop()
	defer commandTicker.Stop()
	defer healthTicker.Stop()
	defer nginxTicker.Stop()
	defer nginxCommandTicker.Stop()
	_, _ = sendHeartbeat(ctx, client, logger)
	collectAndUpload(ctx, client, collector, logger)
	collectAndUploadDocker(ctx, client, dockerEngine, logger)
	pollHealthTask(ctx, client, logger)
	collectAndUploadNginx(ctx, client, nginxManager, logger)
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-heartbeatTicker.C:
			heartbeat, heartbeatErr := sendHeartbeat(ctx, client, logger)
			if heartbeatErr == nil && heartbeat != nil {
				nextHeartbeat := time.Duration(heartbeat.NextHeartbeatSecond) * time.Second
				if nextHeartbeat >= 5*time.Second && nextHeartbeat <= 5*time.Minute && nextHeartbeat != interval {
					interval = nextHeartbeat
					heartbeatTicker.Reset(interval)
				}
				nextCollect := time.Duration(heartbeat.MetricIntervalSecond) * time.Second
				if nextCollect >= 5*time.Second && nextCollect <= 5*time.Minute && nextCollect != collectInterval {
					collectInterval = nextCollect
					metricsTicker.Reset(collectInterval)
					dockerTicker.Reset(collectInterval)
					nginxTicker.Reset(collectInterval)
					logger.Info("collection interval updated", "metricsInterval", collectInterval.String())
				}
			}
		case <-metricsTicker.C:
			collectAndUpload(ctx, client, collector, logger)
		case <-dockerTicker.C:
			collectAndUploadDocker(ctx, client, dockerEngine, logger)
		case <-commandTicker.C:
			pollDockerCommand(ctx, client, dockerEngine, logger)
		case <-healthTicker.C:
			pollHealthTask(ctx, client, logger)
		case <-nginxTicker.C:
			collectAndUploadNginx(ctx, client, nginxManager, logger)
		case <-nginxCommandTicker.C:
			pollNginxCommand(ctx, client, nginxManager, logger)
		}
	}
}

func collectAndUploadNginx(ctx context.Context, client *Client, manager *nginxmanager.Manager,
	logger *slog.Logger) {
	if manager == nil {
		return
	}
	collectCtx, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	snapshot := manager.Snapshot(collectCtx)
	if _, err := client.UploadNginxSnapshot(ctx, snapshot); err != nil && ctx.Err() == nil {
		logger.Warn("Nginx snapshot upload failed", "error", err)
	}
}

func pollNginxCommand(ctx context.Context, client *Client, manager *nginxmanager.Manager,
	logger *slog.Logger) {
	if manager == nil || !manager.Enabled() {
		return
	}
	command, err := client.NextNginxCommand(ctx)
	if err != nil {
		if ctx.Err() == nil {
			logger.Warn("Nginx command poll failed", "error", err)
		}
		return
	}
	if command == nil {
		return
	}
	go func() {
		executeCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
		defer cancel()
		validationOutput, executeErr := manager.Apply(executeCtx, command.Filename, command.Content)
		if reportErr := client.CompleteNginxCommand(ctx, command.CommandID, validationOutput, executeErr); reportErr != nil && ctx.Err() == nil {
			logger.Warn("Nginx command result upload failed", "commandId", command.CommandID, "error", reportErr)
		}
		if executeErr != nil {
			logger.Warn("Nginx configuration operation failed", "commandId", command.CommandID,
				"filename", command.Filename, "error", executeErr)
		} else {
			logger.Info("Nginx configuration operation succeeded", "commandId", command.CommandID,
				"action", command.Action, "filename", command.Filename)
			collectAndUploadNginx(ctx, client, manager, logger)
		}
	}()
}

func pollHealthTask(ctx context.Context, client *Client, logger *slog.Logger) {
	task, err := client.NextHealthTask(ctx)
	if err != nil {
		if ctx.Err() == nil {
			logger.Warn("application health task poll failed", "error", err)
		}
		return
	}
	if task == nil {
		return
	}
	timeout := time.Duration(task.TimeoutSeconds) * time.Second
	if timeout < time.Second || timeout > 30*time.Second {
		timeout = 5 * time.Second
	}
	go func() {
		result := health.Probe(ctx, task.HealthCheckURL, timeout)
		if reportErr := client.CompleteHealthTask(ctx, task.ApplicationID, result); reportErr != nil && ctx.Err() == nil {
			logger.Warn("application health result upload failed", "applicationId", task.ApplicationID, "error", reportErr)
		}
	}()
}

func collectAndUploadDocker(ctx context.Context, client *Client, engine *dockerengine.Engine,
	logger *slog.Logger) {
	if engine == nil {
		return
	}
	collectCtx, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	snapshot := engine.Snapshot(collectCtx)
	if _, err := client.UploadDockerSnapshot(ctx, snapshot); err != nil && ctx.Err() == nil {
		logger.Warn("Docker snapshot upload failed", "error", err)
	}
}

func pollDockerCommand(ctx context.Context, client *Client, engine *dockerengine.Engine, logger *slog.Logger) {
	if engine == nil {
		return
	}
	command, err := client.NextDockerCommand(ctx)
	if err != nil {
		if ctx.Err() == nil {
			logger.Warn("Docker command poll failed", "error", err)
		}
		return
	}
	if command == nil {
		return
	}
	if command.Action == "LOGS" {
		go streamDockerLogs(ctx, client, engine, command, logger)
		return
	}
	go func() {
		executeCtx, cancel := context.WithTimeout(ctx, 45*time.Second)
		defer cancel()
		executeErr := engine.Execute(executeCtx, command.ContainerID, command.Action)
		if reportErr := client.CompleteDockerCommand(ctx, command.CommandID, executeErr); reportErr != nil && ctx.Err() == nil {
			logger.Warn("Docker command result upload failed", "commandId", command.CommandID, "error", reportErr)
		}
		if executeErr != nil {
			logger.Warn("Docker command failed", "commandId", command.CommandID, "action", command.Action,
				"containerId", command.ContainerID, "error", executeErr)
		} else {
			logger.Info("Docker command succeeded", "commandId", command.CommandID,
				"action", command.Action, "containerId", command.ContainerID)
			collectAndUploadDocker(ctx, client, engine, logger)
		}
	}()
}

func streamDockerLogs(ctx context.Context, client *Client, engine *dockerengine.Engine,
	command *DockerCommand, logger *slog.Logger) {
	streamCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	connection, err := client.OpenLogSocket(streamCtx, command.LogSessionID)
	if err != nil {
		logger.Warn("log relay connection failed", "containerId", command.ContainerID, "error", err)
		return
	}
	defer connection.Close()
	go func() {
		for {
			if _, _, readErr := connection.ReadMessage(); readErr != nil {
				cancel()
				return
			}
		}
	}()
	err = engine.StreamLogs(streamCtx, command.ContainerID, command.Lines, command.Follow,
		func(line string) error { return connection.WriteMessage(1, []byte(line)) })
	if err != nil && streamCtx.Err() == nil {
		logger.Warn("Docker log stream failed", "containerId", command.ContainerID, "error", err)
	}
}

func sendHeartbeat(ctx context.Context, client *Client, logger *slog.Logger) (*Heartbeat, error) {
	heartbeat, err := client.Heartbeat(ctx)
	if err != nil && ctx.Err() == nil {
		logger.Warn("heartbeat failed", "error", err)
	}
	return &heartbeat, err
}

func collectAndUpload(ctx context.Context, client *Client, collector *metrics.Collector, logger *slog.Logger) {
	sample, err := collector.Collect(ctx)
	if err != nil {
		if ctx.Err() == nil {
			logger.Warn("metrics collection failed", "error", err)
		}
		return
	}
	if _, err = client.UploadMetrics(ctx, sample); err != nil && ctx.Err() == nil {
		logger.Warn("metrics upload failed", "error", err)
	}
}

func registerUntilAvailable(ctx context.Context, client *Client, snapshot host.Snapshot,
	logger *slog.Logger) (Registration, error) {
	backoff := time.Second
	for {
		registration, err := client.Register(ctx, snapshot)
		if err == nil {
			return registration, nil
		}
		if ctx.Err() != nil {
			return Registration{}, nil
		}
		logger.Warn("registration failed; retrying", "error", err, "retryIn", backoff.String())
		timer := time.NewTimer(backoff)
		select {
		case <-ctx.Done():
			timer.Stop()
			return Registration{}, nil
		case <-timer.C:
		}
		if backoff < 30*time.Second {
			backoff *= 2
			if backoff > 30*time.Second {
				backoff = 30 * time.Second
			}
		}
	}
}
