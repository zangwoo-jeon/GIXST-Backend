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

    /**
     * Generates a context-aware market strategy based on the valuation data.
     * Returns null if generation fails (to allow fallback to static strategy).
     */
    public String generateMarketStrategy(com.AISA.AISA.analysis.dto.MarketValuationDto dto) {
        try {
            String prompt = buildStrategyPrompt(dto);
            return generateResponseWithRetry(prompt);
        } catch (Exception e) {
            log.warn("Failed to generate AI strategy: {}", e.getMessage());
            return null; // Fallback to static
        }
    }

    public String generateAdvice(String context) {
        try {
            return generateResponseWithRetry(context);
        } catch (Exception e) {
            log.error("Failed to generate advice", e);
            return "AI 서비스 연결 오류: " + e.getMessage();
        }
    }

    private String generateResponseWithRetry(String context) throws Exception {
        List<String> keys = geminiProperties.getApiKeys();
        if (keys == null || keys.isEmpty()) {
            if (geminiProperties.getApiKey() == null) {
                throw new IllegalStateException("Gemini API 키가 설정되지 않았습니다.");
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
                    continue;
                }
                if (e.getStatusCode().is5xxServerError()) {
                    log.warn("Gemini Server Error ({}). Rotating key...", e.getStatusCode().value());
                    continue;
                }
                throw e;
            }
        }
        throw new RuntimeException("AI 서비스 사용량이 초과되었습니다.");
    }

    private String callGeminiApi(String context, String apiKey) {
        WebClient webClient = WebClient.create();

        Map<String, Object> requestBody = Map.of(
                "contents", Collections.singletonList(
                        Map.of("parts", Collections.singletonList(
                                Map.of("text", context)))),
                "generationConfig", Map.of(
                        "temperature", 0.7,
                        "maxOutputTokens", 500));

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
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode candidates = root.path("candidates");
            if (candidates.isMissingNode() || candidates.isEmpty())
                throw new RuntimeException("No candidates found");

            JsonNode content = candidates.get(0).path("content");
            JsonNode parts = content.path("parts");

            if (parts.isMissingNode() || parts.isEmpty())
                throw new RuntimeException("Empty response parts");

            return parts.get(0).path("text").asText();
        } catch (Exception e) {
            log.error("Error parsing Gemini response", e);
            throw new RuntimeException("Parsing error", e);
        }
    }

    private String buildStrategyPrompt(com.AISA.AISA.analysis.dto.MarketValuationDto dto) {
        StringBuilder sb = new StringBuilder();
        sb.append("Role: Professional Market Analyst (Quant-based)\n");
        sb.append("Task: Write a concise market strategy (Korean, 3~4 sentences) based on the input data.\n");
        sb.append("Constraint: Focus on the conflict or alignment between Valuation, Sentiment, and Yield Gap.\n");
        sb.append("Input Data:\n");
        sb.append("- Market: ").append(dto.getMarket()).append("\n");
        sb.append("- Total Score: ").append(dto.getTotalScore()).append("/100 (Grade: ").append(dto.getGrade())
                .append(")\n");
        sb.append("- CAPE: ").append(dto.getValuation().getCape()).append(" (Range: ")
                .append(dto.getScoreDetails().getCapeRangePosition()).append("%)\n");
        sb.append("- Yield Gap: ").append(dto.getValuation().getYieldGap()).append("% (Inversion: ")
                .append(dto.getScoreDetails().getYieldGapInversion()).append(")\n");
        sb.append("- Sentiment: ").append(dto.getScoreDetails().getSentimentSignal()).append("\n");
        sb.append("- Investor Trend (Spot): Foreign=").append(dto.getInvestorTrend().getForeignTrend())
                .append(", Individual=").append(dto.getInvestorTrend().getIndividualTrend()).append("\n");
        sb.append("- Investor Trend (Futures): ForeignSum=").append(dto.getInvestorTrend().getFuturesForeignNet5d())
                .append(", IndividualSum=").append(dto.getInvestorTrend().getFuturesIndividualNet5d()).append("\n");
        if (dto.getInvestorTrend().getVkospi() != null) {
            sb.append("- VKOSPI: ").append(dto.getInvestorTrend().getVkospi()).append("\n");
        }

        sb.append("\nOutput (Korean):");
        return sb.toString();
    }
}
