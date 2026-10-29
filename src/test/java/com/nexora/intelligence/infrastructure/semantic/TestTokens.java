package com.nexora.intelligence.infrastructure.semantic;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Signs test JWTs like the internal Keycloak would, and publishes the matching JWKS. */
public final class TestTokens {

    public static final String ISSUER = "http://127.0.0.1/realms/nexora";
    public static final String AUDIENCE = "nexora-semantic";

    private final RSAKey key;
    private final RSAKey otherKey;

    public TestTokens() {
        try {
            key = new RSAKeyGenerator(2048).keyID("test-key").keyUse(KeyUse.SIGNATURE).generate();
            otherKey = new RSAKeyGenerator(2048).keyID("test-key").keyUse(KeyUse.SIGNATURE).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public String jwks() {
        return new JWKSet(key.toPublicJWK()).toString();
    }

    /** Claims of a valid interpretation token for {@code tenant}; {@code changes} can alter or remove any claim. */
    public String token(UUID tenant, Consumer<Map<String, Object>> changes) {
        return sign(key, JWSAlgorithm.RS256, tenant, changes);
    }

    public String token(UUID tenant) {
        return token(tenant, claims -> {
        });
    }

    /** A token signed with a key the JWKS does not publish (same key id). */
    public String forgedToken(UUID tenant) {
        return sign(otherKey, JWSAlgorithm.RS256, tenant, claims -> {
        });
    }

    /** A valid signature with an algorithm the identity does not allow by default. */
    public String tokenWithAlgorithm(JWSAlgorithm algorithm, UUID tenant) {
        return sign(key, algorithm, tenant, claims -> {
        });
    }

    public String tokenResponse(String token) {
        return "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":300}";
    }

    private static String sign(RSAKey signingKey, JWSAlgorithm algorithm, UUID tenant,
                               Consumer<Map<String, Object>> changes) {
        Instant now = Instant.now();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("sub", "service-account-intelligence");
        claims.put("aud", List.of(AUDIENCE));
        claims.put("iat", now.minusSeconds(5));
        claims.put("exp", now.plus(Duration.ofMinutes(5)));
        claims.put("principal_type", "SERVICE");
        claims.put("roles", List.of("REPORTS_CLIENT"));
        claims.put("tenant", tenant.toString());
        changes.accept(claims);
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
        claims.forEach((name, value) -> builder.claim(name, value instanceof Instant instant
                ? Date.from(instant) : value));
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(algorithm).keyID(signingKey.getKeyID()).build(),
                    builder.build());
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
