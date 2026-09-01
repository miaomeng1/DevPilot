.PHONY: test build web agent server compose-config cicd-verify maintenance-verify dokploy-lab-start dokploy-lab-status dokploy-lab-stop

test:
	cd devpilot-server && mvn test
	cd devpilot-web && npm run type-check && npm run build
	cd devpilot-agent && go test ./...

build: server web agent

server:
	cd devpilot-server && mvn -DskipTests package

web:
	cd devpilot-web && npm run build

agent:
	cd devpilot-agent && go build -o bin/devpilot-agent ./cmd/devpilot-agent

compose-config:
	docker compose --env-file .env.example -f deploy/docker-compose.yml config --quiet

cicd-verify:
	bash scripts/verify-cicd.sh

maintenance-verify:
	docker run --rm -v "$(CURDIR):/repo:ro" maven:3.9-eclipse-temurin-21 bash /repo/scripts/test-maintenance.sh

dokploy-lab-start:
	bash scripts/dokploy-lab.sh start

dokploy-lab-status:
	bash scripts/dokploy-lab.sh status

dokploy-lab-stop:
	bash scripts/dokploy-lab.sh stop
