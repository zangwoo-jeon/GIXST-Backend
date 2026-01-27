package com.AISA.AISA.analysis.service;

import com.AISA.AISA.global.config.GeminiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiService {

    private final ObjectMapper objectMapper;
    private final GeminiProperties geminiProperties;

    public String generateAdvice(String context) {
        List<String> keys = geminiProperties.getApiKeys();
        if (keys == null || keys.isEmpty()) {
            if (geminiProperties.getApiKey() == null) {
                return "Gemini API 키가 설정되지 않았습니다.";
            }
        }

        int maxRetries = (keys != null && !keys.isEmpty()) ? keys.size() : 1;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            String currentKey = geminiProperties.getNextKey();
            try {
                String response = callGeminiApi(context, currentKey);
                return parseGeminiResponse(response);

            } catch (WebClientResponseException e) {
                if (e.getStatusCode().value() == 429) {
                    log.warn("Rate limit (429) exceeded. Rotating key...");
                    continue; // Switch key and retry
                }
                if (e.getStatusCode().is5xxServerError()) {
                    log.warn("Gemini Server Error ({}). Rotating key...", e.getStatusCode().value());
                    continue; // Switch key and retry
                }
                log.error("WebClient error: {}", e.getMessage());
                return "AI 서비스 연결 오류: " + e.getMessage();
            } catch (Exception e) {
                log.error("Failed to generate advice from Gemini", e);
                return "AI 진단 서비스 연결 실패: " + e.getMessage();
            }
        }

        return "AI 서비스 사용량이 초과되었습니다. 잠시 후 다시 시도해주세요.";
    }

    private String callGeminiApi(String context, String apiKey) {
        WebClient webClient = WebClient.create();

        Map<String, Object> requestBody = Map.of(
                "contents", Collections.singletonList(
                        Map.of("parts", Collections.singletonList(
                                Map.of("text", context)))),
                "tools", Collections.singletonList(
                        Map.of("google_search", new HashMap<>())));

        return webClient.post()
                .uri(geminiProperties.getUrl() + "?key={key}", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    private String parseGeminiResponse(String jsonResponse) {
        try {
            // Use JsonNode for safe traversal without unchecked casts
            JsonNode root = objectMapper.readTree(jsonResponse);

            JsonNode candidates = root.path("candidates");
            if (candidates.isMissingNode() || candidates.isEmpty())
                return "AI 응답을 분석할 수 없습니다.";

            JsonNode content = candidates.get(0).path("content");
            JsonNode parts = content.path("parts");

            if (parts.isMissingNode() || parts.isEmpty())
                return "AI 응답 내용이 비어있습니다.";

            return parts.get(0).path("text").asText();

        } catch (Exception e) {
            log.error("Error parsing Gemini response", e);
            return "AI 응답 해석 중 오류가 발생했습니다.";
        }
    }
}
