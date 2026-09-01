package config

import "testing"

func TestParseAppliesDefaults(t *testing.T) {
	value, err := Parse([]byte(`
server:
  url: https://ops.example.com
agent:
  token: dp_agent_12345678901234567890
`))
	if err != nil {
		t.Fatalf("Parse() error = %v", err)
	}
	if value.Collect.IntervalSeconds != 10 {
		t.Fatalf("Collect.IntervalSeconds = %d, want 10", value.Collect.IntervalSeconds)
	}
	if value.Nginx.ConfigPath != "/etc/nginx/conf.d" {
		t.Fatalf("Nginx.ConfigPath = %q", value.Nginx.ConfigPath)
	}
}

func TestParseRejectsUnsafeConfig(t *testing.T) {
	_, err := Parse([]byte(`
server:
  url: file:///tmp/server
agent:
  token: short
collect:
  interval: 1
`))
	if err == nil {
		t.Fatal("Parse() error = nil, want validation error")
	}
}
