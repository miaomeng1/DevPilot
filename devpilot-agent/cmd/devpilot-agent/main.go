package main

import (
	"context"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/devpilot/devpilot-agent/internal/agent"
	"github.com/devpilot/devpilot-agent/internal/config"
	dockerengine "github.com/devpilot/devpilot-agent/internal/docker"
	"github.com/devpilot/devpilot-agent/internal/host"
	"github.com/devpilot/devpilot-agent/internal/metrics"
	nginxmanager "github.com/devpilot/devpilot-agent/internal/nginx"
	"github.com/devpilot/devpilot-agent/internal/version"
)

func main() {
	configPath := flag.String("config", config.DefaultPath, "path to config.yaml")
	showVersion := flag.Bool("version", false, "print version information")
	flag.Parse()

	if *showVersion {
		fmt.Printf("devpilot-agent %s (commit=%s, built=%s)\n", version.Version, version.Commit, version.Date)
		return
	}

	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	settings, err := config.Load(*configPath)
	if err != nil {
		logger.Error("agent configuration is invalid", "error", err)
		os.Exit(1)
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	snapshot, err := host.Collect(ctx)
	if err != nil {
		logger.Error("host discovery failed", "error", err)
		os.Exit(1)
	}
	logger.Info("DevPilot Agent starting",
		"version", version.Version,
		"server", settings.Server.URL,
		"hostname", snapshot.Hostname,
		"collectIntervalSeconds", settings.Collect.IntervalSeconds)

	client := agent.NewClient(settings.Server.URL, settings.Agent.Token, version.Version)
	nginxManager := nginxmanager.NewManager(settings.Nginx.Enabled, settings.Nginx.ConfigPath)
	dockerClient, dockerErr := dockerengine.NewEngine()
	if dockerErr != nil {
		logger.Warn("Docker client initialization failed", "error", dockerErr)
	} else {
		defer dockerClient.Close()
	}
	if err := agent.Run(ctx, client, metrics.NewCollector(), dockerClient, nginxManager, snapshot,
		time.Duration(settings.Collect.IntervalSeconds)*time.Second, logger); err != nil {
		logger.Error("agent stopped unexpectedly", "error", err)
		os.Exit(1)
	}
	logger.Info("DevPilot Agent stopped")
}
