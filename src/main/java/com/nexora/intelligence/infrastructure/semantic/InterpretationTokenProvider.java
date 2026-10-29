package com.nexora.intelligence.infrastructure.semantic;

import com.nexora.intelligence.application.port.out.SemanticUnavailableException;
import com.nexora.intelligence.application.port.out.SemanticUnavailableException.Reason;
import com.nexora.intelligence.domain.shared.TenantId;
import com.nexora.intelligence.infrastructure.serviceclient.ServiceIdentityException;
import com.nexora.intelligence.infrastructure.serviceclient.ServiceTokenProvider;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Clock;
import java.util.Map;

/**
 * Access token of the interpretation identity towards Semantic. The token is obtained and verified by the shared
 * {@link ServiceTokenProvider} (client credentials; JWKS signature, algorithm, expiry, issuer, audience, SERVICE
 * principal, required role and tenant); this class only maps its failures to {@link SemanticUnavailableException}.
 * It only serves the interpretation identity: a future AI identity for catalog enrichment must use its own provider.
 */
class InterpretationTokenProvider {

    static final String SERVICE = ServiceTokenProvider.SERVICE;

    private final ServiceTokenProvider tokens;

    InterpretationTokenProvider(SemanticProperties.Identity identity, SemanticHttp http, JwtDecoder decoder,
                                Clock clock) {
        this.tokens = new ServiceTokenProvider(identity.serviceIdentity(), http, decoder, clock);
    }

    /** A verified token of {@code tenant}; fails if the identity is not of that tenant. */
    String tokenFor(TenantId tenant) {
        try {
            return tokens.tokenFor(tenant.value());
        } catch (ServiceIdentityException e) {
            throw new SemanticUnavailableException(switch (e.failure()) {
                case REJECTED -> Reason.IDENTITY_REJECTED;
                case UNREACHABLE -> Reason.UNREACHABLE;
                case TIMEOUT -> Reason.TIMEOUT;
            }, e.getMessage(), e);
        }
    }

    /** Forgets the cached token (after Semantic answered 401 to it). */
    void evict() {
        tokens.evict();
    }

    /** Value of a claim; a dotted path reaches nested claims, as in Semantic. */
    static Object claim(Map<String, Object> claims, String path) {
        return ServiceTokenProvider.claim(claims, path);
    }
}
