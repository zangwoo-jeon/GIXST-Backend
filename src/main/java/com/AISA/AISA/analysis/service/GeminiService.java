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

import java.util.List;
import java.util.Map;

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
    public static record StrategyResult(String valuationStrategy, String trendStrategy) {
    }

    /**
     * Generates context-aware market strategies based on the valuation and trend
     * data.
     * Returns a default/fallback if generation fails.
     */
    public StrategyResult generateMarketStrategy(com.AISA.AISA.analysis.dto.MarketValuationDto dto) {
        try {
            String prompt = buildStrategyPrompt(dto);
            String response = generateResponseWithRetry(prompt);
            return parseSplitResponse(response);
        } catch (Exception e) {
            log.warn("Failed to generate AI strategy: {}", e.getMessage());
            return new StrategyResult(null, null); // Fallback to static
        }
    }

    private StrategyResult parseSplitResponse(String response) {
        String valuation = "";
        String trend = "";

        try {
            if (response.contains("[VALUATION_STRATEGY]")) {
                String[] parts = response.split("\\[TREND_STRATEGY\\]");
                valuation = parts[0].replace("[VALUATION_STRATEGY]", "").trim();
                if (parts.length > 1) {
                    trend = parts[1].trim();
                }
            } else {
                valuation = response; // Fallback if no tags
            }
        } catch (Exception e) {
            log.warn("Failed to parse split response, returning whole as valuation: {}", e.getMessage());
            valuation = response;
        }

        return new StrategyResult(valuation, trend);
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
        int size = (keys != null) ? keys.size() : (geminiProperties.getApiKey() != null ? 1 : 0);
        log.info("Gemini key rotation initialized. Total available keys: {}", size);

        if (size == 0) {
            throw new IllegalStateException("Gemini API 키가 설정되지 않았습니다.");
        }

        int maxRetries = Math.max(size, 1);

        String lastError = "No keys available";
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            String currentKey = geminiProperties.getNextKey();
            String maskedKey = currentKey != null && currentKey.length() > 8
                    ? currentKey.substring(0, 4) + "****" + currentKey.substring(currentKey.length() - 4)
                    : "****";
            log.info("Attempting Gemini API call {}/{} using key: {}...", attempt + 1, maxRetries, maskedKey);
            try {
                String response = callGeminiApi(context, currentKey);
                return parseGeminiResponse(response);

            } catch (WebClientResponseException e) {
                lastError = e.getResponseBodyAsString();
                if (lastError == null || lastError.isEmpty())
                    lastError = e.getMessage();

                if (e.getStatusCode().value() == 429 || e.getStatusCode().value() == 400
                        || e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                    log.warn("Gemini API Error ({}). Response: {}. Rotating key... (Attempt {}/{})",
                            e.getStatusCode().value(), lastError, attempt + 1, maxRetries);
                    continue;
                }
                if (e.getStatusCode().is5xxServerError()) {
                    log.warn("Gemini Server Error ({}). Rotating key...", e.getStatusCode().value());
                    continue;
                }
                throw e;
            }
        }
        throw new RuntimeException("AI 서비스 사용량이 초과되었습니다. (최종 에러: " + lastError + ")");
    }

    private String callGeminiApi(String context, String apiKey) {
        WebClient webClient = WebClient.create();

        return webClient.post()
                .uri(geminiProperties.getUrl() + "?key={key}", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.<String, Object>of(
                        "contents", List.of(Map.of(
                                "parts", List.of(Map.of("text", context)))),
                        "generationConfig", Map.of(
                                "temperature", 0.7,
                                "maxOutputTokens", 800))) // Increased for dual response
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
        sb.append("Task: Write two concise market analysis sections (Korean, 2~3 sentences each).\n");
        sb.append("1. [VALUATION_STRATEGY]: Focus on Valuation (Score, CAPE, Yield Gap).\n");
        sb.append("2. [TREND_STRATEGY]: Focus on Market Trend (Trend Score, 수급, Breadth, VKOSPI).\n");
        sb.append("Constraint: Use the exact tags [VALUATION_STRATEGY] and [TREND_STRATEGY] to separate sections.\n\n");

        sb.append("Input Data:\n");
        sb.append("- Market: ").append(dto.getMarket()).append("\n");
        sb.append("- Valuation Score: ").append(dto.getValuationScore()).append("/100 (Grade: ").append(dto.getGrade())
                .append(")\n");
        sb.append("- Trend Score: ").append(dto.getTrendScore()).append("/100 (Description: ")
                .append(dto.getTrendDescription()).append(")\n");

        if (dto.getValuation() != null) {
            sb.append("- CAPE: ").append(dto.getValuation().getCape()).append(" (Range: ")
                    .append(dto.getScoreDetails().getCapeRangePosition()).append("%)\n");
            sb.append("- Yield Gap: ").append(dto.getValuation().getYieldGap()).append("% (Inversion: ")
                    .append(dto.getScoreDetails().getYieldGapInversion()).append(")\n");
        }
        sb.append("- Valuation Signal: ").append(dto.getScoreDetails().getValuationSignal()).append("\n");
        sb.append("- Trend Signal: ").append(dto.getScoreDetails().getTrendSignal()).append("\n");

        if (dto.getInvestorTrend() != null) {
            sb.append("- Common Stock Breadth: Rising=").append(dto.getInvestorTrend().getCommonRisingStockCount())
                    .append(", Falling=").append(dto.getInvestorTrend().getCommonFallingStockCount())
                    .append(" (Index: ").append(dto.getInvestorTrend().getCommonMarketBreadthIndex()).append(")\n");
            sb.append("- Breadth Avg (5d/20d): ").append(dto.getInvestorTrend().getBreadth5dAvg()).append(" / ")
                    .append(dto.getInvestorTrend().getBreadth20dAvg()).append("\n");

            sb.append("- Investor Trend (Spot): Foreign=").append(dto.getInvestorTrend().getForeignTrend())
                    .append(", Individual=").append(dto.getInvestorTrend().getIndividualTrend()).append("\n");
            sb.append("- Investor Trend (Futures): ForeignSum=").append(dto.getInvestorTrend().getFuturesForeignNet5d())
                    .append(", IndividualSum=").append(dto.getInvestorTrend().getFuturesIndividualNet5d()).append("\n");
            if (dto.getInvestorTrend().getVkospi() != null) {
                sb.append("- VKOSPI: ").append(dto.getInvestorTrend().getVkospi()).append("\n");
            }
        } else {
            sb.append("- Investor Trend: Not available\n");
        }

        sb.append("\nOutput (Korean):");
        return sb.toString();
    }
}