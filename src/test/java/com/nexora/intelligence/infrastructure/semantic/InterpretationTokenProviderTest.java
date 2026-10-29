package com.nexora.intelligence.infrastructure.semantic;

import com.nexora.intelligence.application.port.out.SemanticUnavailableException;
import com.nexora.intelligence.application.port.out.SemanticUnavailableException.Reason;
import com.nexora.intelligence.domain.shared.TenantId;
import com.nimbusds.jose.JWSAlgorithm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tokens of the interpretation identity: obtained with client credentials and cryptographically verified (JWKS
 * signature, algorithm, expiry, issuer, audience) plus principal type, role and tenant before they are ever sent.
 */
class InterpretationTokenProviderTest {

    private SemanticFixture semantic;
    private TenantId tenant;

    @BeforeEach
    void setUp() throws Exception {
        semantic = new SemanticFixture();
        tenant = new TenantId(semantic.tenant);
    }

    @AfterEach
    void tearDown() {
        semantic.close();
    }

    private void tokenEndpointAnswers(String token) {
        semantic.server.answer(FakeSemanticServer.TOKEN_PATH, 200, semantic.tokens.tokenResponse(token));
    }

    private String withClaims(Consumer<Map<String, Object>> changes) {
        return semantic.tokens.token(semantic.tenant, changes);
    }

    private Reason refusal(String token) {
        tokenEndpointAnswers(token);
        try {
            semantic.tokenProvider.tokenFor(tenant);
        } catch (SemanticUnavailableException e) {
            return e.reason();
        }
        throw new AssertionError("the token was accepted");
    }

