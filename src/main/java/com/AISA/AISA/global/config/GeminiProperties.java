package com.AISA.AISA.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * Gemini API URL.
     */
    private String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent";

    private final AtomicInteger keyIndex = new AtomicInteger(0);

    public String getNextKey() {
        if (apiKeys != null && !apiKeys.isEmpty()) {
            int index = keyIndex.getAndIncrement() % apiKeys.size();
            return apiKeys.get(Math.abs(index));
        }
        return apiKey;
    }
}