package docker

import "strings"

// Use provider identities, never an image name: unrelated apps may run the same image.
func runtimeKey(labels map[string]string, name string) string {
	if id := labels["com.devpilot.application"]; id != "" {
		return "devpilot:" + id
	}
	if id := labels["coolify.applicationId"]; id != "" {
		return "coolify:" + id
	}
	if service := labels["com.docker.swarm.service.name"]; service != "" {
		return "swarm:" + service
	}
	if project, service := labels["com.docker.compose.project"], labels["com.docker.compose.service"]; project != "" && service != "" {
		return "compose:" + project + ":" + service
	}
	return "name:" + strings.TrimPrefix(name, "/")
}
