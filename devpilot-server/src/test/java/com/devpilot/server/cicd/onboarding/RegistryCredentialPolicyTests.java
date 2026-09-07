package com.devpilot.server.cicd.onboarding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.devpilot.server.exception.BusinessException;
import org.junit.jupiter.api.Test;

class RegistryCredentialPolicyTests {
    @Test void rejectsKnownIncompatibleGhcrTokenWithoutEchoingIt() {
        String secret = "github_pat_fake_test_only";
        var error = assertThrows(BusinessException.class,
                () -> RegistryCredentialPolicy.validate(" GHCR.IO/example/demo ", " " + secret + " "));
        assertTrue(error.getMessage().contains("read:packages"));
        assertFalse(error.getMessage().contains(secret));
    }

    @Test void doesNotClaimOtherCredentialsAreInvalidOrVerified() {
        assertDoesNotThrow(() -> RegistryCredentialPolicy.validate("ghcr.io/example/demo", "ghp_fixture"));
        assertDoesNotThrow(() -> RegistryCredentialPolicy.validate("ghcr.io/example/demo", ""));
        assertDoesNotThrow(() -> RegistryCredentialPolicy.validate("registry.example/demo", "github_pat_fixture"));
        assertDoesNotThrow(() -> RegistryCredentialPolicy.validate("ghcr.io.example/demo", "github_pat_fixture"));
        assertDoesNotThrow(() -> RegistryCredentialPolicy.validate(null, null));
    }

    @Test void providerRejectsBeforeRemoteReadOrWrite() {
        var http = mock(OnboardingHttpClient.class);
        var request = mock(OnboardingRequest.class);
        when(request.imageRepository()).thenReturn("ghcr.io/example/demo");
        when(request.registryPassword()).thenReturn("github_pat_fixture");
        var client = new ProviderOnboardingClient(http);
        assertThrows(BusinessException.class, () -> client.configure(request, "existing"));
        assertThrows(BusinessException.class, () -> client.refreshRegistryCredentials(request, "existing"));
        verifyNoInteractions(http);
    }
}
