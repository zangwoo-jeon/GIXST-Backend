package com.AISA.AISA.fred.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "fred")
@Getter
@Setter
public class FredApiProperties {
    private String baseUrl;
    private String apiKey;
}
