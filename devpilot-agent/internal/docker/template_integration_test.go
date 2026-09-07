package docker

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"os"
	"regexp"
	"testing"
	"time"

	"github.com/moby/moby/api/types/network"
	"github.com/moby/moby/client"
)

// Explicit isolated-lab acceptance. Creates a unique instance and preserves its
// data volume; cleanup only removes the owned container returned by installation.
func TestTemplateRealInstallAndRetry(t *testing.T) {
	if os.Getenv("DEVPILOT_TEST_TEMPLATE") != "isolated-lab-only" {
		t.Skip("requires isolated template acceptance")
	}
	// Offline labs can import the same official image into their loopback
	// Registry. This override exists only in the test binary, never the Agent.
	if mirror := os.Getenv("DEVPILOT_TEST_TEMPLATE_MIRROR"); mirror != "" {
		if !regexp.MustCompile(`^127\.0\.0\.1:15000/[a-z0-9/_-]+@sha256:[a-f0-9]{64}$`).MatchString(mirror) {
			t.Fatal("test mirror must be an immutable isolated loopback image")
		}
		original := serviceTemplates["uptime-kuma"]
		spec := original
		spec.image = mirror
		serviceTemplates["uptime-kuma"] = spec
		defer func() { serviceTemplates["uptime-kuma"] = original }()
	}
	e, err := NewEngine()
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	_ = listener.Close()
	name := fmt.Sprintf("sdk-kuma-%d", time.Now().UnixNano())
	t.Logf("fixture=%s port=%d; test volume will be preserved", name, port)
	id, err := e.InstallTemplate(ctx, "uptime-kuma", name, port, "UTC")
	if err != nil {
		t.Fatalf("install %s (inspect preserved resources before retry): %v", name, err)
	}
	defer func() {
		cleanupCtx, done := context.WithTimeout(context.Background(), 20*time.Second)
		defer done()
		inspection, err := e.client.ContainerInspect(cleanupCtx, id, client.ContainerInspectOptions{})
		if err != nil {
			t.Errorf("cleanup inspect: %v", err)
			return
		}
		if inspection.Container.Config == nil || inspection.Container.Config.Labels["com.devpilot.instance"] != name {
			t.Error("cleanup ownership mismatch")
			return
		}
		if _, err := e.client.ContainerRemove(cleanupCtx, id, client.ContainerRemoveOptions{Force: true, RemoveVolumes: false}); err != nil {
			t.Errorf("cleanup container: %v", err)
		}
	}()
	inspection, err := e.client.ContainerInspect(ctx, id, client.ContainerInspectOptions{})
	if err != nil {
		t.Fatal(err)
	}
	c := inspection.Container
	if c.State == nil || !c.State.Running || c.HostConfig == nil {
		t.Fatal("new template not running")
	}
	bindings := c.HostConfig.PortBindings[network.MustParsePort("3001/tcp")]
	if len(bindings) != 1 || bindings[0].HostIP.String() != "127.0.0.1" || bindings[0].HostPort != fmt.Sprint(port) {
		t.Fatalf("unexpected binding: %v", bindings)
	}
	if len(c.Mounts) != 1 || c.Mounts[0].Name != "devpilot-"+name+"-data" || c.Mounts[0].Destination != "/app/data" {
		t.Fatal("unexpected data mount")
	}
	httpClient := &http.Client{Timeout: 3 * time.Second}
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		request, _ := http.NewRequestWithContext(ctx, "GET", fmt.Sprintf("http://127.0.0.1:%d/", port), nil)
		response, requestErr := httpClient.Do(request)
		if requestErr == nil {
			_ = response.Body.Close()
			if response.StatusCode == 200 {
				break
			}
		}
		select {
		case <-ctx.Done():
			t.Fatal("template HTTP health did not become ready")
		case <-ticker.C:
		}
	}
	if retryID, err := e.InstallTemplate(ctx, "uptime-kuma", name, port, "UTC"); err != nil || retryID != id {
		t.Fatalf("running retry id=%q err=%v", retryID, err)
	}
	if err := e.Execute(ctx, id, "STOP"); err != nil {
		t.Fatal(err)
	}
	if retryID, err := e.InstallTemplate(ctx, "uptime-kuma", name, port, "UTC"); err != nil || retryID != id {
		t.Fatalf("stopped retry id=%q err=%v", retryID, err)
	}
	if _, err := e.InstallTemplate(ctx, "uptime-kuma", name, port, "Asia/Shanghai"); err == nil {
		t.Fatal("changed configuration accepted")
	}
	t.Logf("HTTP 200, loopback binding, persistent mount, running/stopped retries same ID, configuration mismatch rejected; retained volume=devpilot-%s-data", name)
}
