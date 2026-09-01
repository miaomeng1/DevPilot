package docker

import (
	"reflect"
	"testing"

	"github.com/docker/docker/api/types/container"
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
