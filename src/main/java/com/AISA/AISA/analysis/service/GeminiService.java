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

import java.math.BigDecimal;
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
    public static record StrategyResult(String valuationStrategy, String trendStrategy, String combinedStrategy) {
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
            return new StrategyResult(null, null, null); // Fallback to static
        }
    }

    private StrategyResult parseSplitResponse(String response) {
        String valuation = "";
        String trend = "";
        String combined = "";

        try {
            if (response.contains("[VALUATION_STRATEGY]")) {
                String[] parts1 = response.split("\\[TREND_STRATEGY\\]");
                valuation = parts1[0].replace("[VALUATION_STRATEGY]", "").trim();
                if (parts1.length > 1) {
                    if (parts1[1].contains("[COMBINED_STRATEGY]")) {
                        String[] parts2 = parts1[1].split("\\[COMBINED_STRATEGY\\]");
                        trend = parts2[0].trim();
                        combined = parts2[1].trim();
                    } else {
                        trend = parts1[1].trim();
                    }
                }
            } else {
                valuation = response; // Fallback if no tags
            }
        } catch (Exception e) {
            log.warn("Failed to parse split response, returning whole as valuation: {}", e.getMessage());
            valuation = response;
        }

        return new StrategyResult(valuation, trend, combined);
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
                                "maxOutputTokens", 2048))) // Increased for dual response
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
        sb.append("Task: Write three concise market analysis sections (Korean, 2~3 sentences each).\n");
        sb.append("1. [VALUATION_STRATEGY]: Focus ONLY on Valuation (Score, CAPE, Yield Gap, Valuation Signal).\n");
        sb.append(
                "2. [TREND_STRATEGY]: Focus ONLY on Market Trend (Trend Score, 수급, Breadth, VKOSPI, Trend Signal).\n");
        sb.append(
                "3. [COMBINED_STRATEGY]: Integrate Valuation and Trend to provide a final Investment Strategy based on the Final Combined Signal.\n");
        sb.append(
                "Constraint: Use the exact tags [VALUATION_STRATEGY], [TREND_STRATEGY], and [COMBINED_STRATEGY] to separate sections.\n");
        sb.append(
                "IMPORTANT: 출력 문장에 코스피, 코스닥, VKOSPI의 현재 지수 수치는 절대 포함하지 마세요. VKOSPI는 반드시 구간 표현(예: '공포 구간', '경계 수준')으로만 서술하세요.\n\n");

        sb.append("Input Data:\n");
        sb.append("- Market: ").append(dto.getMarket()).append("\n");
        sb.append("- Valuation Score: ").append(dto.getValuationAnalysis().getScore()).append("/100 (Grade: ")
                .append(dto.getValuationAnalysis().getState())
                .append(")\n");
        sb.append("- Trend Score: ").append(dto.getTrendAnalysis().getScore()).append("/100 (Description: ")
                .append(dto.getTrendAnalysis().getState()).append(")\n");

        if (dto.getValuation() != null) {
            sb.append("- CAPE: ").append(dto.getValuation().getCape()).append(" (Range: ")
                    .append(dto.getScoreDetails().getCapeRangePosition()).append("%)\n");
            sb.append("- Yield Gap: ").append(dto.getValuation().getYieldGap()).append("% (Inversion: ")
                    .append(dto.getScoreDetails().getYieldGapInversion()).append(")\n");
        }
        sb.append("- Valuation Signal: ").append(dto.getValuationAnalysis().getActionSignal()).append("\n");
        sb.append("- Trend Signal: ").append(dto.getTrendAnalysis().getActionSignal()).append("\n");
        if (dto.getInvestmentStrategy() != null) {
            sb.append("- Final Combined Signal: ").append(dto.getInvestmentStrategy().getFinalActionSignal())
                    .append("\n");
        }

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
                BigDecimal vk = dto.getInvestorTrend().getVkospi();
                String vkLevel;
                double vkVal = vk.doubleValue();
                if (vkVal <= 15)
                    vkLevel = "매우 안정 (극저변동성)";
                else if (vkVal <= 20)
                    vkLevel = "안정";
                else if (vkVal <= 30)
                    vkLevel = "보통 (평균 수준)";
                else if (vkVal <= 40)
                    vkLevel = "경계 (높은 변동성)";
                else
                    vkLevel = "공포 구간 (극단적 고변동성, 코로나급)";
                sb.append("- VKOSPI Level: [").append(vkLevel).append("]\n");
                sb.append("  * VKOSPI 해석 기준: ≤15 매우 안정, 15-20 안정, 20-30 보통, 30-40 경계, ≥40 공포 구간. ")
                        .append("40 이상은 시장 참여자들의 극단적 불안감을 반영하며, 급락 리스크 경고 신호입니다. ")
                        .append("상승장에서 VKOSPI가 높으면 '과열+불안' 공존 상태로 해석해야 합니다.\n");
            }
        } else {
            sb.append("- Investor Trend: Not available\n");
        }

        sb.append("\nOutput (Korean):");
        return sb.toString();
    }
}
