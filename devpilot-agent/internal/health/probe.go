package health

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type Result struct {
	Status        string `json:"status"`
	LatencyMillis int    `json:"latencyMillis"`
	HTTPStatus    int    `json:"httpStatus,omitempty"`
	Message       string `json:"message,omitempty"`
}

func Probe(ctx context.Context, target string, timeout time.Duration) Result {
	started := time.Now()
	result := Result{Status: "UNHEALTHY"}
	parsed, err := url.Parse(target)
	if err != nil || parsed.Host == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") {
		result.Message = "health check URL must use HTTP or HTTPS"
		return finish(started, result)
	}
	probeCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	request, err := http.NewRequestWithContext(probeCtx, http.MethodGet, parsed.String(), nil)
	if err != nil {
		result.Message = truncate(err.Error(), 500)
		return finish(started, result)
	}
	request.Header.Set("Accept", "application/json, text/plain;q=0.9, */*;q=0.5")
	request.Header.Set("User-Agent", "DevPilot-Agent-HealthCheck")
	response, err := (&http.Client{Timeout: timeout}).Do(request)
	if err != nil {
		result.Message = truncate(err.Error(), 500)
		return finish(started, result)
	}
	defer response.Body.Close()
	result.HTTPStatus = response.StatusCode
	body, readErr := io.ReadAll(io.LimitReader(response.Body, 4096))
	if readErr != nil {
		result.Message = truncate(readErr.Error(), 500)
		return finish(started, result)
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		result.Message = response.Status
		return finish(started, result)
	}
	var healthEnvelope struct {
		Status string `json:"status"`
	}
	if json.Unmarshal(body, &healthEnvelope) == nil && healthEnvelope.Status != "" {
		normalized := strings.ToUpper(strings.TrimSpace(healthEnvelope.Status))
		if normalized != "UP" && normalized != "HEALTHY" {
			result.Message = fmt.Sprintf("reported status %s", normalized)
			return finish(started, result)
		}
		result.Message = fmt.Sprintf("reported status %s", normalized)
	} else {
		result.Message = response.Status
	}
	result.Status = "HEALTHY"
	return finish(started, result)
}

func finish(started time.Time, result Result) Result {
	result.LatencyMillis = int(time.Since(started).Milliseconds())
	return result
}

func truncate(value string, limit int) string {
	if len(value) <= limit {
		return value
	}
	return value[:limit]
}
