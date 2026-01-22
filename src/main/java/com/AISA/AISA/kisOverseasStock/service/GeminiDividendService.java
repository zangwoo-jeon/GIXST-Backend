package com.AISA.AISA.kisOverseasStock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiDividendService {

    private final ObjectMapper objectMapper;

    @Value("${google.ai.studio.api-key}")
    private String geminiApiKey;

    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent";

    public String fetchBatchDividendData(String prompt) {
        WebClient webClient = WebClient.create();

        Map<String, Object> requestPart = new HashMap<>();
        requestPart.put("parts", Collections.singletonList(Map.of("text", prompt)));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("contents", Collections.singletonList(requestPart));
        requestBody.put("tools", Collections.singletonList(
                Map.of("google_search", new HashMap<>())));

        try {
            String response = webClient.post()
                    .uri(GEMINI_API_URL + "?key={key}", geminiApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return parseGeminiResponse(response);

        } catch (Exception e) {
            log.error("Failed to fetch batch dividend data from Gemini", e);
            return null;
        }
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
