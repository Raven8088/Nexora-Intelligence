package com.nexora.intelligence.infrastructure.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Configuration of the local question API: its properties and the loopback-only filter in front of it. Registered
 * only with {@code nexora.intelligence.api.enabled=true}; without it no business endpoint exists, as before.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "nexora.intelligence.api", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(QuestionApiProperties.class)
class QuestionApiConfiguration {

    @Bean
    FilterRegistrationBean<LoopbackOnlyFilter> loopbackOnlyFilter() {
        FilterRegistrationBean<LoopbackOnlyFilter> registration = new FilterRegistrationBean<>(new LoopbackOnlyFilter());
        registration.addUrlPatterns(QuestionController.PATH, QuestionController.PATH + "/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