    @Test
    void aValidTokenIsObtainedWithClientCredentialsAndCached() {
        String token = semantic.tokens.token(semantic.tenant);
        tokenEndpointAnswers(token);

        assertThat(semantic.tokenProvider.tokenFor(tenant)).isEqualTo(token);
        assertThat(semantic.tokenProvider.tokenFor(tenant)).isEqualTo(token);

        List<FakeSemanticServer.Received> calls = semantic.server.received(FakeSemanticServer.TOKEN_PATH);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).method()).isEqualTo("POST");
        assertThat(calls.get(0).body()).isEqualTo("grant_type=client_credentials");
        assertThat(calls.get(0).header("Authorization")).isEqualTo("Basic " + java.util.Base64.getEncoder()
                .encodeToString(("nexora-intelligence:" + SemanticFixture.SECRET).getBytes()));
        assertThat(semantic.server.received(FakeSemanticServer.JWKS_PATH)).hasSize(1);
    }

    @Test
    void anEvictedTokenIsRenewed() {
        semantic.tokenProvider.tokenFor(tenant);
        semantic.tokenProvider.evict();
        semantic.tokenProvider.tokenFor(tenant);
        assertThat(semantic.server.received(FakeSemanticServer.TOKEN_PATH)).hasSize(2);
    }

    @Test
    void aTokenIsRenewedShortlyBeforeItExpires() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        InterpretationTokenProvider provider = new InterpretationTokenProvider(semantic.properties.identity(),
                new SemanticHttp(Duration.ofSeconds(2), Duration.ofSeconds(5), 1 << 20),
                SemanticConfiguration.jwtDecoder(semantic.properties), clock);

        provider.tokenFor(tenant);
        now.set(now.get().plus(Duration.ofMinutes(4)));
        provider.tokenFor(tenant);
        assertThat(semantic.server.received(FakeSemanticServer.TOKEN_PATH)).hasSize(1);
        now.set(now.get().plus(Duration.ofSeconds(40)));
        provider.tokenFor(tenant);
        assertThat(semantic.server.received(FakeSemanticServer.TOKEN_PATH)).hasSize(2);
    }

    @Test
    void aSignatureFromAnotherKeyIsRefused() {
        assertThat(refusal(semantic.tokens.forgedToken(semantic.tenant))).isEqualTo(Reason.IDENTITY_REJECTED);
    }

    @Test
    void anAlgorithmThatIsNotAllowedIsRefused() {
        assertThat(refusal(semantic.tokens.tokenWithAlgorithm(JWSAlgorithm.RS512, semantic.tenant)))
                .isEqualTo(Reason.IDENTITY_REJECTED);
    }

    @Test
    void anUnsignedOrMalformedTokenIsRefused() {
        String[] parts = semantic.tokens.token(semantic.tenant).split("\\.");
        String unsigned = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes()) + "." + parts[1] + ".";
        assertThat(refusal(unsigned)).isEqualTo(Reason.IDENTITY_REJECTED);
        assertThat(refusal("not-a-jwt")).isEqualTo(Reason.IDENTITY_REJECTED);
    }

    @Test
    void anExpiredOrNotYetValidTokenIsRefused() {
        assertThat(refusal(withClaims(c -> c.put("exp", Instant.now().minus(Duration.ofMinutes(5))))))
                .isEqualTo(Reason.IDENTITY_REJECTED);
        assertThat(refusal(withClaims(c -> c.put("nbf", Instant.now().plus(Duration.ofMinutes(5))))))
                .isEqualTo(Reason.IDENTITY_REJECTED);
        assertThat(refusal(withClaims(c -> c.remove("exp")))).isEqualTo(Reason.IDENTITY_REJECTED);
    }

    @Test
    void anotherIssuerOrAudienceIsRefused() {
        assertThat(refusal(withClaims(c -> c.put("iss", "http://evil.example/realms/nexora"))))
                .isEqualTo(Reason.IDENTITY_REJECTED);
        assertThat(refusal(withClaims(c -> c.put("aud", List.of("nexora-reports")))))
                .isEqualTo(Reason.IDENTITY_REJECTED);
        assertThat(refusal(withClaims(c -> c.remove("aud")))).isEqualTo(Reason.IDENTITY_REJECTED);
    }

    @Test
    void onlyAServicePrincipalWithTheRequiredRoleIsAccepted() {
        assertThat(refusal(withClaims(c -> c.put("principal_type", "AI")))).isEqualTo(Reason.IDENTITY_REJECTED);
        assertThat(refusal(withClaims(c -> c.put("principal_type", "HUMAN")))).isEqualTo(Reason.IDENTITY_REJECTED);
        assertThat(refusal(withClaims(c -> c.remove("principal_type")))).isEqualTo(Reason.IDENTITY_REJECTED);
        assertThat(refusal(withClaims(c -> c.put("roles", List.of("AI_AGENT"))))).isEqualTo(Reason.IDENTITY_REJECTED);
        assertThat(refusal(withClaims(c -> c.remove("roles")))).isEqualTo(Reason.IDENTITY_REJECTED);
        tokenEndpointAnswers(withClaims(c -> c.put("roles", "REPORTS_CLIENT")));
        assertThat(semantic.tokenProvider.tokenFor(tenant)).isNotBlank();
    }

    @Test
    void aTokenOfAnotherTenantIsRefused() {
        assertThat(refusal(withClaims(c -> c.put("tenant", UUID.randomUUID().toString()))))
                .isEqualTo(Reason.IDENTITY_REJECTED);
        assertThat(refusal(withClaims(c -> c.remove("tenant")))).isEqualTo(Reason.IDENTITY_REJECTED);
    }

    @Test
    void anotherTenantNeverGetsATokenOfThisIdentity() {
        try {
            semantic.tokenProvider.tokenFor(new TenantId(UUID.randomUUID()));
            throw new AssertionError("accepted");
        } catch (SemanticUnavailableException e) {
            assertThat(e.reason()).isEqualTo(Reason.IDENTITY_REJECTED);
        }
        assertThat(semantic.server.received()).isEmpty();
    }

    @Test
    void nestedRoleClaimsAreSupported() {
        assertThat(InterpretationTokenProvider.claim(Map.of("realm_access", Map.of("roles", List.of("A"))),
                "realm_access.roles")).isEqualTo(List.of("A"));
        assertThat(InterpretationTokenProvider.claim(Map.of("roles", "A"), "realm_access.roles")).isNull();
    }

    @Test
    void tokenEndpointFailuresAreControlled() {
        semantic.server.answer(FakeSemanticServer.TOKEN_PATH, 401, "{\"error\":\"invalid_client\"}");
        assertThat(reason()).isEqualTo(Reason.IDENTITY_REJECTED);
        semantic.server.answer(FakeSemanticServer.TOKEN_PATH, 503, "");
        assertThat(reason()).isEqualTo(Reason.UNREACHABLE);
        semantic.server.answer(FakeSemanticServer.TOKEN_PATH, 200, "{\"token_type\":\"Bearer\"}");
        assertThat(reason()).isEqualTo(Reason.IDENTITY_REJECTED);
        semantic.server.answer(FakeSemanticServer.TOKEN_PATH, 200, "<html>");
        assertThat(reason()).isEqualTo(Reason.IDENTITY_REJECTED);
    }

    @Test
    void unavailableIssuerKeysAreAnAvailabilityProblem() {
        semantic.server.answer(FakeSemanticServer.JWKS_PATH, 503, "");
        assertThat(reason()).isEqualTo(Reason.UNREACHABLE);
    }

    @Test
    void secretsNeverAppearInConfigurationTextOrFailures() {
        assertThat(semantic.properties.toString()).doesNotContain(SemanticFixture.SECRET).contains("clientSecret=***");
        semantic.server.answer(FakeSemanticServer.TOKEN_PATH, 400, "{\"error\":\"" + SemanticFixture.SECRET + "\"}");
        try {
            semantic.tokenProvider.tokenFor(tenant);
        } catch (SemanticUnavailableException e) {
            assertThat(e.getMessage()).doesNotContain(SemanticFixture.SECRET);
        }
        String token = withClaims(c -> c.put("principal_type", "AI"));
        tokenEndpointAnswers(token);
        try {
            semantic.tokenProvider.tokenFor(tenant);
        } catch (SemanticUnavailableException e) {
            assertThat(e.getMessage()).doesNotContain(token);
        }
    }

    private Reason reason() {
        try {
            semantic.tokenProvider.tokenFor(tenant);
        } catch (SemanticUnavailableException e) {
            return e.reason();
        }
        throw new AssertionError("no failure");
    }
}
