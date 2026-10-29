package com.nexora.intelligence.infrastructure.serviceclient;

import com.nexora.intelligence.infrastructure.serviceclient.ServiceIdentityException.Failure;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Access token of one {@link ServiceIdentity} (OAuth2 client credentials). Every token is verified before it is sent
 * anywhere: signature with an allowed algorithm against the issuer's JWKS, expiry and not-before, issuer and audience
 * (by the {@link JwtDecoder}), then principal type SERVICE, the required role and a tenant claim equal to the tenant
 * Intelligence is acting for. A token that fails any check is discarded, never sent.
 * <p>
 * A verified token is cached until shortly before it expires; {@link #evict()} forgets it after the target service
 * rejected it. Tokens and secrets never appear in messages or logs. One provider serves one identity and one target
 * audience; identities for other purposes (such as a future AI identity) must use their own provider.
 */
public class ServiceTokenProvider {

    public static final String SERVICE = "SERVICE";
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(30);

    private record CachedToken(String value, Instant refreshAt) {
    }

    private final ServiceIdentity identity;
    private final BoundedHttp http;
    private final JwtDecoder decoder;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private volatile CachedToken cached;

    public ServiceTokenProvider(ServiceIdentity identity, BoundedHttp http, JwtDecoder decoder, Clock clock) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.http = Objects.requireNonNull(http, "http");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** A verified token of {@code tenant}; fails if the identity is not of that tenant. */
    public synchronized String tokenFor(UUID tenant) {
        Objects.requireNonNull(tenant, "tenant");
        if (!identity.tenantId().equals(tenant)) {
            throw rejected("the service identity does not belong to the requested tenant");
        }
        CachedToken current = cached;
        if (current != null && clock.instant().isBefore(current.refreshAt())) {
            return current.value();
        }
        cached = null;
        String token = request();
        Jwt jwt = verify(token, tenant);
        cached = new CachedToken(token, jwt.getExpiresAt().minus(REFRESH_MARGIN));
        return token;
    }

    /** Forgets the cached token (after the target service answered 401 to it). */
    public synchronized void evict() {
        cached = null;
    }

    private String request() {
        String basic = "Basic " + Base64.getEncoder().encodeToString((url(identity.clientId()) + ":"
                + url(identity.clientSecret())).getBytes(StandardCharsets.UTF_8));
        BoundedHttp.Response response;
        try {
            response = http.postForm(identity.tokenUri(), basic, "grant_type=client_credentials");
        } catch (BoundedHttp.TransportException e) {
            throw new ServiceIdentityException(e.failure() == BoundedHttp.Failure.NO_ANSWER ? Failure.TIMEOUT
                    : Failure.UNREACHABLE, "token endpoint unreachable", e);
        }
        if (response.status() >= 500) {
            throw new ServiceIdentityException(Failure.UNREACHABLE,
                    "token endpoint answered HTTP " + response.status());
        }
        if (response.status() != 200) {
            throw rejected("token endpoint answered HTTP " + response.status());
        }
        try {
            JsonNode node = json.readTree(response.body());
            JsonNode token = node == null ? null : node.get("access_token");
            if (token == null || !token.isString() || token.asString().isBlank()) {
                throw rejected("no access token in the token response");
            }
            return token.asString();
        } catch (JacksonException e) {
            throw rejected("unreadable token response");
        }
    }

    private Jwt verify(String token, UUID tenant) {
        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (JwtException e) {
            if (causedByIo(e)) {
                throw new ServiceIdentityException(Failure.UNREACHABLE, "issuer keys unavailable", e);
            }
            throw rejected("service token failed verification");
        }
        if (jwt.getExpiresAt() == null) {
            throw rejected("service token without expiry");
        }
        if (!SERVICE.equals(claim(jwt.getClaims(), identity.principalTypeClaim()))) {
            throw rejected("service token principal type is not SERVICE");
        }
        Object roles = claim(jwt.getClaims(), identity.rolesClaim());
        boolean hasRole = roles instanceof Collection<?> list
                ? list.stream().anyMatch(identity.requiredRole()::equals)
                : identity.requiredRole().equals(roles);
        if (!hasRole) {
            throw rejected("service token without the required role");
        }
        if (!tenant.toString().equals(claim(jwt.getClaims(), identity.tenantClaim()))) {
            throw rejected("service token tenant mismatch");
        }
        return jwt;
    }

    /** Value of a claim; a dotted path reaches nested claims, as in Semantic and Reports. */
    public static Object claim(Map<String, Object> claims, String path) {
        Object current = claims;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    private static boolean causedByIo(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException || cause.getClass().getName().endsWith("RemoteKeySourceException")
                    || cause instanceof org.springframework.web.client.ResourceAccessException) {
                return true;
            }
        }
        return false;
    }

    private static ServiceIdentityException rejected(String message) {
        return new ServiceIdentityException(Failure.REJECTED, message);
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
