package host

import (
	"net"
	"runtime"
	"slices"
	"testing"
)

func TestLiveTCPListenerIsObserved(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("Linux procfs evidence")
	}
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	port := listener.Addr().(*net.TCPAddr).Port
	if ports := ListeningTCPPorts(); ports == nil || !slices.Contains(ports, port) {
		t.Fatalf("live port %d absent from %v", port, ports)
	}
}

func TestTCPListeners(t *testing.T) {
	table := "  sl local_address rem_address st\n0: 00000000:1F90 00000000:0000 0A\n1: 0100007F:2328 0100007F:1234 01\n"
	ports, ok := parseTCPListeners(table)
	if !ok || len(ports) != 1 || ports[0] != 8080 {
		t.Fatalf("listeners = %v, %v", ports, ok)
	}
	for _, bad := range []string{"", "wrong header", "local_address\nmalformed", "local_address\n0: bad remote 0A"} {
		if _, ok := parseTCPListeners(bad); ok {
			t.Fatalf("accepted malformed table %q", bad)
		}
	}
	ports, ok = parseTCPListeners("sl local_address rem_address st\n")
	if !ok || ports == nil || len(ports) != 0 {
		t.Fatal("empty successful scan must differ from unavailable")
	}
}
