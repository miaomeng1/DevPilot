package docker

import (
	"context"
	"fmt"
	"os"
	"testing"
	"time"

	"github.com/containerd/errdefs"
	"github.com/moby/moby/api/types/container"
	"github.com/moby/moby/client"
)

// Opt-in against the isolated Linux lab only; never starts/stops containers.
func TestDockerSDKReadOnlyCompatibility(t *testing.T) {
	if os.Getenv("DEVPILOT_TEST_DOCKER_READONLY") != "true" {
		t.Skip("requires explicit isolated Docker read-only acceptance")
	}
	logContainer := os.Getenv("DEVPILOT_TEST_LOG_CONTAINER")
	if logContainer == "" {
		t.Fatal("an explicit test log container is required")
	}
	engine, err := NewEngine()
	if err != nil {
		t.Fatal(err)
	}
	defer engine.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	snapshot := engine.Snapshot(ctx)
	if !snapshot.Available || snapshot.EngineVersion == "" || len(snapshot.Containers) == 0 {
		t.Fatalf("Docker inventory unavailable: available=%v version=%q count=%d", snapshot.Available, snapshot.EngineVersion, len(snapshot.Containers))
	}
	for _, container := range snapshot.Containers {
		if container.ContainerID == "" || container.Name == "" || container.Image == "" {
			t.Fatal("container identity missing after SDK migration")
		}
	}
	lines := 0
	if err := engine.StreamLogs(ctx, logContainer, 100, false, func(string) error {
		lines++ // Do not emit potentially sensitive log contents.
		return nil
	}); err != nil {
		t.Fatalf("read test container logs: %v", err)
	}
	t.Logf("read-only Docker %s: %d containers, %d log lines", snapshot.EngineVersion, len(snapshot.Containers), lines)
}

// Mutating acceptance creates its own disposable, network-isolated container.
// No existing container ID/name is accepted as input and no volumes are mounted.
func TestDockerSDKControlCompatibility(t *testing.T) {
	if os.Getenv("DEVPILOT_TEST_DOCKER_CONTROL") != "isolated-lab-only" {
		t.Skip("requires explicit isolated Docker control acceptance")
	}
	image := os.Getenv("DEVPILOT_TEST_CONTROL_IMAGE")
	if image == "" {
		t.Fatal("explicit locally available test image required")
	}
	e, err := NewEngine()
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	name := fmt.Sprintf("devpilot-sdk-control-%d", time.Now().UnixNano())
	created, err := e.client.ContainerCreate(ctx, client.ContainerCreateOptions{
		Name: name,
		Config: &container.Config{Image: image, Entrypoint: []string{"/bin/sh", "-c"},
			Cmd:    []string{"trap 'exit 0' TERM; while :; do sleep 1 & wait $!; done"},
			Labels: map[string]string{"com.devpilot.test.sdk": name}},
		HostConfig: &container.HostConfig{NetworkMode: "none", ReadonlyRootfs: true,
			SecurityOpt: []string{"no-new-privileges:true"}, CapDrop: []string{"ALL"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	// Cleanup is limited to the newly returned ID, with an ownership recheck.
	defer func() {
		cleanupCtx, done := context.WithTimeout(context.Background(), 15*time.Second)
		defer done()
		inspection, err := e.client.ContainerInspect(cleanupCtx, created.ID, client.ContainerInspectOptions{})
		if errdefs.IsNotFound(err) {
			return
		}
		if err != nil {
			t.Errorf("fixture cleanup inspect: %v", err)
			return
		}
		if inspection.Container.Config == nil || inspection.Container.Config.Labels["com.devpilot.test.sdk"] != name {
			t.Error("fixture ownership mismatch; refusing cleanup")
			return
		}
		if _, err := e.client.ContainerRemove(cleanupCtx, created.ID, client.ContainerRemoveOptions{Force: true, RemoveVolumes: false}); err != nil {
			t.Errorf("fixture cleanup: %v", err)
		}
	}()
	inspectRunning := func(want bool) string {
		t.Helper()
		result, err := e.client.ContainerInspect(ctx, created.ID, client.ContainerInspectOptions{})
		if err != nil {
			t.Fatal(err)
		}
		if result.Container.State == nil || result.Container.State.Running != want {
			t.Fatalf("unexpected fixture state: %+v", result.Container.State)
		}
		return result.Container.State.StartedAt
	}
	inspectRunning(false)
	if err := e.Execute(ctx, created.ID, "START"); err != nil {
		t.Fatal(err)
	}
	started := inspectRunning(true)
	if err := e.Execute(ctx, created.ID, "REMOVE"); err == nil {
		t.Fatal("non-force removal of running container unexpectedly succeeded")
	}
	inspectRunning(true)
	if err := e.Execute(ctx, created.ID, "RESTART"); err != nil {
		t.Fatal(err)
	}
	if inspectRunning(true) == started {
		t.Fatal("restart did not change StartedAt")
	}
	if err := e.Execute(ctx, created.ID, "STOP"); err != nil {
		t.Fatal(err)
	}
	inspectRunning(false)
	if err := e.Execute(ctx, created.ID, "REMOVE"); err != nil {
		t.Fatal(err)
	}
	if _, err := e.client.ContainerInspect(ctx, created.ID, client.ContainerInspectOptions{}); !errdefs.IsNotFound(err) {
		t.Fatalf("removed fixture still exists or inspect failed: %v", err)
	}
	t.Log("created isolated fixture; start, protected remove, restart, stop, remove verified")
}
