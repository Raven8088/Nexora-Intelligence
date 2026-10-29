package com.nexora.intelligence.infrastructure.serviceclient;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collection;

/**
 * Standard Spring Security verification of service tokens: signature against the issuer's JWKS with only the
 * identity's algorithms, expiry and not-before with its skew, issuer and audience. The JWKS is fetched lazily, without
 * following redirects and with the given timeouts.
 */
public final class ServiceJwtDecoders {

    private ServiceJwtDecoders() {
    }

    public static JwtDecoder forIdentity(ServiceIdentity identity, Duration connectTimeout, Duration readTimeout) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(readTimeout);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(identity.jwkSetUri().toString())
                .jwsAlgorithms(algorithms -> {
                    algorithms.clear();
                    for (SignatureAlgorithm algorithm : identity.signatureAlgorithms()) {
                        algorithms.add(algorithm);
                    }
                })
                .restOperations(new RestTemplate(factory))
                .build();
        String audience = identity.audience();
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(audience));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(identity.clockSkew()),
                new JwtIssuerValidator(identity.issuer()),
                audienceValidator));
        return decoder;
    }
}
