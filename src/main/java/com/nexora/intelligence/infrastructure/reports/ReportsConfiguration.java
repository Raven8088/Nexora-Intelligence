package com.nexora.intelligence.infrastructure.reports;

import com.nexora.intelligence.application.port.out.ReportExecutionClient;
import com.nexora.intelligence.infrastructure.serviceclient.BoundedHttp;
import com.nexora.intelligence.infrastructure.serviceclient.ServiceJwtDecoders;
import com.nexora.intelligence.infrastructure.serviceclient.ServiceTokenProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/**
 * Registers the Reports adapter only when Reports is configured ({@code nexora.intelligence.reports.base-url}).
 * Without it the application starts exactly as before. Nothing connects to Reports, the token endpoint or the JWKS
 * endpoint at startup.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "nexora.intelligence.reports", name = "base-url")
@EnableConfigurationProperties(ReportsProperties.class)
class ReportsConfiguration {

    @Bean
    ReportExecutionClient reportExecutionClient(ReportsProperties properties) {
        return client(properties, Clock.systemUTC());
    }

    static HttpReportExecutionClient client(ReportsProperties properties, Clock clock) {
        BoundedHttp http = new BoundedHttp(properties.connectTimeout(), properties.readTimeout(),
                properties.maxResponseBytes());
        ServiceTokenProvider tokens = new ServiceTokenProvider(properties.identity().serviceIdentity(), http,
                ServiceJwtDecoders.forIdentity(properties.identity().serviceIdentity(), properties.connectTimeout(),
                        properties.readTimeout()), clock);
        return new HttpReportExecutionClient(http, tokens, properties.baseUrl(), properties.maxRows(),
                properties.maxRetries(), json());
    }

    static JsonMapper json() {
        return JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
    }
}
