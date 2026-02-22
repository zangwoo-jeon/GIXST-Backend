package com.AISA.AISA.kisStock.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {
    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        org.springframework.web.reactive.function.client.ExchangeStrategies exchangeStrategies = org.springframework.web.reactive.function.client.ExchangeStrategies
                .builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)) // 16MB
                .build();

        return builder.baseUrl("https://openapi.koreainvestment.com:9443")
                .exchangeStrategies(exchangeStrategies)
                .build();
    }
}
