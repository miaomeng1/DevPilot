package agent

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	dockerengine "github.com/devpilot/devpilot-agent/internal/docker"
	"github.com/devpilot/devpilot-agent/internal/health"
	"github.com/devpilot/devpilot-agent/internal/host"
	"github.com/devpilot/devpilot-agent/internal/metrics"
	nginxmanager "github.com/devpilot/devpilot-agent/internal/nginx"
	"github.com/gorilla/websocket"
)

const tokenHeader = "X-DevPilot-Agent-Token"

type Client struct {
	baseURL    string
	token      string
	version    string
	httpClient *http.Client
}

type registerRequest struct {
	Token        string `json:"token"`
	AgentVersion string `json:"agentVersion"`
	host.Snapshot
}

type Registration struct {
	ServerID                string `json:"serverId"`
	ServerName              string `json:"serverName"`
	HeartbeatIntervalSecond int    `json:"heartbeatIntervalSeconds"`
	MetricIntervalSecond    int    `json:"metricIntervalSeconds"`
}

type Heartbeat struct {
	ServerID             string `json:"serverId"`
	Status               string `json:"status"`
	NextHeartbeatSecond  int    `json:"nextHeartbeatSeconds"`
	MetricIntervalSecond int    `json:"metricIntervalSeconds"`
}

type MetricReceipt struct {
	ServerID   string    `json:"serverId"`
	AcceptedAt time.Time `json:"acceptedAt"`
}

type DockerCommand struct {
	CommandID    string `json:"commandId"`
	ContainerID  string `json:"containerId"`
	Action       string `json:"action"`
	LogSessionID string `json:"logSessionId"`
	Lines        int    `json:"lines"`
	Follow       bool   `json:"follow"`
}

type HealthTask struct {
	ApplicationID  string `json:"applicationId"`
	HealthCheckURL string `json:"healthCheckUrl"`
	TimeoutSeconds int    `json:"timeoutSeconds"`
}

type NginxCommand struct {
	CommandID string `json:"commandId"`
	Action    string `json:"action"`
	Filename  string `json:"filename"`
	Content   string `json:"content"`
}

func (c *Client) OpenLogSocket(ctx context.Context, sessionID string) (*websocket.Conn, error) {
	base, err := url.Parse(c.baseURL)
	if err != nil {
		return nil, fmt.Errorf("parse server URL: %w", err)
	}
	if base.Scheme == "https" {
		base.Scheme = "wss"
	} else {
		base.Scheme = "ws"
	}
	base.Path = strings.TrimRight(base.Path, "/") + "/ws/agent/logs"
	query := base.Query()
	query.Set("sessionId", sessionID)
	base.RawQuery = query.Encode()
	headers := http.Header{}
	headers.Set(tokenHeader, c.token)
	headers.Set("User-Agent", "DevPilot-Agent/"+c.version)
	connection, response, err := websocket.DefaultDialer.DialContext(ctx, base.String(), headers)
	if response != nil && response.Body != nil {
		response.Body.Close()
	}
	if err != nil {
		return nil, fmt.Errorf("connect log relay: %w", err)
	}
	return connection, nil
}

type apiResponse[T any] struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
	Data    T      `json:"data"`
}

func NewClient(baseURL, token, agentVersion string) *Client {
	return &Client{
		baseURL: strings.TrimRight(baseURL, "/"),
		token:   token,
		version: agentVersion,
		httpClient: &http.Client{
			Timeout: 15 * time.Second,
		},
	}
}

func (c *Client) Register(ctx context.Context, snapshot host.Snapshot) (Registration, error) {
	payload := registerRequest{Token: c.token, AgentVersion: c.version, Snapshot: snapshot}
	return doJSON[Registration](ctx, c, http.MethodPost, "/api/agent/register", payload, false)
}

func (c *Client) Heartbeat(ctx context.Context) (Heartbeat, error) {
	payload := struct {
		AgentVersion string `json:"agentVersion"`
	}{AgentVersion: c.version}
	return doJSON[Heartbeat](ctx, c, http.MethodPost, "/api/agent/heartbeat", payload, true)
}

func (c *Client) UploadMetrics(ctx context.Context, sample metrics.Sample) (MetricReceipt, error) {
	sample.AgentVersion = c.version
	return doJSON[MetricReceipt](ctx, c, http.MethodPost, "/api/agent/metrics", sample, true)
}

