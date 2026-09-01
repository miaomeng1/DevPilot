package metrics

import (
	"context"
	"fmt"
	"time"

	"github.com/shirou/gopsutil/v4/cpu"
	"github.com/shirou/gopsutil/v4/disk"
	"github.com/shirou/gopsutil/v4/load"
	"github.com/shirou/gopsutil/v4/mem"
	gnet "github.com/shirou/gopsutil/v4/net"
)

type Sample struct {
	AgentVersion         string    `json:"agentVersion,omitempty"`
	CollectedAt          time.Time `json:"collectedAt"`
	CPUUsage             float64   `json:"cpuUsage"`
	LoadOne              float64   `json:"loadOne"`
	LoadFive             float64   `json:"loadFive"`
	LoadFifteen          float64   `json:"loadFifteen"`
	MemoryTotal          uint64    `json:"memoryTotal"`
	MemoryUsed           uint64    `json:"memoryUsed"`
	MemoryAvailable      uint64    `json:"memoryAvailable"`
	DiskTotal            uint64    `json:"diskTotal"`
	DiskUsed             uint64    `json:"diskUsed"`
	DiskFree             uint64    `json:"diskFree"`
	NetworkBytesSent     uint64    `json:"networkBytesSent"`
	NetworkBytesReceived uint64    `json:"networkBytesReceived"`
	NetworkUploadRate    float64   `json:"networkUploadRate"`
	NetworkDownloadRate  float64   `json:"networkDownloadRate"`
}

type Collector struct {
	previousSent     uint64
	previousReceived uint64
	previousAt       time.Time
}

func NewCollector() *Collector {
	return &Collector{}
}

func (c *Collector) Collect(ctx context.Context) (Sample, error) {
	cpuValues, err := cpu.PercentWithContext(ctx, 250*time.Millisecond, false)
	if err != nil || len(cpuValues) == 0 {
		return Sample{}, fmt.Errorf("collect CPU usage: %w", err)
	}
	memory, err := mem.VirtualMemoryWithContext(ctx)
	if err != nil {
		return Sample{}, fmt.Errorf("collect memory usage: %w", err)
	}
	diskUsage, err := disk.UsageWithContext(ctx, "/")
	if err != nil {
		return Sample{}, fmt.Errorf("collect root disk usage: %w", err)
	}
	loadAverage, err := load.AvgWithContext(ctx)
	if err != nil {
		return Sample{}, fmt.Errorf("collect load average: %w", err)
	}
	network, err := gnet.IOCountersWithContext(ctx, false)
	if err != nil || len(network) == 0 {
		return Sample{}, fmt.Errorf("collect network usage: %w", err)
	}

	now := time.Now().UTC()
	uploadRate, downloadRate := c.rates(network[0].BytesSent, network[0].BytesRecv, now)
	return Sample{
		CollectedAt:          now,
		CPUUsage:             cpuValues[0],
		LoadOne:              loadAverage.Load1,
		LoadFive:             loadAverage.Load5,
		LoadFifteen:          loadAverage.Load15,
		MemoryTotal:          memory.Total,
		MemoryUsed:           memory.Used,
		MemoryAvailable:      memory.Available,
		DiskTotal:            diskUsage.Total,
		DiskUsed:             diskUsage.Used,
		DiskFree:             diskUsage.Free,
		NetworkBytesSent:     network[0].BytesSent,
		NetworkBytesReceived: network[0].BytesRecv,
		NetworkUploadRate:    uploadRate,
		NetworkDownloadRate:  downloadRate,
	}, nil
}

func (c *Collector) rates(sent, received uint64, now time.Time) (float64, float64) {
	var upload, download float64
	seconds := now.Sub(c.previousAt).Seconds()
	if !c.previousAt.IsZero() && seconds > 0 {
		if sent >= c.previousSent {
			upload = float64(sent-c.previousSent) / seconds
		}
		if received >= c.previousReceived {
			download = float64(received-c.previousReceived) / seconds
		}
	}
	c.previousSent = sent
	c.previousReceived = received
	c.previousAt = now
	return upload, download
}
