package config

import (
	"errors"
	"fmt"
	"net/url"
	"os"
	"strings"

	"gopkg.in/yaml.v3"
)

const DefaultPath = "/etc/devpilot-agent/config.yaml"

type Config struct {
	Server  ServerConfig  `yaml:"server"`
	Agent   AgentConfig   `yaml:"agent"`
	Collect CollectConfig `yaml:"collect"`
	Nginx   NginxConfig   `yaml:"nginx"`
}

type ServerConfig struct {
	URL string `yaml:"url"`
}

type AgentConfig struct {
	Token string `yaml:"token"`
}

type CollectConfig struct {
	IntervalSeconds int `yaml:"interval"`
}

type NginxConfig struct {
	Enabled    bool   `yaml:"enabled"`
	ConfigPath string `yaml:"configPath"`
}

func Load(path string) (Config, error) {
	contents, err := os.ReadFile(path)
	if err != nil {
		return Config{}, fmt.Errorf("read agent config: %w", err)
	}
	return Parse(contents)
}

func Parse(contents []byte) (Config, error) {
	config := Config{
		Collect: CollectConfig{IntervalSeconds: 10},
		Nginx:   NginxConfig{ConfigPath: "/etc/nginx/conf.d"},
	}
	if err := yaml.Unmarshal(contents, &config); err != nil {
		return Config{}, fmt.Errorf("parse agent config: %w", err)
	}
	if err := config.Validate(); err != nil {
		return Config{}, err
	}
	return config, nil
}

func (c Config) Validate() error {
	var problems []string
	serverURL, parseErr := url.Parse(c.Server.URL)
	if parseErr != nil || serverURL.Host == "" || (serverURL.Scheme != "http" && serverURL.Scheme != "https") {
		problems = append(problems, "server.url must use http or https")
	}
	if len(strings.TrimSpace(c.Agent.Token)) < 16 {
		problems = append(problems, "agent.token is missing or too short")
	}
	if c.Collect.IntervalSeconds < 5 || c.Collect.IntervalSeconds > 300 {
		problems = append(problems, "collect.interval must be between 5 and 300 seconds")
	}
	if c.Nginx.Enabled && strings.TrimSpace(c.Nginx.ConfigPath) == "" {
		problems = append(problems, "nginx.configPath is required when nginx is enabled")
	}
	return errors.Join(toErrors(problems)...)
}

func toErrors(messages []string) []error {
	errorsList := make([]error, 0, len(messages))
	for _, message := range messages {
		errorsList = append(errorsList, errors.New(message))
	}
	return errorsList
}
