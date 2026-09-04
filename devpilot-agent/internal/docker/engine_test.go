package docker

import (
	"reflect"
	"strings"
	"testing"

	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/api/types/mount"
)

func TestLineWriterFramesPartialLines(t *testing.T) {
	var lines []string
	writer := &lineWriter{send: func(line string) error { lines = append(lines, line); return nil }}
	_, _ = writer.Write([]byte("first\nsec"))
	_, _ = writer.Write([]byte("ond\r\nthird"))
	_ = writer.flush()
	want := []string{"first", "second", "third"}
	if !reflect.DeepEqual(lines, want) {
		t.Fatalf("lines = %#v, want %#v", lines, want)
	}
}

func TestMaskEnvironment(t *testing.T) {
	masked := maskEnvironment([]string{
		"MODE=prod", "MYSQL_PASSWORD=unsafe", "API_TOKEN=value", "AWS_ACCESS_KEY_ID=identifier",
		"DATABASE_URL=postgres://user:password@database:5432/app", "PUBLIC_URL=https://example.test",
	})
	if masked[0] != "MODE=prod" || masked[1] != "MYSQL_PASSWORD=******" ||
		masked[2] != "API_TOKEN=******" || masked[3] != "AWS_ACCESS_KEY_ID=******" ||
		masked[4] != "DATABASE_URL=******" || masked[5] != "PUBLIC_URL=https://example.test" {
		t.Fatalf("maskEnvironment() = %#v", masked)
	}
}

func TestCPUPercentAndMemoryWorkingSet(t *testing.T) {
	stats := container.StatsResponse{}
	stats.CPUStats.CPUUsage.TotalUsage = 300
	stats.PreCPUStats.CPUUsage.TotalUsage = 100
	stats.CPUStats.SystemUsage = 2000
	stats.PreCPUStats.SystemUsage = 1000
	stats.CPUStats.OnlineCPUs = 4
	stats.MemoryStats.Usage = 1000
	stats.MemoryStats.Stats = map[string]uint64{"inactive_file": 200}
	if got := cpuPercent(stats); got != 80 {
		t.Fatalf("cpuPercent() = %v, want 80", got)
	}
	if got := memoryUsage(stats); got != 800 {
		t.Fatalf("memoryUsage() = %v, want 800", got)
	}
}

func TestServiceTemplateSecurityDefaults(t *testing.T) {
	for templateID, spec := range serviceTemplates {
		if strings.HasSuffix(spec.image, ":latest") || !strings.Contains(spec.image, ":") {
			t.Fatalf("template %s image is not version-pinned: %s", templateID, spec.image)
		}
		mounts := []mount.Mount{{Type: mount.TypeVolume, Source: "devpilot-test-data", Target: "/data"}}
		config, hostConfig := buildTemplateConfiguration(templateID, "personal-service", "Asia/Shanghai",
			12345, spec, mounts)
		if config.Image != spec.image || config.Labels["com.devpilot.managed"] != "true" {
			t.Fatalf("template %s missing pinned image or ownership label", templateID)
		}
		for _, bindings := range hostConfig.PortBindings {
			if len(bindings) != 1 || bindings[0].HostIP != "127.0.0.1" || bindings[0].HostPort != "12345" {
				t.Fatalf("template %s has unsafe port binding: %#v", templateID, bindings)
			}
		}
		if hostConfig.Privileged || hostConfig.Resources.Memory <= 0 ||
			!reflect.DeepEqual(hostConfig.SecurityOpt, []string{"no-new-privileges:true"}) {
			t.Fatalf("template %s has unsafe runtime defaults", templateID)
		}
		if hostConfig.LogConfig.Config["max-size"] != "10m" || hostConfig.LogConfig.Config["max-file"] != "3" {
			t.Fatalf("template %s does not bound container logs", templateID)
		}
	}
}

func TestServiceTemplateInputValidation(t *testing.T) {
	for _, value := range []string{"Asia/Shanghai", "UTC", "America/New_York", "Etc/GMT+8"} {
		if !validTimezone(value) {
			t.Fatalf("validTimezone(%q) = false", value)
		}
	}
	for _, value := range []string{"", "/UTC", "../UTC", "Asia//Shanghai", "Asia/Shanghai;rm"} {
		if validTimezone(value) {
			t.Fatalf("validTimezone(%q) = true", value)
		}
	}
	for _, value := range []string{"UPPER", "ab", "-bad", "bad_name", strings.Repeat("a", 65)} {
		if instanceNamePattern.MatchString(value) {
			t.Fatalf("instance name %q unexpectedly accepted", value)
		}
	}
}
