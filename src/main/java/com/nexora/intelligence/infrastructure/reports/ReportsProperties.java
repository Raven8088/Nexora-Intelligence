package com.nexora.intelligence.infrastructure.reports;

import com.nexora.intelligence.infrastructure.serviceclient.ServiceIdentity;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Connection of Intelligence to Nexora Reports and the service identity used to execute plans. Everything is operator
 * configuration, never taken from a question or a model answer; the client secret only comes from the environment
 * ({@code NEXORA_INTELLIGENCE_REPORTS_CLIENT_SECRET}) and is never logged.
 *
 * @param baseUrl          Reports base URL, e.g. {@code http://localhost:8090}
 * @param connectTimeout   time to establish each connection
 * @param readTimeout      time to wait for the complete answer; it should exceed Reports' own maximum duration (60 s
 *                         by default) so that Reports' 504 arrives before the client gives up
 * @param maxResponseBytes upper bound of a response body (results of up to 10 000 rows)
 * @param maxRows          rows requested per execution (1..10 000, Reports' default ceiling); always sent
 * @param maxRetries       0 or 1 retries, only of executions that were never sent
 * @param identity         the execution identity (OAuth2 client credentials, verified before use)
 */
@ConfigurationProperties("nexora.intelligence.reports")
public record ReportsProperties(
        URI baseUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("90s") Duration readTimeout,
        @DefaultValue("16777216") int maxResponseBytes,
        @DefaultValue("1000") int maxRows,
        @DefaultValue("0") int maxRetries,
        Identity identity) {

    public static final int MAX_ROWS = 10_000;
    private static final String PREFIX = "nexora.intelligence.reports.";
    private static final Duration MAX_READ_TIMEOUT = Duration.ofMinutes(5);
    private static final int MIN_RESPONSE_BYTES = 64 * 1024;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024 * 1024;

    /**
     * The identity Intelligence uses to execute plans: a SERVICE principal with the {@code REPORT_RUNNER} role of one
     * tenant and audience {@code nexora-reports}. It may be the same OAuth2 client as the interpretation identity
     * when that client has both audiences and roles; its tokens are requested and verified separately.
     */
    public record Identity(
            UUID tenantId,
            URI tokenUri,
            URI jwkSetUri,
            String issuer,
            @DefaultValue("nexora-reports") String audience,
            String clientId,
            String clientSecret,
            @DefaultValue("RS256") List<String> algorithms,
            @DefaultValue("tenant") String tenantClaim,
            @DefaultValue("principal_type") String principalTypeClaim,
            @DefaultValue("roles") String rolesClaim,
            @DefaultValue("REPORT_RUNNER") String requiredRole,
            @DefaultValue("30s") Duration clockSkew) {

        public Identity {
            ServiceIdentity validated = ServiceIdentity.of(PREFIX + "identity.", tenantId, tokenUri, jwkSetUri,
                    issuer, audience, clientId, clientSecret, algorithms, tenantClaim, principalTypeClaim, rolesClaim,
                    requiredRole, clockSkew);
            tokenUri = validated.tokenUri();
            jwkSetUri = validated.jwkSetUri();
            issuer = validated.issuer();
            audience = validated.audience();
            clientId = validated.clientId();
            algorithms = validated.algorithms();
            tenantClaim = validated.tenantClaim();
            principalTypeClaim = validated.principalTypeClaim();
            rolesClaim = validated.rolesClaim();
            requiredRole = validated.requiredRole();
        }

        public ServiceIdentity serviceIdentity() {
            return new ServiceIdentity(tenantId, tokenUri, jwkSetUri, issuer, audience, clientId, clientSecret,
                    algorithms, tenantClaim, principalTypeClaim, rolesClaim, requiredRole, clockSkew);
        }

        @Override
        public String toString() {
            return serviceIdentity().toString();
        }
    }

    public ReportsProperties {
        baseUrl = ServiceIdentity.endpoint(baseUrl, PREFIX + "base-url");
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException(PREFIX + "connect-timeout must be positive");
        }
        if (readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()
                || readTimeout.compareTo(MAX_READ_TIMEOUT) > 0) {
            throw new IllegalArgumentException(PREFIX + "read-timeout must be positive and at most "
                    + MAX_READ_TIMEOUT);
        }
        if (maxResponseBytes < MIN_RESPONSE_BYTES || maxResponseBytes > MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException(PREFIX + "max-response-bytes must be between " + MIN_RESPONSE_BYTES
                    + " and " + MAX_RESPONSE_BYTES);
        }
        if (maxRows < 1 || maxRows > MAX_ROWS) {
            throw new IllegalArgumentException(PREFIX + "max-rows must be between 1 and " + MAX_ROWS);
        }
        if (maxRetries < 0 || maxRetries > 1) {
            throw new IllegalArgumentException(PREFIX + "max-retries must be 0 or 1");
        }
        if (identity == null) {
            throw new IllegalArgumentException(PREFIX + "identity is required");
        }
    }

    @Override
    public String toString() {
        return "ReportsProperties[baseUrl=" + baseUrl + ", connectTimeout=" + connectTimeout + ", readTimeout="
                + readTimeout + ", maxResponseBytes=" + maxResponseBytes + ", maxRows=" + maxRows + ", maxRetries="
                + maxRetries + ", identity=" + identity + "]";
    }
}
