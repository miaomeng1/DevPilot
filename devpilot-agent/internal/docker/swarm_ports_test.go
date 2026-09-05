package docker

import (
	"github.com/docker/docker/api/types/swarm"
	"testing"
)

func TestSwarmPublishedPortsMatchServiceNotImage(t *testing.T) {
	containers := []ContainerSnapshot{
		{RuntimeKey: "swarm:demo", Ports: []string{"8080/tcp"}},
		{RuntimeKey: "swarm:unrelated", Ports: []string{"8080/tcp"}},
	}
	service := swarm.Service{}
	service.Spec.Name = "demo"
	service.Endpoint.Ports = []swarm.PortConfig{{PublishedPort: 18088, TargetPort: 8080, Protocol: swarm.PortConfigProtocolTCP, PublishMode: swarm.PortConfigPublishModeIngress}}
	appendSwarmPorts(containers, []swarm.Service{service})
	if len(containers[0].Ports) != 2 || containers[0].Ports[1] != "Swarm ingress :18088→8080/tcp" {
		t.Fatalf("missing published port: %#v", containers[0].Ports)
	}
	if len(containers[1].Ports) != 1 {
		t.Fatal("unrelated service acquired port mapping")
	}
}
