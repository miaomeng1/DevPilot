package host

import (
	"os"
	"runtime"
	"sort"
	"strconv"
	"strings"
)

// ListeningTCPPorts reports only socket listeners, not Docker NAT reservations.
// nil means unavailable; an empty non-nil slice means a successful empty scan.
func ListeningTCPPorts() []int {
	if runtime.GOOS != "linux" {
		return nil
	}
	ports := map[int]bool{}
	for _, path := range []string{"/proc/net/tcp", "/proc/net/tcp6"} {
		data, err := os.ReadFile(path)
		if err != nil {
			return nil
		}
		values, ok := parseTCPListeners(string(data))
		if !ok {
			return nil
		}
		for _, port := range values {
			ports[port] = true
		}
	}
	result := make([]int, 0, len(ports))
	for port := range ports {
		result = append(result, port)
	}
	sort.Ints(result)
	return result
}

func parseTCPListeners(table string) ([]int, bool) {
	lines := strings.Split(strings.TrimSpace(table), "\n")
	if len(lines) == 0 || !strings.Contains(lines[0], "local_address") {
		return nil, false
	}
	result := []int{}
	for _, line := range lines[1:] {
		fields := strings.Fields(line)
		if len(fields) < 4 {
			return nil, false
		}
		if fields[3] != "0A" {
			continue
		}
		address := strings.Split(fields[1], ":")
		if len(address) != 2 {
			return nil, false
		}
		port, err := strconv.ParseUint(address[1], 16, 16)
		if err != nil {
			return nil, false
		}
		result = append(result, int(port))
	}
	return result, true
}
