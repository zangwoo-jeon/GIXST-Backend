package com.AISA.AISA.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "google.ai.studio")
@Getter
@Setter
public class GeminiProperties {
    /**
     * Single API key for backward compatibility.
     */
    private String apiKey;

    /**
     * List of API keys for rotation/batch processing.
     */
    private List<String> apiKeys;
}
