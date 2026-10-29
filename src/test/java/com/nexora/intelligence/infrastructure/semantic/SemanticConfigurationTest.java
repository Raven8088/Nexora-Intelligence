package com.nexora.intelligence.infrastructure.semantic;

import com.nexora.intelligence.application.port.out.LogicalPlanClient;
import com.nexora.intelligence.application.port.out.SemanticCatalogClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The adapters are registered only when Semantic is configured, and invalid settings stop startup. */
class SemanticConfigurationTest {

    private static final String TENANT = UUID.randomUUID().toString();
    private static final String[] VALID = {
            "nexora.intelligence.semantic.base-url=http://localhost:8080/",
            "nexora.intelligence.semantic.identity.tenant-id=" + TENANT,
            "nexora.intelligence.semantic.identity.token-uri=http://localhost:8180/realms/nexora/protocol/openid-connect/token",
            "nexora.intelligence.semantic.identity.jwk-set-uri=http://localhost:8180/realms/nexora/protocol/openid-connect/certs",
            "nexora.intelligence.semantic.identity.issuer=http://localhost:8180/realms/nexora",
            "nexora.intelligence.semantic.identity.client-id=nexora-intelligence",
            "nexora.intelligence.semantic.identity.client-secret=from-the-environment"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SemanticConfiguration.class);

    @Test
    void noAdapterWithoutABaseUrl() {
        runner.run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(SemanticCatalogClient.class).doesNotHaveBean(LogicalPlanClient.class));
    }

    @Test
    void theAdaptersAreRegisteredWithSafeDefaultsAndNothingIsContactedAtStartup() {
        runner.withPropertyValues(VALID).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SemanticCatalogClient.class)
                    .hasSingleBean(LogicalPlanClient.class);
            assertThat(context.getBean(SemanticCatalogClient.class)).isInstanceOf(HttpSemanticCatalogClient.class);
            SemanticProperties properties = context.getBean(SemanticProperties.class);
            assertThat(properties.baseUrl()).isEqualTo(URI.create("http://localhost:8080"));
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.maxRetries()).isZero();
            assertThat(properties.maxResponseBytes()).isEqualTo(4 * 1024 * 1024);
            SemanticProperties.Identity identity = properties.identity();
            assertThat(identity.audience()).isEqualTo("nexora-semantic");
            assertThat(identity.algorithms()).containsExactly("RS256");
            assertThat(identity.requiredRole()).isEqualTo("REPORTS_CLIENT");
            assertThat(identity.tenantClaim()).isEqualTo("tenant");
            assertThat(identity.principalTypeClaim()).isEqualTo("principal_type");
            assertThat(identity.rolesClaim()).isEqualTo("roles");
            assertThat(identity.clockSkew()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.toString()).doesNotContain("from-the-environment");
        });
    }

    @Test
    void invalidSettingsFailStartup() {
        String[][] invalid = {
                {"base-url=ftp://localhost:8080"},
                {"base-url=http://user:pass@localhost:8080"},
                {"base-url=http://localhost:8080?x=1"},
                {"max-retries=2"},
                {"max-retries=-1"},
                {"read-timeout=0s"},
                {"read-timeout=3m"},
                {"connect-timeout=0s"},
                {"max-response-bytes=1000"},
                {"identity.tenant-id="},
                {"identity.client-secret="},
                {"identity.client-id="},
                {"identity.issuer="},
                {"identity.token-uri=file:///etc/passwd"},
                {"identity.jwk-set-uri=http://localhost:8180/certs#x"},
                {"identity.algorithms=none"},
                {"identity.algorithms=HS256X"},
                {"identity.clock-skew=10m"}};
        for (String[] settings : invalid) {
            List<String> values = new ArrayList<>(List.of(VALID));
            for (String setting : settings) {
                values.add("nexora.intelligence.semantic." + setting);
            }
            runner.withPropertyValues(values.toArray(String[]::new))
                    .run(context -> assertThat(context).as(String.join(",", settings)).hasFailed());
        }
    }
}
