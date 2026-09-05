package docker

import "testing"

func TestRuntimeIdentitySurvivesReplacement(t *testing.T) {
	labels := map[string]string{"com.docker.swarm.service.name": "blog-prod"}
	if runtimeKey(labels, "blog-prod.1.old") != runtimeKey(labels, "blog-prod.1.new") {
		t.Fatal("task replacement changed stable identity")
	}
	if runtimeKey(nil, "/blog") == runtimeKey(nil, "/other") {
		t.Fatal("different applications must not share identity")
	}
}
