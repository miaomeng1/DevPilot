package health

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestProbeUnderstandsActuatorHealth(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("Content-Type", "application/json")
		_, _ = writer.Write([]byte(`{"status":"UP"}`))
	}))
	defer server.Close()

	result := Probe(context.Background(), server.URL, time.Second)
	if result.Status != "HEALTHY" || result.HTTPStatus != http.StatusOK {
		t.Fatalf("Probe() = %#v", result)
	}
}

func TestProbeRejectsDownAndUnsafeSchemes(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		_, _ = writer.Write([]byte(`{"status":"DOWN"}`))
	}))
	defer server.Close()
	if result := Probe(context.Background(), server.URL, time.Second); result.Status != "UNHEALTHY" {
		t.Fatalf("DOWN Probe() = %#v", result)
	}
	if result := Probe(context.Background(), "file:///etc/passwd", time.Second); result.Status != "UNHEALTHY" {
		t.Fatalf("file Probe() = %#v", result)
	}
}