func (c *Client) UploadDockerSnapshot(ctx context.Context, snapshot dockerengine.Snapshot) (string, error) {
	snapshot.AgentVersion = c.version
	return doJSON[string](ctx, c, http.MethodPost, "/api/agent/docker/snapshot", snapshot, true)
}

func (c *Client) NextDockerCommand(ctx context.Context) (*DockerCommand, error) {
	return doJSON[*DockerCommand](ctx, c, http.MethodGet, "/api/agent/docker/commands/next", struct{}{}, true)
}

func (c *Client) CompleteDockerCommand(ctx context.Context, commandID string, executeErr error) error {
	status := "SUCCEEDED"
	errorMessage := ""
	if executeErr != nil {
		status = "FAILED"
		errorMessage = executeErr.Error()
		if len(errorMessage) > 1000 {
			errorMessage = errorMessage[:1000]
		}
	}
	payload := struct {
		Status       string `json:"status"`
		ErrorMessage string `json:"errorMessage,omitempty"`
	}{Status: status, ErrorMessage: errorMessage}
	_, err := doJSON[struct{}](ctx, c, http.MethodPost,
		"/api/agent/docker/commands/"+commandID+"/result", payload, true)
	return err
}

func (c *Client) NextHealthTask(ctx context.Context) (*HealthTask, error) {
	return doJSON[*HealthTask](ctx, c, http.MethodGet, "/api/agent/applications/health/next", struct{}{}, true)
}

func (c *Client) CompleteHealthTask(ctx context.Context, applicationID string, result health.Result) error {
	_, err := doJSON[struct{}](ctx, c, http.MethodPost,
		"/api/agent/applications/health/"+applicationID+"/result", result, true)
	return err
}

func (c *Client) UploadNginxSnapshot(ctx context.Context, snapshot nginxmanager.Snapshot) (string, error) {
	snapshot.AgentVersion = c.version
	return doJSON[string](ctx, c, http.MethodPost, "/api/agent/nginx/snapshot", snapshot, true)
}

func (c *Client) NextNginxCommand(ctx context.Context) (*NginxCommand, error) {
	return doJSON[*NginxCommand](ctx, c, http.MethodGet, "/api/agent/nginx/commands/next", struct{}{}, true)
}

func (c *Client) CompleteNginxCommand(ctx context.Context, commandID, validationOutput string, executeErr error) error {
	status := "SUCCEEDED"
	errorMessage := ""
	if executeErr != nil {
		status = "FAILED"
		errorMessage = executeErr.Error()
		if len(errorMessage) > 2000 {
			errorMessage = errorMessage[:2000]
		}
	}
	if len(validationOutput) > 10000 {
		validationOutput = validationOutput[:10000]
	}
	payload := struct {
		Status           string `json:"status"`
		ValidationOutput string `json:"validationOutput,omitempty"`
		ErrorMessage     string `json:"errorMessage,omitempty"`
	}{Status: status, ValidationOutput: validationOutput, ErrorMessage: errorMessage}
	_, err := doJSON[struct{}](ctx, c, http.MethodPost,
		"/api/agent/nginx/commands/"+commandID+"/result", payload, true)
	return err
}

func doJSON[T any](ctx context.Context, client *Client, method, path string, payload any,
	withTokenHeader bool) (T, error) {
	var zero T
	body, err := json.Marshal(payload)
	if err != nil {
		return zero, fmt.Errorf("encode request: %w", err)
	}
	request, err := http.NewRequestWithContext(ctx, method, client.baseURL+path, bytes.NewReader(body))
	if err != nil {
		return zero, fmt.Errorf("create request: %w", err)
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "application/json")
	request.Header.Set("User-Agent", "DevPilot-Agent/"+client.version)
	if withTokenHeader {
		request.Header.Set(tokenHeader, client.token)
	}

	response, err := client.httpClient.Do(request)
	if err != nil {
		return zero, fmt.Errorf("request %s: %w", path, err)
	}
	defer response.Body.Close()
	responseBody, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		return zero, fmt.Errorf("read response: %w", err)
	}
	var envelope apiResponse[T]
	if err := json.Unmarshal(responseBody, &envelope); err != nil {
		return zero, fmt.Errorf("decode response (status %d): %w", response.StatusCode, err)
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 || envelope.Code != 0 {
		return zero, fmt.Errorf("server rejected %s (status=%d code=%d): %s",
			path, response.StatusCode, envelope.Code, envelope.Message)
	}
	return envelope.Data, nil
}
