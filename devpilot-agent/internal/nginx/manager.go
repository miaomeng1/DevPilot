package nginx

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

const maxConfigBytes = 256 * 1024

var safeFilename = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]*\.conf$`)

type FileSnapshot struct {
	Filename    string `json:"filename"`
	Content     string `json:"content"`
	ContentHash string `json:"contentHash"`
}

type Snapshot struct {
	AgentVersion string         `json:"agentVersion,omitempty"`
	Enabled      bool           `json:"enabled"`
	Available    bool           `json:"available"`
	NginxVersion string         `json:"nginxVersion,omitempty"`
	ConfigPath   string         `json:"configPath,omitempty"`
	ErrorMessage string         `json:"errorMessage,omitempty"`
	CollectedAt  time.Time      `json:"collectedAt"`
	Files        []FileSnapshot `json:"files"`
}

type Manager struct {
	enabled      bool
	configPath   string
	binary       string
	initialError error
	mu           sync.Mutex
}

func NewManager(enabled bool, configPath string) *Manager {
	manager := &Manager{enabled: enabled, configPath: configPath}
	if !enabled {
		return manager
	}
	root, err := filepath.EvalSymlinks(configPath)
	if err != nil {
		manager.initialError = fmt.Errorf("resolve Nginx config path: %w", err)
		return manager
	}
	manager.configPath = root
	manager.binary, err = exec.LookPath("nginx")
	if err != nil {
		manager.initialError = errors.New("nginx executable not found")
	}
	return manager
}

func newManagerWithBinary(configPath, binary string) *Manager {
	return &Manager{enabled: true, configPath: configPath, binary: binary}
}

func (m *Manager) Enabled() bool {
	return m != nil && m.enabled
}

func (m *Manager) Snapshot(ctx context.Context) Snapshot {
	if m == nil {
		return Snapshot{Enabled: false, Available: false, CollectedAt: time.Now().UTC(), Files: []FileSnapshot{}}
	}
	snapshot := Snapshot{Enabled: m.Enabled(), Available: false, ConfigPath: m.configPath,
		CollectedAt: time.Now().UTC(), Files: []FileSnapshot{}}
	if !m.Enabled() {
		return snapshot
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.initialError != nil {
		snapshot.ErrorMessage = m.initialError.Error()
		return snapshot
	}
	versionOutput, versionErr := m.run(ctx, "-v")
	if versionErr != nil {
		snapshot.ErrorMessage = versionErr.Error()
		return snapshot
	}
	snapshot.NginxVersion = normalizeVersion(versionOutput)
	entries, err := os.ReadDir(m.configPath)
	if err != nil {
		snapshot.ErrorMessage = fmt.Sprintf("read Nginx config path: %v", err)
		return snapshot
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].Name() < entries[j].Name() })
	var warnings []string
	for _, entry := range entries {
		if len(snapshot.Files) >= 500 {
			warnings = append(warnings, "configuration file limit reached")
			break
		}
		name := entry.Name()
		if entry.IsDir() || entry.Type()&os.ModeSymlink != 0 || !safeFilename.MatchString(name) {
			continue
		}
		path := filepath.Join(m.configPath, name)
		info, statErr := entry.Info()
		if statErr != nil || !info.Mode().IsRegular() {
			continue
		}
		if info.Size() > maxConfigBytes {
			warnings = append(warnings, name+" exceeds 256 KiB")
			continue
		}
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			warnings = append(warnings, name+" could not be read")
			continue
		}
		digest := sha256.Sum256(content)
		snapshot.Files = append(snapshot.Files, FileSnapshot{Filename: name, Content: string(content),
			ContentHash: hex.EncodeToString(digest[:])})
	}
	snapshot.Available = true
	snapshot.ErrorMessage = strings.Join(warnings, "; ")
	return snapshot
}

func (m *Manager) Apply(ctx context.Context, filename, content string) (string, error) {
	if !m.Enabled() {
		return "", errors.New("Nginx management is disabled")
	}
	if m.initialError != nil {
		return "", m.initialError
	}
	if !safeFilename.MatchString(filename) || filepath.Base(filename) != filename {
		return "", errors.New("unsafe Nginx configuration filename")
	}
	if len(content) == 0 || len(content) > maxConfigBytes {
		return "", errors.New("Nginx configuration must contain 1 to 262144 bytes")
	}
	m.mu.Lock()
	defer m.mu.Unlock()

	target := filepath.Join(m.configPath, filename)
	info, err := os.Lstat(target)
	if err != nil {
		return "", fmt.Errorf("inspect Nginx configuration: %w", err)
	}
	if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
		return "", errors.New("Nginx configuration target must be a regular file")
	}
	stage, err := os.CreateTemp(m.configPath, ".devpilot-stage-")
	if err != nil {
		return "", fmt.Errorf("create staged Nginx configuration: %w", err)
	}
	stageName := stage.Name()
	defer os.Remove(stageName)
	if chmodErr := stage.Chmod(info.Mode().Perm()); chmodErr != nil {
		stage.Close()
		return "", fmt.Errorf("preserve Nginx configuration permissions: %w", chmodErr)
	}
	if _, err = stage.WriteString(content); err == nil {
		err = stage.Sync()
	}
	if closeErr := stage.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return "", fmt.Errorf("write staged Nginx configuration: %w", err)
	}
	validationOutput, validationErr := m.validateCandidate(ctx, filename, content)
	if validationErr != nil {
		return validationOutput, fmt.Errorf("nginx -t failed: %w", validationErr)
	}
	backup, err := os.CreateTemp(m.configPath, ".devpilot-backup-")
	if err != nil {
		return "", fmt.Errorf("reserve Nginx backup path: %w", err)
	}
	backupName := backup.Name()
	backup.Close()
	if err = os.Remove(backupName); err != nil {
		return "", fmt.Errorf("prepare Nginx backup path: %w", err)
	}
	if err = os.Rename(target, backupName); err != nil {
		return "", fmt.Errorf("backup active Nginx configuration: %w", err)
	}
	restoreNeeded := true
	defer func() {
		if restoreNeeded {
			_ = os.Remove(target)
			_ = os.Rename(backupName, target)
		}
	}()
	if err = os.Rename(stageName, target); err != nil {
		return "", fmt.Errorf("activate staged Nginx configuration: %w", err)
	}
	reloadOutput, reloadErr := m.run(ctx, "-s", "reload")
	combinedOutput := strings.TrimSpace(strings.Join([]string{validationOutput, reloadOutput}, "\n"))
	if reloadErr != nil {
		_ = os.Remove(target)
		if restoreErr := os.Rename(backupName, target); restoreErr != nil {
			return combinedOutput, fmt.Errorf("nginx reload failed and backup restore failed: %v; %w", restoreErr, reloadErr)
		}
		restoreNeeded = false
		_, _ = m.run(ctx, "-t")
		_, _ = m.run(ctx, "-s", "reload")
		return combinedOutput, fmt.Errorf("nginx reload failed; previous configuration restored: %w", reloadErr)
	}
	restoreNeeded = false
	if err = os.Remove(backupName); err != nil {
		combinedOutput = strings.TrimSpace(combinedOutput + "\nwarning: stale DevPilot backup could not be removed")
	}
	return combinedOutput, nil
}

// validateCandidate asks Nginx to parse a disposable configuration assembled
// from the current directory with the candidate content substituted in place.
// The live file is deliberately left untouched until this preflight succeeds.
func (m *Manager) validateCandidate(ctx context.Context, filename, content string) (string, error) {
	validationRoot, err := os.MkdirTemp("", "devpilot-nginx-validate-")
	if err != nil {
		return "", fmt.Errorf("create validation workspace: %w", err)
	}
	defer os.RemoveAll(validationRoot)
	if err = os.Chmod(validationRoot, 0o700); err != nil {
		return "", fmt.Errorf("secure validation workspace: %w", err)
	}

	entries, err := os.ReadDir(m.configPath)
	if err != nil {
		return "", fmt.Errorf("read Nginx configuration directory: %w", err)
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].Name() < entries[j].Name() })
	var configuration strings.Builder
	configuration.WriteString("pid ")
	configuration.WriteString(nginxQuote(filepath.Join(validationRoot, "nginx.pid")))
	configuration.WriteString(";\nerror_log stderr;\nevents {}\nhttp {\n")
	candidateAdded := false
	for _, entry := range entries {
		name := entry.Name()
		if entry.IsDir() || entry.Type()&os.ModeSymlink != 0 || !safeFilename.MatchString(name) {
			continue
		}
		info, infoErr := entry.Info()
		if infoErr != nil || !info.Mode().IsRegular() {
			continue
		}
		if name == filename {
			configuration.WriteString("# DevPilot candidate: ")
			configuration.WriteString(filename)
			configuration.WriteString("\n")
			configuration.WriteString(content)
			if !strings.HasSuffix(content, "\n") {
				configuration.WriteByte('\n')
			}
			candidateAdded = true
			continue
		}
		configuration.WriteString("include ")
		configuration.WriteString(nginxQuote(filepath.Join(m.configPath, name)))
		configuration.WriteString(";\n")
	}
	if !candidateAdded {
		return "", errors.New("Nginx configuration target disappeared before validation")
	}
	configuration.WriteString("}\n")
	validationConfig := filepath.Join(validationRoot, "nginx.conf")
	if err = os.WriteFile(validationConfig, []byte(configuration.String()), 0o600); err != nil {
		return "", fmt.Errorf("write validation configuration: %w", err)
	}
	return m.run(ctx, "-t", "-p", validationRoot+string(os.PathSeparator), "-c", validationConfig)
}

func nginxQuote(value string) string {
	value = strings.ReplaceAll(value, `\`, `\\`)
	value = strings.ReplaceAll(value, `"`, `\"`)
	return `"` + value + `"`
}

func (m *Manager) run(ctx context.Context, args ...string) (string, error) {
	if m.binary == "" {
		return "", errors.New("nginx executable not found")
	}
	command := exec.CommandContext(ctx, m.binary, args...)
	var output limitedBuffer
	command.Stdout = &output
	command.Stderr = &output
	err := command.Run()
	return strings.TrimSpace(output.String()), err
}

func normalizeVersion(value string) string {
	trimmed := strings.TrimSpace(value)
	return strings.TrimPrefix(trimmed, "nginx version: ")
}

type limitedBuffer struct {
	buffer bytes.Buffer
}

func (b *limitedBuffer) Write(value []byte) (int, error) {
	original := len(value)
	remaining := 64*1024 - b.buffer.Len()
	if remaining > 0 {
		if len(value) > remaining {
			value = value[:remaining]
		}
		_, _ = b.buffer.Write(value)
	}
	return original, nil
}

func (b *limitedBuffer) String() string { return b.buffer.String() }
