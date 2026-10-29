package com.nexora.intelligence.infrastructure.serviceclient;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * A service identity of Intelligence towards one Nexora service: OAuth2 client credentials of one tenant, and what a
 * token must carry to be sent to that service. Validated on construction; the secret never appears in
 * {@link #toString()}.
 *
 * @param tenantId           the tenant the credentials belong to; tokens must carry exactly this tenant
 * @param tokenUri           OAuth2 token endpoint (client credentials grant)
 * @param jwkSetUri          JWKS endpoint of the issuer, to verify token signatures
 * @param issuer             expected {@code iss} claim
 * @param audience           audience the token must carry (the target service)
 * @param clientId           OAuth2 client id
 * @param clientSecret       OAuth2 client secret, from the environment only
 * @param algorithms         accepted JWS algorithms
 * @param tenantClaim        claim with the tenant UUID
 * @param principalTypeClaim claim with the principal type (must be SERVICE)
 * @param rolesClaim         claim with the roles; a dotted path reaches nested claims
 * @param requiredRole       role the token must carry for the target service
 * @param clockSkew          tolerance for {@code exp} and {@code nbf}
 */
public record ServiceIdentity(
        UUID tenantId,
        URI tokenUri,
        URI jwkSetUri,
        String issuer,
        String audience,
        String clientId,
        String clientSecret,
        List<String> algorithms,
        String tenantClaim,
        String principalTypeClaim,
        String rolesClaim,
        String requiredRole,
        Duration clockSkew) {

    /** Validates every field; messages name the configuration property under {@code prefix}. */
    public static ServiceIdentity of(String prefix, UUID tenantId, URI tokenUri, URI jwkSetUri, String issuer,
                                     String audience, String clientId, String clientSecret, List<String> algorithms,
                                     String tenantClaim, String principalTypeClaim, String rolesClaim,
                                     String requiredRole, Duration clockSkew) {
        if (tenantId == null) {
            throw new IllegalArgumentException(prefix + "tenant-id is required");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalArgumentException(prefix + "client-secret is required (set it from the environment)");
        }
        if (algorithms == null || algorithms.isEmpty()) {
            throw new IllegalArgumentException(prefix + "algorithms must not be empty");
        }
        List<String> names = algorithms.stream().map(String::strip).toList();
        for (String algorithm : names) {
            if (SignatureAlgorithm.from(algorithm) == null) {
                throw new IllegalArgumentException(prefix + "algorithms has an unsupported algorithm");
            }
        }
        if (clockSkew == null || clockSkew.isNegative() || clockSkew.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException(prefix + "clock-skew must be between 0 and 5 minutes");
        }
        return new ServiceIdentity(tenantId, endpoint(tokenUri, prefix + "token-uri"),
                endpoint(jwkSetUri, prefix + "jwk-set-uri"), required(issuer, prefix + "issuer"),
                required(audience, prefix + "audience"), required(clientId, prefix + "client-id"), clientSecret,
                names, required(tenantClaim, prefix + "tenant-claim"),
                required(principalTypeClaim, prefix + "principal-type-claim"),
                required(rolesClaim, prefix + "roles-claim"), required(requiredRole, prefix + "required-role"),
                clockSkew);
    }

    public ServiceIdentity {
        algorithms = List.copyOf(algorithms);
    }

    public List<SignatureAlgorithm> signatureAlgorithms() {
        return algorithms.stream().map(SignatureAlgorithm::from).toList();
    }

    /** An http(s) URL without user info, query or fragment, without trailing slash. */
    public static URI endpoint(URI uri, String name) {
        if (uri == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(name + " must be an http(s) URL without user info, query or fragment");
        }
        String text = uri.toString();
        return URI.create(text.endsWith("/") ? text.substring(0, text.length() - 1) : text);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 500) {
            throw new IllegalArgumentException(name + " is required (at most 500 characters)");
        }
        return value.strip();
    }

    @Override
    public String toString() {
        return "ServiceIdentity[tenantId=" + tenantId + ", tokenUri=" + tokenUri + ", jwkSetUri=" + jwkSetUri
                + ", issuer=" + issuer + ", audience=" + audience + ", clientId=" + clientId
                + ", clientSecret=***, algorithms=" + algorithms + ", requiredRole=" + requiredRole + "]";
    }
}
