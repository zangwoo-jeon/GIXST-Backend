package com.AISA.AISA.kisStock.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ecos")
@Getter
@Setter
public class EcosApiProperties {
    private String baseUrl;
    private String apiKey;
}
