package com.nexora.intelligence.infrastructure.reports;

import com.nexora.intelligence.application.port.out.ReportExecutionClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The Reports adapter is registered only when Reports is configured, and invalid settings stop startup. */
class ReportsConfigurationTest {

    private static final String[] VALID = {
            "nexora.intelligence.reports.base-url=http://localhost:8090/",
            "nexora.intelligence.reports.identity.tenant-id=" + UUID.randomUUID(),
            "nexora.intelligence.reports.identity.token-uri=http://localhost:8180/realms/nexora/protocol/openid-connect/token",
            "nexora.intelligence.reports.identity.jwk-set-uri=http://localhost:8180/realms/nexora/protocol/openid-connect/certs",
            "nexora.intelligence.reports.identity.issuer=http://localhost:8180/realms/nexora",
            "nexora.intelligence.reports.identity.client-id=nexora-intelligence",
            "nexora.intelligence.reports.identity.client-secret=from-the-environment"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ReportsConfiguration.class);

    @Test
    void noAdapterWithoutABaseUrl() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ReportExecutionClient.class));
    }

    @Test
    void theAdapterIsRegisteredWithSafeDefaultsAndNothingIsContactedAtStartup() {
        runner.withPropertyValues(VALID).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ReportExecutionClient.class);
            ReportsProperties properties = context.getBean(ReportsProperties.class);
            assertThat(properties.baseUrl()).isEqualTo(URI.create("http://localhost:8090"));
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(90));
            assertThat(properties.maxRows()).isEqualTo(1000);
            assertThat(properties.maxRetries()).isZero();
            assertThat(properties.maxResponseBytes()).isEqualTo(16 * 1024 * 1024);
            ReportsProperties.Identity identity = properties.identity();
            assertThat(identity.audience()).isEqualTo("nexora-reports");
            assertThat(identity.requiredRole()).isEqualTo("REPORT_RUNNER");
            assertThat(identity.algorithms()).containsExactly("RS256");
            assertThat(identity.tokenUri()).hasToString(
                    "http://localhost:8180/realms/nexora/protocol/openid-connect/token");
            assertThat(properties.toString()).doesNotContain("from-the-environment");
        });
    }

    @Test
    void invalidSettingsFailStartup() {
        String[][] invalid = {
                {"base-url=ftp://localhost:8090"},
                {"base-url=http://user:pass@localhost:8090"},
                {"base-url=http://localhost:8090#x"},
                {"max-rows=0"},
                {"max-rows=10001"},
                {"max-retries=2"},
                {"read-timeout=0s"},
                {"read-timeout=6m"},
                {"connect-timeout=0s"},
                {"max-response-bytes=1000"},
                {"identity.tenant-id="},
                {"identity.client-secret="},
                {"identity.client-id="},
                {"identity.issuer="},
                {"identity.audience="},
                {"identity.required-role="},
                {"identity.token-uri=file:///etc/passwd"},
                {"identity.jwk-set-uri=http://localhost/certs?x=1"},
                {"identity.algorithms=none"},
                {"identity.clock-skew=10m"}};
        for (String[] settings : invalid) {
            List<String> values = new ArrayList<>(List.of(VALID));
            for (String setting : settings) {
                values.add("nexora.intelligence.reports." + setting);
            }
            runner.withPropertyValues(values.toArray(String[]::new))
                    .run(context -> assertThat(context).as(String.join(",", settings)).hasFailed());
        }
    }
}
