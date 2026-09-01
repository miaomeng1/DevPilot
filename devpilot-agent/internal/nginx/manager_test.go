package nginx

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestApplyValidatesReloadsAndSnapshots(t *testing.T) {
	root := t.TempDir()
	configPath := filepath.Join(root, "site.conf")
	if err := os.WriteFile(configPath, []byte("server { listen 80; }\n"), 0o640); err != nil {
		t.Fatal(err)
	}
	binary := fakeNginx(t, root, false)
	manager := newManagerWithBinary(root, binary)
	output, err := manager.Apply(context.Background(), "site.conf", "server { listen 8080; }\n")
	if err != nil || !strings.Contains(output, "test is successful") {
		t.Fatalf("Apply() = %q, %v", output, err)
	}
	content, _ := os.ReadFile(configPath)
	if string(content) != "server { listen 8080; }\n" {
		t.Fatalf("content = %q", content)
	}
	validatedContent, _ := os.ReadFile(filepath.Join(root, "content-at-validation"))
	if string(validatedContent) != "server { listen 80; }\n" {
		t.Fatalf("active content during validation = %q", validatedContent)
	}
	snapshot := manager.Snapshot(context.Background())
	if !snapshot.Available || len(snapshot.Files) != 1 || snapshot.Files[0].Filename != "site.conf" {
		t.Fatalf("Snapshot() = %#v", snapshot)
	}
}

func TestApplyRestoresOriginalWhenValidationFails(t *testing.T) {
	root := t.TempDir()
	configPath := filepath.Join(root, "site.conf")
	original := "server { listen 80; }\n"
	if err := os.WriteFile(configPath, []byte(original), 0o640); err != nil {
		t.Fatal(err)
	}
	manager := newManagerWithBinary(root, fakeNginx(t, root, true))
	if _, err := manager.Apply(context.Background(), "site.conf", "INVALID\n"); err == nil {
		t.Fatal("Apply() error = nil")
	}
	content, _ := os.ReadFile(configPath)
	if string(content) != original {
		t.Fatalf("failed validation changed active content to %q", content)
	}
}

func TestApplyRejectsTraversal(t *testing.T) {
	manager := newManagerWithBinary(t.TempDir(), "/bin/true")
	if _, err := manager.Apply(context.Background(), "../nginx.conf", "events {}\n"); err == nil {
		t.Fatal("Apply() accepted path traversal")
	}
}

func fakeNginx(t *testing.T, root string, failInvalid bool) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "nginx")
	failure := ""
	if failInvalid {
		failure = "grep -q INVALID \"$validation_config\" && { echo invalid >&2; exit 1; }\n"
	}
	script := "#!/bin/sh\n" +
		"if [ \"$1\" = \"-v\" ]; then echo 'nginx version: nginx/test' >&2; exit 0; fi\n" +
		"if [ \"$1\" = \"-t\" ]; then\n" +
		"  cat " + filepath.Join(root, "site.conf") + " > " + filepath.Join(root, "content-at-validation") + "\n" +
		"  shift\n" +
		"  validation_config=''\n" +
		"  while [ \"$#\" -gt 0 ]; do\n" +
		"    if [ \"$1\" = \"-c\" ]; then shift; validation_config=\"$1\"; break; fi\n" +
		"    shift\n" +
		"  done\n" + failure +
		"fi\n" +
		"echo 'nginx: configuration file test is successful' >&2\n"
	if err := os.WriteFile(path, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	return path
}
