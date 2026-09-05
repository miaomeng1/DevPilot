package com.devpilot.server;

import com.devpilot.server.cicd.onboarding.*;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Explicit test-only launcher; never packaged in the production image. No external mutations. */
public class OnboardingUiSmokeApplication {
    public static void main(String[] args) {
        SpringApplication.run(new Class<?>[]{DevPilotServerApplication.class, Fixtures.class}, args);
    }

    @TestConfiguration
    static class Fixtures {
        @Bean @Primary RepositoryOnboardingClient smokeRepository() {
            var client = mock(RepositoryOnboardingClient.class);
            when(client.inspect(anyString(), anyString(), anyString())).thenReturn(new RepositoryOnboardingClient.Repository(
                    "https://api.github.com/repos/example/smoke", "example/smoke", "main", "ghcr.io/example/smoke",
                    "FROM node:22\nEXPOSE 8080\n", "NODE", "https://github.com/example/smoke"));
            when(client.proposeWorkflow(any(), any(), anyString(), anyString())).thenReturn("https://github.com/example/smoke/pull/1");
            return client;
        }
        @Bean @Primary ProviderOnboardingClient smokeProvider() {
            var client = mock(ProviderOnboardingClient.class);
            when(client.discover(anyString(), anyString(), anyString())).thenReturn(new ProviderOnboardingClient.Discovery(
                    List.of(new ProviderOnboardingClient.Target("project1", "production1", "UI fixture / production")),
                    List.of(new ProviderOnboardingClient.Server("", "Local test fixture", "127.0.0.1"))));
            when(client.ensureDedicatedProject(any(), anyString(), anyString())).thenReturn(new ProviderOnboardingClient.Target("project1", "production1", "UI fixture / production"));
            when(client.ensureApplication(any(), anyString(), anyString())).thenReturn(new ProviderOnboardingClient.Application("fixture-app", "swarm:fixture-app"));
            return client;
        }
    }
}
