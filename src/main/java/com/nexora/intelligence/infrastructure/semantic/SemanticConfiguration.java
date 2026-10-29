package com.nexora.intelligence.infrastructure.semantic;

import com.nexora.intelligence.application.port.out.LogicalPlanClient;
import com.nexora.intelligence.application.port.out.SemanticCatalogClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.nexora.intelligence.infrastructure.serviceclient.ServiceJwtDecoders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/**
 * Registers the Semantic adapters only when Semantic is configured ({@code nexora.intelligence.semantic.base-url}).
 * Without it the application starts exactly as before. Nothing connects to Semantic, the token endpoint or the JWKS
 * endpoint at startup.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "nexora.intelligence.semantic", name = "base-url")
@EnableConfigurationProperties(SemanticProperties.class)
class SemanticConfiguration {

    @Bean
    SemanticApi semanticApi(SemanticProperties properties) {
        SemanticHttp http = new SemanticHttp(properties.connectTimeout(), properties.readTimeout(),
                properties.maxResponseBytes());
        InterpretationTokenProvider tokens = new InterpretationTokenProvider(properties.identity(), http,
                jwtDecoder(properties), Clock.systemUTC());
        return new SemanticApi(http, tokens, properties.baseUrl(), properties.maxRetries(), json());
    }

    @Bean
    SemanticCatalogClient semanticCatalogClient(SemanticApi api) {
        return new HttpSemanticCatalogClient(api, new CatalogResponseMapper(json()));
    }

    @Bean
    LogicalPlanClient logicalPlanClient(SemanticApi api) {
        JsonMapper json = json();
        return new HttpLogicalPlanClient(api, new PlanRequestMapper(json), json);
    }

    /** Spring Security verification of the interpretation tokens (shared with every service client). */
    static JwtDecoder jwtDecoder(SemanticProperties properties) {
        return ServiceJwtDecoders.forIdentity(properties.identity().serviceIdentity(), properties.connectTimeout(),
                properties.readTimeout());
    }

    static JsonMapper json() {
        return JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build();
    }
}
