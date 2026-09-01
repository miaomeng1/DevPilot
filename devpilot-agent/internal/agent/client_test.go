package agent

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	dockerengine "github.com/devpilot/devpilot-agent/internal/docker"
	"github.com/devpilot/devpilot-agent/internal/health"
	"github.com/devpilot/devpilot-agent/internal/host"
	"github.com/devpilot/devpilot-agent/internal/metrics"
	nginxmanager "github.com/devpilot/devpilot-agent/internal/nginx"
)

func TestRegisterAndHeartbeat(t *testing.T) {
	t.Helper()
	const token = "dp_agent_12345678901234567890123456789012"
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("Content-Type", "application/json")
		switch request.URL.Path {
		case "/api/agent/register":
			var payload map[string]any
			if err := json.NewDecoder(request.Body).Decode(&payload); err != nil {
				t.Fatalf("decode register request: %v", err)
			}
			if payload["token"] != token || payload["hostname"] != "test-host" {
				t.Fatalf("unexpected register payload: %#v", payload)
			}
			_, _ = writer.Write([]byte(`{"code":0,"message":"success","data":{"serverId":"42","serverName":"test","heartbeatIntervalSeconds":10}}`))
		case "/api/agent/heartbeat":
			if request.Header.Get(tokenHeader) != token {
				t.Fatalf("heartbeat token header = %q", request.Header.Get(tokenHeader))
			}
			_, _ = writer.Write([]byte(`{"code":0,"message":"success","data":{"serverId":"42","status":"ONLINE","nextHeartbeatSeconds":10}}`))
		case "/api/agent/metrics":
			if request.Header.Get(tokenHeader) != token {
				t.Fatalf("metrics token header = %q", request.Header.Get(tokenHeader))
			}
			var payload map[string]any
			if err := json.NewDecoder(request.Body).Decode(&payload); err != nil {
				t.Fatalf("decode metrics request: %v", err)
			}
			if payload["agentVersion"] != "1.0.0" || payload["cpuUsage"] != 12.5 {
				t.Fatalf("unexpected metrics payload: %#v", payload)
			}
			_, _ = writer.Write([]byte(`{"code":0,"message":"success","data":{"serverId":"42","acceptedAt":"2026-08-31T01:00:00Z"}}`))
		case "/api/agent/docker/snapshot":
			if request.Header.Get(tokenHeader) != token {
				t.Fatalf("snapshot token header = %q", request.Header.Get(tokenHeader))
			}
			_, _ = writer.Write([]byte(`{"code":0,"message":"success","data":"42"}`))
		case "/api/agent/docker/commands/next":
			_, _ = writer.Write([]byte(`{"code":0,"message":"success","data":{"commandId":"84","containerId":"abc","action":"RESTART"}}`))
		case "/api/agent/docker/commands/84/result":
			_, _ = writer.Write([]byte(`{"code":0,"message":"success","data":null}`))
		case "/api/agent/applications/health/next":
			_, _ = writer.Write([]byte(`{"code":0,"message":"success","data":{"applicationId":"21","healthCheckUrl":"http://127.0.0.1:8080/health","timeoutSeconds":5}}`))
		case "/api/agent/applications/health/21/result":
			_, _ = writer.Write([]byte(`{"code":0,"message":"success","data":null}`))
		case "/api/agent/nginx/snapshot":
			_, _ = writer.Write([]byte(`{"code":0,"message":"success","data":"42"}`))
		case "/api/agent/nginx/commands/next":
			_, _ = writer.Write([]byte(`{"code":0,"message":"success","data":{"commandId":"91","action":"UPDATE","filename":"api.conf","content":"server {}"}}`))
		case "/api/agent/nginx/commands/91/result":
			_, _ = writer.Write([]byte(`{"code":0,"message":"success","data":null}`))
		default:
			http.NotFound(writer, request)
		}
	}))
	defer server.Close()

	client := NewClient(server.URL, token, "1.0.0")
	registration, err := client.Register(context.Background(), host.Snapshot{Hostname: "test-host"})
	if err != nil {
		t.Fatalf("Register() error = %v", err)
	}
	if registration.ServerID != "42" || registration.HeartbeatIntervalSecond != 10 {
		t.Fatalf("Register() = %#v", registration)
	}
	heartbeat, err := client.Heartbeat(context.Background())
	if err != nil {
		t.Fatalf("Heartbeat() error = %v", err)
	}
	if heartbeat.Status != "ONLINE" {
		t.Fatalf("Heartbeat().Status = %q", heartbeat.Status)
	}
	receipt, err := client.UploadMetrics(context.Background(), metrics.Sample{
		CollectedAt: time.Now().UTC(), CPUUsage: 12.5,
	})
	if err != nil {
		t.Fatalf("UploadMetrics() error = %v", err)
	}
	if receipt.ServerID != "42" {
		t.Fatalf("UploadMetrics().ServerID = %q", receipt.ServerID)
	}
	serverID, err := client.UploadDockerSnapshot(context.Background(), dockerengine.Snapshot{
		Available: true, CollectedAt: time.Now().UTC(), Containers: []dockerengine.ContainerSnapshot{},
	})
	if err != nil || serverID != "42" {
		t.Fatalf("UploadDockerSnapshot() = %q, %v", serverID, err)
	}
	command, err := client.NextDockerCommand(context.Background())
	if err != nil || command == nil || command.CommandID != "84" || command.Action != "RESTART" {
		t.Fatalf("NextDockerCommand() = %#v, %v", command, err)
	}
	if err := client.CompleteDockerCommand(context.Background(), command.CommandID, nil); err != nil {
		t.Fatalf("CompleteDockerCommand() error = %v", err)
	}
	healthTask, err := client.NextHealthTask(context.Background())
	if err != nil || healthTask == nil || healthTask.ApplicationID != "21" {
		t.Fatalf("NextHealthTask() = %#v, %v", healthTask, err)
	}
	if err := client.CompleteHealthTask(context.Background(), healthTask.ApplicationID,
		health.Result{Status: "HEALTHY", HTTPStatus: 200}); err != nil {
		t.Fatalf("CompleteHealthTask() error = %v", err)
	}
	serverID, err = client.UploadNginxSnapshot(context.Background(), nginxmanager.Snapshot{
		Enabled: true, Available: true, CollectedAt: time.Now().UTC(), Files: []nginxmanager.FileSnapshot{},
	})
	if err != nil || serverID != "42" {
		t.Fatalf("UploadNginxSnapshot() = %q, %v", serverID, err)
	}
	nginxCommand, err := client.NextNginxCommand(context.Background())
	if err != nil || nginxCommand == nil || nginxCommand.CommandID != "91" {
		t.Fatalf("NextNginxCommand() = %#v, %v", nginxCommand, err)
	}
	if err := client.CompleteNginxCommand(context.Background(), nginxCommand.CommandID,
		"configuration test is successful", nil); err != nil {
		t.Fatalf("CompleteNginxCommand() error = %v", err)
	}
}

func TestClientRejectsErrorEnvelope(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("Content-Type", "application/json")
		writer.WriteHeader(http.StatusUnauthorized)
		_, _ = writer.Write([]byte(`{"code":40101,"message":"Agent Token invalid","data":null}`))
	}))
	defer server.Close()

	client := NewClient(server.URL, "dp_agent_invalid_12345678901234567890", "1.0.0")
	if _, err := client.Heartbeat(context.Background()); err == nil {
		t.Fatal("Heartbeat() error = nil, want server rejection")
	}
}
