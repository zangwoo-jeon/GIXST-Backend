package com.AISA.AISA.kisOverseasStock.service;

import com.AISA.AISA.global.config.GeminiProperties;
import com.AISA.AISA.kisOverseasStock.exception.GeminiQuotaExhaustedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiDividendService {

    private final ObjectMapper objectMapper;

    private final GeminiProperties geminiProperties;
    private final java.util.concurrent.atomic.AtomicInteger keyIndex = new java.util.concurrent.atomic.AtomicInteger(0);

    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent";

    public String fetchBatchDividendData(String prompt) {
        List<String> keys = geminiProperties.getApiKeys();
        if (keys == null || keys.isEmpty()) {
            log.error("No Gemini API keys configured");
            return null;
        }

        int maxRetries = keys.size();
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            String currentKey = getNextKey(keys);
            try {
                String response = callGeminiApi(prompt, currentKey);
                return parseGeminiResponse(response);
            } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
                if (e.getStatusCode().value() == 429) {
                    log.warn("Rate limit (429) exceeded for key index {}. Rotating key...",
                            keyIndex.get() % keys.size());
                    continue; // Switch key and retry
                }
                if (e.getStatusCode().is5xxServerError()) {
                    log.warn("Gemini Server Error ({}) for key index {}. Rotating key...",
                            e.getStatusCode().value(), keyIndex.get() % keys.size());
                    continue; // Switch key and retry
                }
                log.error("WebClient error: {}", e.getMessage());
                break;
            } catch (Exception e) {
                log.error("Failed to fetch batch dividend data from Gemini", e);
                break;
            }
        }
        throw new GeminiQuotaExhaustedException("Gemini API is unavailable or all quotas exhausted.");
    }

    private String getNextKey(List<String> keys) {
        int index = keyIndex.getAndIncrement() % keys.size();
        return keys.get(Math.abs(index));
    }

    private String callGeminiApi(String prompt, String apiKey) {
        WebClient webClient = WebClient.create();

        Map<String, Object> requestPart = new HashMap<>();
        requestPart.put("parts", Collections.singletonList(Map.of("text", prompt)));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("contents", Collections.singletonList(requestPart));
        requestBody.put("tools", Collections.singletonList(
                Map.of("google_search", new HashMap<>())));

        return webClient.post()
                .uri(GEMINI_API_URL + "?key={key}", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    private String parseGeminiResponse(String jsonResponse) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);

            JsonNode candidates = root.path("candidates");
            if (candidates.isMissingNode() || candidates.isEmpty())
                return null;

            JsonNode content = candidates.get(0).path("content");
            JsonNode parts = content.path("parts");

            if (parts.isMissingNode() || parts.isEmpty())
                return null;

            return parts.get(0).path("text").asText();

        } catch (Exception e) {
            log.error("Error parsing Gemini response", e);
            return null;
        }
    }
}
