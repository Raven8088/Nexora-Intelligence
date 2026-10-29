package com.nexora.intelligence.infrastructure.semantic;

import com.nexora.intelligence.infrastructure.serviceclient.ServiceIdentity;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Connection of Intelligence to Nexora Semantic and the service identity used to interpret questions. Everything is
 * operator configuration, never taken from a question or a model answer; the client secret only comes from the
 * environment ({@code NEXORA_INTELLIGENCE_SEMANTIC_CLIENT_SECRET}) and is never logged.
 *
 * @param baseUrl          Semantic base URL, e.g. {@code http://localhost:8080}; {@code /api/v1/...} is appended
 * @param connectTimeout   time to establish each connection (Semantic, token endpoint, JWKS)
 * @param readTimeout      time to wait for each complete answer
 * @param maxResponseBytes upper bound of any response body
 * @param maxRetries       0 or 1 retries, only where it is safe: reads, and plan creations that were never sent
 * @param identity         the interpretation identity (OAuth2 client credentials, verified before use)
 */
@ConfigurationProperties("nexora.intelligence.semantic")
public record SemanticProperties(
        URI baseUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @DefaultValue("4194304") int maxResponseBytes,
        @DefaultValue("0") int maxRetries,
        Identity identity) {

    public static final int MAX_RETRIES = 1;
    private static final Duration MAX_READ_TIMEOUT = Duration.ofMinutes(2);
    private static final int MIN_RESPONSE_BYTES = 64 * 1024;
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private static final String PREFIX = "nexora.intelligence.semantic.";

    /**
     * The identity Intelligence uses to read the catalog and create plans: a SERVICE principal with the
     * {@code REPORTS_CLIENT} role of one tenant. It is separate from any future AI identity for catalog enrichment,
     * which must have its own configuration prefix and credentials.
     *
     * @param tenantId           the tenant the credentials belong to; tokens must carry exactly this tenant
     * @param tokenUri           OAuth2 token endpoint (client credentials grant)
     * @param jwkSetUri          JWKS endpoint of the issuer, to verify token signatures
     * @param issuer             expected {@code iss} claim
     * @param audience           audience the token must carry
     * @param clientId           OAuth2 client id
     * @param clientSecret       OAuth2 client secret, from the environment only
     * @param algorithms         accepted JWS algorithms
     * @param tenantClaim        claim with the tenant UUID (Semantic's default)
     * @param principalTypeClaim claim with the principal type (must be SERVICE)
     * @param rolesClaim         claim with the roles; a dotted path reaches nested claims
     * @param requiredRole       role the token must carry
     * @param clockSkew          tolerance for {@code exp} and {@code nbf}
     */
    public record Identity(
            UUID tenantId,
            URI tokenUri,
            URI jwkSetUri,
            String issuer,
            @DefaultValue("nexora-semantic") String audience,
            String clientId,
            String clientSecret,
            @DefaultValue("RS256") List<String> algorithms,
            @DefaultValue("tenant") String tenantClaim,
            @DefaultValue("principal_type") String principalTypeClaim,
            @DefaultValue("roles") String rolesClaim,
            @DefaultValue("REPORTS_CLIENT") String requiredRole,
            @DefaultValue("30s") Duration clockSkew) {

        public Identity {
            if (tenantId == null) {
                throw new IllegalArgumentException(PREFIX + "identity.tenant-id is required");
            }
            tokenUri = endpoint(tokenUri, "identity.token-uri");
            jwkSetUri = endpoint(jwkSetUri, "identity.jwk-set-uri");
            issuer = required(issuer, "identity.issuer");
            audience = required(audience, "identity.audience");
            clientId = required(clientId, "identity.client-id");
            if (clientSecret == null || clientSecret.isBlank()) {
                throw new IllegalArgumentException(PREFIX + "identity.client-secret is required "
                        + "(set NEXORA_INTELLIGENCE_SEMANTIC_CLIENT_SECRET)");
            }
            if (algorithms == null || algorithms.isEmpty()) {
                throw new IllegalArgumentException(PREFIX + "identity.algorithms must not be empty");
            }
            algorithms = algorithms.stream().map(String::strip).toList();
            for (String algorithm : algorithms) {
                if (SignatureAlgorithm.from(algorithm) == null) {
                    throw new IllegalArgumentException(PREFIX + "identity.algorithms has an unsupported algorithm");
                }
            }
            tenantClaim = required(tenantClaim, "identity.tenant-claim");
            principalTypeClaim = required(principalTypeClaim, "identity.principal-type-claim");
            rolesClaim = required(rolesClaim, "identity.roles-claim");
            requiredRole = required(requiredRole, "identity.required-role");
            if (clockSkew == null || clockSkew.isNegative() || clockSkew.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalArgumentException(PREFIX + "identity.clock-skew must be between 0 and 5 minutes");
            }
        }

        public List<SignatureAlgorithm> signatureAlgorithms() {
            return algorithms.stream().map(SignatureAlgorithm::from).toList();
        }

        /** The same identity in the form shared by every service client. */
        public ServiceIdentity serviceIdentity() {
            return new ServiceIdentity(tenantId, tokenUri, jwkSetUri, issuer, audience, clientId, clientSecret,
                    algorithms, tenantClaim, principalTypeClaim, rolesClaim, requiredRole, clockSkew);
        }

        @Override
        public String toString() {
            return "Identity[tenantId=" + tenantId + ", tokenUri=" + tokenUri + ", jwkSetUri=" + jwkSetUri
                    + ", issuer=" + issuer + ", audience=" + audience + ", clientId=" + clientId
                    + ", clientSecret=***, algorithms=" + algorithms + ", requiredRole=" + requiredRole + "]";
        }
    }

    public SemanticProperties {
        baseUrl = endpoint(baseUrl, "base-url");
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
        if (maxRetries < 0 || maxRetries > MAX_RETRIES) {
            throw new IllegalArgumentException(PREFIX + "max-retries must be 0 or 1");
        }
        if (identity == null) {
            throw new IllegalArgumentException(PREFIX + "identity is required");
        }
    }

    private static URI endpoint(URI uri, String name) {
        if (uri == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(PREFIX + name
                    + " must be an http(s) URL without user info, query or fragment");
        }
        String text = uri.toString();
        return URI.create(text.endsWith("/") ? text.substring(0, text.length() - 1) : text);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 500) {
            throw new IllegalArgumentException(PREFIX + name + " is required (at most 500 characters)");
        }
        return value.strip();
    }

    @Override
    public String toString() {
        return "SemanticProperties[baseUrl=" + baseUrl + ", connectTimeout=" + connectTimeout + ", readTimeout="
                + readTimeout + ", maxResponseBytes=" + maxResponseBytes + ", maxRetries=" + maxRetries
                + ", identity=" + identity + "]";
    }
}
