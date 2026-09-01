package host

import (
	"context"
	"fmt"
	"net"
	"runtime"
	"strings"

	"github.com/shirou/gopsutil/v4/cpu"
	"github.com/shirou/gopsutil/v4/disk"
	goshost "github.com/shirou/gopsutil/v4/host"
	"github.com/shirou/gopsutil/v4/mem"
)

type Snapshot struct {
	Hostname     string `json:"hostname"`
	IP           string `json:"ip"`
	OS           string `json:"os"`
	Kernel       string `json:"kernel"`
	Architecture string `json:"arch"`
	CPUModel     string `json:"cpuModel"`
	CPUCores     int    `json:"cpuCores"`
	MemoryTotal  uint64 `json:"memoryTotal"`
	DiskTotal    uint64 `json:"diskTotal"`
}

func Collect(ctx context.Context) (Snapshot, error) {
	hostInfo, err := goshost.InfoWithContext(ctx)
	if err != nil {
		return Snapshot{}, fmt.Errorf("collect host information: %w", err)
	}
	memory, err := mem.VirtualMemoryWithContext(ctx)
	if err != nil {
		return Snapshot{}, fmt.Errorf("collect memory information: %w", err)
	}
	cores, err := cpu.CountsWithContext(ctx, true)
	if err != nil || cores < 1 {
		return Snapshot{}, fmt.Errorf("collect CPU count: %w", err)
	}
	cpuInfo, _ := cpu.InfoWithContext(ctx)
	cpuModel := "unknown"
	if len(cpuInfo) > 0 && strings.TrimSpace(cpuInfo[0].ModelName) != "" {
		cpuModel = cpuInfo[0].ModelName
	}

	diskTotal, err := rootDiskTotal(ctx)
	if err != nil {
		return Snapshot{}, err
	}
	osName := strings.TrimSpace(strings.Join([]string{hostInfo.Platform, hostInfo.PlatformVersion}, " "))
	if osName == "" {
		osName = runtime.GOOS
	}

	return Snapshot{
		Hostname:     hostInfo.Hostname,
		IP:           primaryIP(),
		OS:           osName,
		Kernel:       hostInfo.KernelVersion,
		Architecture: runtime.GOARCH,
		CPUModel:     cpuModel,
		CPUCores:     cores,
		MemoryTotal:  memory.Total,
		DiskTotal:    diskTotal,
	}, nil
}

func rootDiskTotal(ctx context.Context) (uint64, error) {
	partitions, err := disk.PartitionsWithContext(ctx, false)
	if err != nil {
		return 0, fmt.Errorf("collect disk partitions: %w", err)
	}
	paths := []string{"/"}
	for _, partition := range partitions {
		if partition.Mountpoint != "/" {
			paths = append(paths, partition.Mountpoint)
		}
	}
	for _, path := range paths {
		usage, usageErr := disk.UsageWithContext(ctx, path)
		if usageErr == nil && usage.Total > 0 {
			return usage.Total, nil
		}
	}
	return 0, fmt.Errorf("collect root disk usage: no readable filesystem")
}

func primaryIP() string {
	interfaces, err := net.Interfaces()
	if err != nil {
		return "127.0.0.1"
	}
	for _, networkInterface := range interfaces {
		if networkInterface.Flags&net.FlagUp == 0 || networkInterface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addresses, addressErr := networkInterface.Addrs()
		if addressErr != nil {
			continue
		}
		for _, address := range addresses {
			ip, _, parseErr := net.ParseCIDR(address.String())
			if parseErr == nil && ip.IsGlobalUnicast() && ip.To4() != nil {
				return ip.String()
			}
		}
	}
	return "127.0.0.1"
}
