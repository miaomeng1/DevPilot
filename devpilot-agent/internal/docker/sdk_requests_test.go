package docker

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"testing"

	"github.com/moby/moby/api/types/container"

	"github.com/moby/moby/client"
)

type sdkTestTransport func(*http.Request) (*http.Response, error)

func (f sdkTestTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func sdkResponse(code int, body string) *http.Response {
	return &http.Response{StatusCode: code, Header: http.Header{"Content-Type": {"application/json"}}, Body: io.NopCloser(strings.NewReader(body))}
}

func TestSDKTemplateAmbiguousStartRetry(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	_ = listener.Close()
	config, host := buildTemplateConfiguration("uptime-kuma", "sdk-retry", "UTC", port, serviceTemplates["uptime-kuma"], nil)
	created, running := false, false
	creates, starts, pulls, volumes, deletes := 0, 0, 0, 0, 0
	e := sdkTestEngine(t, func(r *http.Request) (*http.Response, error) {
		switch {
		case r.Method == "GET" && r.URL.Path == "/v1.51/containers/devpilot-sdk-retry/json":
			if !created {
				return sdkResponse(404, `{"message":"not found"}`), nil
			}
			body, err := json.Marshal(map[string]any{"Id": "fixture", "Config": config, "HostConfig": host, "State": map[string]bool{"Running": running}})
			if err != nil {
				t.Fatal(err)
			}
			return sdkResponse(200, string(body)), nil
		case r.URL.Path == "/v1.51/images/create":
			pulls++
			return sdkResponse(200, `{"status":"complete"}`), nil
		case r.URL.Path == "/v1.51/volumes/create":
			volumes++
			return sdkResponse(201, `{"Name":"devpilot-sdk-retry-data","Labels":{"com.devpilot.managed":"true","com.devpilot.template.id":"uptime-kuma","com.devpilot.instance":"sdk-retry"}}`), nil
		case r.URL.Path == "/v1.51/containers/create":
			creates++
			created = true
			return sdkResponse(201, `{"Id":"fixture"}`), nil
		case r.URL.Path == "/v1.51/containers/fixture/start":
			starts++
			running = true
			return nil, context.DeadlineExceeded // Docker succeeded, response was lost.
		case r.Method == "DELETE":
			deletes++
			return sdkResponse(204, ""), nil
		default:
			t.Errorf("unexpected request %s %s", r.Method, r.URL.Path)
			return sdkResponse(500, `{"message":"unexpected"}`), nil
		}
	})
	if id, err := e.InstallTemplate(context.Background(), "uptime-kuma", "sdk-retry", port, "UTC"); id != "" || err == nil || !strings.Contains(err.Error(), "resource preserved") {
		t.Fatalf("first attempt id=%q err=%v", id, err)
	}
	if id, err := e.InstallTemplate(context.Background(), "uptime-kuma", "sdk-retry", port, "UTC"); id != "fixture" || err != nil {
		t.Fatalf("retry id=%q err=%v", id, err)
	}
	if creates != 1 || starts != 1 || pulls != 1 || volumes != 1 || deletes != 0 {
		t.Fatalf("creates=%d starts=%d pulls=%d volumes=%d deletes=%d", creates, starts, pulls, volumes, deletes)
	}
}

func TestSDKTemplateRetryRejectsMismatchedExistingContainer(t *testing.T) {
	for _, mutation := range []string{"owner", "instance", "image", "port", "timezone", "state"} {
		t.Run(mutation, func(t *testing.T) {
			config, host := buildTemplateConfiguration("uptime-kuma", "sdk-retry", "UTC", 19001, serviceTemplates["uptime-kuma"], nil)
			state := &container.State{Running: false}
			switch mutation {
			case "owner":
				delete(config.Labels, "com.devpilot.managed")
			case "instance":
				config.Labels["com.devpilot.instance"] = "someone-else"
			case "image":
				config.Image = "unrelated:latest"
			case "port":
				host.PortBindings = nil
			case "timezone":
				config.Env = []string{"TZ=Asia/Shanghai"}
			case "state":
				state = nil
			}
			e := sdkTestEngine(t, func(r *http.Request) (*http.Response, error) {
				if r.Method != "GET" {
					t.Errorf("mismatched resource must not be mutated: %s %s", r.Method, r.URL.Path)
				}
				body, err := json.Marshal(map[string]any{"Id": "fixture", "Config": config, "HostConfig": host, "State": state})
				if err != nil {
					t.Fatal(err)
				}
				return sdkResponse(200, string(body)), nil
			})
			if id, err := e.InstallTemplate(context.Background(), "uptime-kuma", "sdk-retry", 19001, "UTC"); id != "" || err == nil {
				t.Fatalf("mismatched resource accepted id=%q err=%v", id, err)
			}
		})
	}
}

func TestSDKTemplateRejectsUnownedVolume(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	_ = listener.Close()
	volumeCalls := 0
	e := sdkTestEngine(t, func(r *http.Request) (*http.Response, error) {
		switch r.URL.Path {
		case "/v1.51/containers/devpilot-sdk-retry/json":
			return sdkResponse(404, `{"message":"not found"}`), nil
		case "/v1.51/images/create":
			return sdkResponse(200, `{"status":"complete"}`), nil
		case "/v1.51/volumes/create":
			volumeCalls++
			return sdkResponse(201, `{"Name":"devpilot-sdk-retry-data","Labels":{"owner":"unrelated"}}`), nil
		default:
			t.Errorf("must not attach or delete unrelated volume: %s %s", r.Method, r.URL.Path)
			return sdkResponse(500, `{"message":"unexpected"}`), nil
		}
	})
	if id, err := e.InstallTemplate(context.Background(), "uptime-kuma", "sdk-retry", port, "UTC"); id != "" || err == nil || !strings.Contains(err.Error(), "existing data preserved") {
		t.Fatalf("id=%q err=%v", id, err)
	}
	if volumeCalls != 1 {
		t.Fatalf("volume calls=%d", volumeCalls)
	}
}

func sdkTestEngine(t *testing.T, transport sdkTestTransport) *Engine {
	t.Helper()
	c, err := client.New(client.WithHost("http://docker.invalid"), client.WithAPIVersion("1.51"),
		client.WithHTTPClient(&http.Client{Transport: transport}))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return &Engine{client: c}
}

// Exercise the real SDK encoder without access to any Docker daemon.
func TestSDKContainerControlRequests(t *testing.T) {
	for _, tc := range []struct{ action, method, path string }{
		{"START", "POST", "/v1.51/containers/fixture/start"},
		{"STOP", "POST", "/v1.51/containers/fixture/stop"},
		{"RESTART", "POST", "/v1.51/containers/fixture/restart"},
		{"REMOVE", "DELETE", "/v1.51/containers/fixture"},
	} {
		t.Run(tc.action, func(t *testing.T) {
			for _, status := range []int{204, 409} {
				calls := 0
				e := sdkTestEngine(t, func(r *http.Request) (*http.Response, error) {
					calls++
					if r.Method != tc.method || r.URL.Path != tc.path {
						t.Errorf("unexpected request: %s %s", r.Method, r.URL.Path)
					}
					q := r.URL.Query()
					if (tc.action == "STOP" || tc.action == "RESTART") && q.Get("t") != "10" {
						t.Error("stop timeout changed")
					}
					if tc.action == "REMOVE" && (q.Get("force") == "1" || q.Get("force") == "true" || q.Get("v") == "1" || q.Get("v") == "true") {
						t.Error("removal must preserve volumes and remain non-force")
					}
					return sdkResponse(status, `{"message":"fixture conflict"}`), nil
				})
				err := e.Execute(context.Background(), "fixture", tc.action)
				if calls != 1 || (err != nil) != (status == 409) {
					t.Fatalf("status=%d calls=%d error=%v", status, calls, err)
				}
			}
		})
	}
	e := sdkTestEngine(t, func(*http.Request) (*http.Response, error) {
		t.Error("unsupported action reached Docker")
		return nil, fmt.Errorf("unexpected request")
	})
	if e.Execute(context.Background(), "fixture", "EXEC") == nil {
		t.Fatal("unsupported action accepted")
	}
}

type sdkCountedBody struct {
	io.Reader
	closes int
}

func (b *sdkCountedBody) Close() error { b.closes++; return nil }

func TestSDKTemplatePullFailureDoesNotCreateResources(t *testing.T) {
	for _, body := range []string{
		`{"status":"Pulling"}` + "\n" + `{"errorDetail":{"message":"fixture registry denied","code":403},"error":"fixture registry denied"}`,
		`{"status":`,
	} {
		t.Run(body, func(t *testing.T) {
			listener, err := net.Listen("tcp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			port := listener.Addr().(*net.TCPAddr).Port
			_ = listener.Close()
			stream := &sdkCountedBody{Reader: strings.NewReader(body)}
			calls := 0
			e := sdkTestEngine(t, func(r *http.Request) (*http.Response, error) {
				calls++
				switch {
				case r.Method == "GET" && r.URL.Path == "/v1.51/containers/devpilot-sdk-fixture/json":
					return sdkResponse(404, `{"message":"not found"}`), nil
				case r.Method == "POST" && r.URL.Path == "/v1.51/images/create":
					response := sdkResponse(200, "")
					response.Body = stream
					return response, nil
				default:
					t.Errorf("pull failure must not create/start/remove resources: %s %s", r.Method, r.URL.Path)
					return sdkResponse(500, `{"message":"unexpected"}`), nil
				}
			})
			id, err := e.InstallTemplate(context.Background(), "uptime-kuma", "sdk-fixture", port, "UTC")
			if id != "" || err == nil || !strings.Contains(err.Error(), "read image pull result") {
				t.Fatalf("id=%q error=%v", id, err)
			}
			if calls != 2 || stream.closes != 1 {
				t.Fatalf("calls=%d stream closes=%d", calls, stream.closes)
			}
		})
	}
}
