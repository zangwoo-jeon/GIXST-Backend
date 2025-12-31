package com.AISA.AISA.analysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiService {

    private final ObjectMapper objectMapper;

    @Value("${google.ai.studio.api-key}")
    private String geminiApiKey;

    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent";

    public String generateAdvice(String context) {
        WebClient webClient = WebClient.create();

        Map<String, Object> requestBody = Map.of(
                "contents", Collections.singletonList(
                        Map.of("parts", Collections.singletonList(
                                Map.of("text", context)))),
                "tools", Collections.singletonList(
                        Map.of("google_search", new HashMap<>())));

        try {
            String response = webClient.post()
                    .uri(GEMINI_API_URL + "?key={key}", geminiApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(); // Blocking for simplicity in this synchronous service flow, can be made async
                              // if needed

            return parseGeminiResponse(response);

        } catch (Exception e) {
            log.error("Failed to generate advice from Gemini", e);
            return "AI 진단 서비스 연결 실패: " + e.getMessage();
        }
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
