package com.AISA.AISA.analysis.service;

import com.AISA.AISA.global.config.GeminiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
    private final MeterRegistry meterRegistry;

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
    public StrategyResult generateMarketStrategy(com.AISA.AISA.analysis.dto.MarketValuationDto dto,
            BigDecimal effectiveVkospi) {
        try {
            String prompt = buildStrategyPrompt(dto, effectiveVkospi);
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

    /**
     * VERY_LOW 신뢰도 (OOD) 상황 전용 시나리오별 체크리스트 추천 생성.
     * 구체적 매수/매도 지시 금지, 가설적 시나리오로만 응답하도록 강제 프롬프트 적용.
     * 실패 시 null 반환 (호출자가 fallback 처리).
     */
    public com.AISA.AISA.analysis.dto.MarketValuationDto.ScenarioRecommendation generateScenarioRecommendation(
            com.AISA.AISA.analysis.dto.MarketValuationDto dto, BigDecimal effectiveVkospi) {
        try {
            String prompt = buildScenarioPrompt(dto, effectiveVkospi);
            String response = generateResponseWithRetry(prompt);
            return parseScenarioResponse(response);
        } catch (Exception e) {
            log.warn("Failed to generate scenario recommendation: {}", e.getMessage());
            return null;
        }
    }

    private String buildScenarioPrompt(com.AISA.AISA.analysis.dto.MarketValuationDto dto,
            BigDecimal effectiveVkospi) {
        com.AISA.AISA.analysis.dto.MarketValuationDto.PredictionReport pr = dto.getPredictionReport();
        com.AISA.AISA.analysis.dto.MarketValuationDto.OutcomeDistribution od = pr.getOutcomeDistribution();
        com.AISA.AISA.analysis.dto.MarketValuationDto.KnnStats ks = pr.getKnnStats();
        com.AISA.AISA.analysis.dto.MarketValuationDto.HistoricalMatch hm = pr.getHistoricalMatch();
        java.util.List<com.AISA.AISA.analysis.dto.MarketValuationDto.HistoricalMatchCase> top = pr.getTopMatches();

        BigDecimal cape = dto.getValuation() != null ? dto.getValuation().getCape() : null;
        BigDecimal tenYearHigh = (dto.getMetadata() != null
                && dto.getMetadata().getHistoricalStats() != null)
                        ? dto.getMetadata().getHistoricalStats().getTenYearHigh()
                        : null;
        boolean capeAtTenYearHigh = (cape != null && tenYearHigh != null
                && cape.compareTo(tenYearHigh) >= 0);

        StringBuilder sb = new StringBuilder();
        sb.append("역할: 한국 시장 분석 어시스턴트\n");
        sb.append("작성 목적: OOD 상황에서 사용자에게 정직한 historical context narrative 제공\n\n");

        sb.append("==== 1단계: OOD 선언 (반드시 narrative 첫 문장에 반영) ====\n");
        sb.append("- 시장: ").append(dto.getMarket()).append("\n");
        sb.append("- 현재 CAPE: ").append(cape);
        if (tenYearHigh != null) {
            sb.append(" (10년 최고치 ").append(tenYearHigh).append(")");
            if (capeAtTenYearHigh)
                sb.append(" ← **오늘이 10년 최고 또는 그 이상**");
        }
        sb.append("\n");
        sb.append("- 현재 VKOSPI: ").append(effectiveVkospi).append("\n");
        sb.append("- 진짜로 유사한 과거가 없음. KNN이 가장 '덜 다른' 30개를 골랐지만 평균 z-score 거리가 ");
        sb.append(ks != null ? ks.getAvgDistance() : "N/A");
        sb.append("로 매우 멉니다 (정상 범위 1~2, 현재는 OOD).\n\n");

        sb.append("==== 2단계: 가장 가까운 (그러나 여전히 먼) 상위 5개 사례 ====\n");
        sb.append("아래는 KNN이 5차원(CAPE/YG/VKOSPI/외국인수급/Breadth) 공간에서 가장 가깝다고 판정한 5개입니다.\n");
        sb.append("주의: distance가 1~2면 정상 매칭, 3 이상이면 사실상 비교가 위험합니다.\n\n");
        if (top != null && !top.isEmpty()) {
            for (int i = 0; i < top.size(); i++) {
                com.AISA.AISA.analysis.dto.MarketValuationDto.HistoricalMatchCase m = top.get(i);
                sb.append((i + 1)).append(". ").append(m.getDate())
                        .append(" — CAPE=").append(m.getCape())
                        .append(", VKOSPI=").append(m.getVkospi())
                        .append(", YG=").append(m.getYieldGap())
                        .append(", 외국인5d=").append(m.getForeignNet5d())
                        .append(", breadth5d=").append(m.getBreadth5d())
                        .append(" → 30일 후 수익률 ").append(m.getForwardReturn()).append("%")
                        .append(" (distance ").append(m.getDistance()).append(")\n");
            }
        } else {
            sb.append("(매칭 사례 없음)\n");
        }
        sb.append("\n");

        sb.append("==== 3단계: KNN 30개 전체 분포 요약 ====\n");
        if (ks != null && ks.getReturnStats() != null) {
            com.AISA.AISA.analysis.dto.MarketValuationDto.ReturnStats rs = ks.getReturnStats();
            sb.append("- 30일 forward return: min ").append(rs.getMin())
                    .append("%, max ").append(rs.getMax())
                    .append("%, mean ").append(rs.getMean())
                    .append("%, std ").append(rs.getStd()).append("%\n");
        }
        if (od != null) {
            sb.append("- 분위수 (raw): bear(p10) ").append(od.getBearCase().getReturnValue())
                    .append("%, base(p50) ").append(od.getBaseCase().getReturnValue())
                    .append("%, bull(p90) ").append(od.getBullCase().getReturnValue()).append("%\n");
        }
        if (hm != null) {
            sb.append("- 상승 비율: ").append(hm.getPositiveOutcomes()).append("/").append(hm.getTotalMatches())
                    .append(" = ").append(String.format("%.1f", hm.getWinRate())).append("%\n");
        }
        sb.append("\n");

        sb.append("==== 작성 지시 (반드시 준수) ====\n");
        sb.append("1. 각 시나리오(bearCase/baseCase/bullCase)는 narrative 한 필드만 작성. 체크리스트 작성 금지.\n");
        sb.append("2. narrative는 3~5문장. 다음 요소를 반드시 포함:\n");
        sb.append("   a) 위 5개 사례 중 1~2개를 구체적 date와 함께 인용 (예: \"2026-03-31 사례에서는...\")\n");
        sb.append("   b) 그 사례의 forward return을 언급\n");
        sb.append("   c) 전체 30개 분포에서의 위치 언급 (cherry-picking 회피)\n");
        sb.append("   d) \"이번이 비슷한 패턴일 수도, 전혀 다를 수도 있다\" 취지의 caveat 포함\n");
        sb.append("3. 절대 금지:\n");
        sb.append("   - 구체적 매수/매도/종목명/ETF 코드 추천 (예: \"삼성전자 매수\", \"TIGER 200\")\n");
        sb.append("   - 구체적 비중 지시 (예: \"방어주 30%로 늘리세요\")\n");
        sb.append("   - 단일 사례만 보고 단정적 예측 (예: \"2020-03 사례처럼 -15% 떨어집니다\")\n");
        sb.append("   - 추상적 체크리스트 형태 (\"고PER 비중 점검하세요\" 같은)\n");
        sb.append("4. summary는 1~2문장으로 OOD 상황(avgDistance ");
        sb.append(ks != null ? ks.getAvgDistance() : "N/A");
        sb.append(")과 narrative가 가설적임을 명시.\n\n");

        sb.append("==== 출력 형식 (JSON만, 마크다운 코드 블록 금지) ====\n");
        sb.append("{\n");
        sb.append("  \"summary\": \"OOD 상황 명시 + 가설적임을 명확히 (1~2문장)\",\n");
        sb.append("  \"bearCase\": { \"narrative\": \"...3~5문장, 위 지시사항 모두 반영...\" },\n");
        sb.append("  \"baseCase\": { \"narrative\": \"...\" },\n");
        sb.append("  \"bullCase\": { \"narrative\": \"...\" }\n");
        sb.append("}\n\n");
        sb.append("JSON만 출력:\n");
        return sb.toString();
    }

    private com.AISA.AISA.analysis.dto.MarketValuationDto.ScenarioRecommendation parseScenarioResponse(
            String response) {
        if (response == null || response.isBlank())
            return null;
        try {
            String cleaned = response.trim();
            // 마크다운 코드 블록 제거
            if (cleaned.startsWith("```")) {
                int firstNewline = cleaned.indexOf('\n');
                if (firstNewline > 0)
                    cleaned = cleaned.substring(firstNewline + 1);
                if (cleaned.endsWith("```"))
                    cleaned = cleaned.substring(0, cleaned.length() - 3);
                cleaned = cleaned.trim();
            }
            // JSON 객체 시작/끝만 추출
            int firstBrace = cleaned.indexOf('{');
            int lastBrace = cleaned.lastIndexOf('}');
            if (firstBrace < 0 || lastBrace < 0 || lastBrace <= firstBrace) {
                log.warn("Scenario response has no JSON object: {}", cleaned);
                return null;
            }
            String jsonOnly = cleaned.substring(firstBrace, lastBrace + 1);

            JsonNode root = objectMapper.readTree(jsonOnly);
            return com.AISA.AISA.analysis.dto.MarketValuationDto.ScenarioRecommendation.builder()
                    .summary(root.path("summary").asText(""))
                    .bearCase(extractDetail(root.path("bearCase")))
                    .baseCase(extractDetail(root.path("baseCase")))
                    .bullCase(extractDetail(root.path("bullCase")))
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse scenario JSON response: {}", e.getMessage());
            return null;
        }
    }

    private com.AISA.AISA.analysis.dto.MarketValuationDto.ScenarioDetail extractDetail(JsonNode node) {
        if (node == null || node.isMissingNode())
            return null;
        return com.AISA.AISA.analysis.dto.MarketValuationDto.ScenarioDetail.builder()
                .narrative(node.path("narrative").asText(""))
                .build();
    }

    /**
     * Shiller CAPE 기반 theoreticalAnchor에 대한 한국 시장 컨텍스트 explanation 생성.
     * Hallucination 방지를 위해 한국 시장 참고 사실 set을 명시적으로 제공하고, 해외 사례 인용 금지.
     * 실패 시 null 반환 (호출자가 정적 fallback explanation 유지).
     */
    public String generateTheoreticalAnchorExplanation(
            com.AISA.AISA.kisStock.enums.MarketType market,
            BigDecimal cape,
            com.AISA.AISA.analysis.dto.MarketValuationDto.TheoreticalAnchor anchor,
            com.AISA.AISA.analysis.dto.MarketValuationDto.HistoricalStats historicalStats,
            BigDecimal distributionPercentile) {
        if (anchor == null || cape == null)
            return null;
        try {
            String prompt = buildTheoreticalAnchorPrompt(market, cape, anchor, historicalStats,
                    distributionPercentile);
            String response = generateResponseWithRetry(prompt);
            if (response == null || response.isBlank())
                return null;
            return stripCodeFence(response.trim());
        } catch (Exception e) {
            log.warn("Failed to generate theoretical anchor explanation: {}", e.getMessage());
            return null;
        }
    }

    private String buildTheoreticalAnchorPrompt(
            com.AISA.AISA.kisStock.enums.MarketType market,
            BigDecimal cape,
            com.AISA.AISA.analysis.dto.MarketValuationDto.TheoreticalAnchor anchor,
            com.AISA.AISA.analysis.dto.MarketValuationDto.HistoricalStats historicalStats,
            BigDecimal distributionPercentile) {
        StringBuilder sb = new StringBuilder();
        sb.append("역할: 한국 주식시장 분석 어시스턴트\n");
        sb.append("목적: Shiller CAPE 모델 기반 장기 기대수익률 추정을 사용자가 객관적으로 이해하도록 설명\n\n");

        sb.append("===== 현재 데이터 (이 값만 사용. 다른 수치 임의 생성 절대 금지) =====\n");
        sb.append("- 시장: ").append(market.name()).append("\n");
        sb.append("- 현재 CAPE: ").append(cape).append("\n");
        if (historicalStats != null) {
            sb.append("- 10년 분포 — 최고: ").append(historicalStats.getTenYearHigh())
                    .append(" / 최저: ").append(historicalStats.getTenYearLow())
                    .append(" / 평균: ").append(historicalStats.getTenYearAvg())
                    .append(" / 중간값: ").append(historicalStats.getTenYearMedian()).append("\n");
        }
        if (distributionPercentile != null) {
            sb.append("- 현재 분포 백분위: 상위 ").append(distributionPercentile).append("%\n");
        }
        sb.append("- Shiller 모델 향후 10년 연환산 실질 수익률 추정: ")
                .append(anchor.getExpectedReturn10YAnnual()).append("%");
        if (anchor.getLowerBound() != null && anchor.getUpperBound() != null) {
            sb.append(" (범위 ").append(anchor.getLowerBound()).append("~")
                    .append(anchor.getUpperBound()).append("%)");
        }
        sb.append("\n");
        sb.append("- 분류: ").append(anchor.getCapeRegime()).append("\n\n");

        sb.append("===== 한국 시장 참고 사실 (필요 시만 인용. 이 목록 외 사건 임의 추가 절대 금지) =====\n");
        sb.append("KOSPI 주요 시기:\n");
        sb.append("- 1989-1990: KOSPI 1000p 첫 정점 후 조정\n");
        sb.append("- 1997-1998: IMF 외환위기, KOSPI 280p대 저점\n");
        sb.append("- 2007-2008: KOSPI 2000p 첫 돌파 후 글로벌 금융위기 조정\n");
        sb.append("- 2020-2021: 동학개미운동, KOSPI 3300p 역대 최고\n");
        sb.append("- 2022: 미국 긴축 영향 KOSPI 2200p대 조정\n\n");
        sb.append("KOSDAQ 주요 시기:\n");
        sb.append("- 1999-2000: 닷컴 버블, KOSDAQ 2925p 역대 최고\n");
        sb.append("- 2008: 글로벌 금융위기로 KOSDAQ 261p 저점\n");
        sb.append("- 2017-2018: 바이오/제약 주도 강세\n");
        sb.append("- 2020-2021: 코로나 후 회복, KOSDAQ 1060p\n\n");

        sb.append("===== 작성 규칙 (반드시 준수) =====\n");
        sb.append("1. 2~4문장. strategyText 톤 — 객관적, 정보 중심.\n");
        sb.append("2. 비유, 일상 비유 절대 금지.\n");
        sb.append("3. 위 \"한국 시장 참고 사실\" 목록 외 다른 한국 시장 사건 인용 금지.\n");
        sb.append("4. 1929 미국 대공황, 2000 미국 닷컴버블, 2008 미국 서브프라임 등 해외 시장 사례 절대 인용 금지.\n");
        sb.append("5. 위 \"현재 데이터\" 외 수치(CAPE 과거값, 지수 정확값 등) 추측 금지.\n");
        sb.append("6. 시장(").append(market.name()).append(")에 해당하는 한국 역사만 인용. 다른 시장 사례 사용 금지.\n");
        sb.append("7. Shiller 모델은 S&P 500 1881~ 백테스트 기반이라 \"한국 시장 적용은 방향성 참고 수준\"임을 1회 명시.\n");
        sb.append("8. 미래 단정 (\"~가 됩니다\") 금지. 확률적 표현 (\"~가 시사됩니다\", \"~할 가능성 있음\") 사용.\n");
        sb.append("9. 한국 거래소는 1956년 설립이라 그 이전 한국 시장 사례 인용 금지.\n\n");

        sb.append("===== 출력 형식 =====\n");
        sb.append("plain text 한 문단만. JSON, 마크다운 코드 블록, 헤더 일체 사용 금지.\n\n");

        sb.append("===== 작성 지시 =====\n");
        sb.append("다음을 모두 포함:\n");
        sb.append("- 현재 CAPE 값과 10년 분포 내 위치\n");
        sb.append("- 해당 시장에 맞는 한국 역사 시기 1~2개 참조 (분류가 EXTREME/EXPENSIVE면 거품기 인용, UNDERVALUED면 위기 저점 인용, FAIR면 인용 생략 가능)\n");
        sb.append("- Shiller 추정 수익률\n");
        sb.append("- Shiller 모델의 한국 적용 한계 1문장\n\n");
        sb.append("작성:\n");
        return sb.toString();
    }

    private String stripCodeFence(String s) {
        if (s == null)
            return null;
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0)
                t = t.substring(firstNewline + 1);
            if (t.endsWith("```"))
                t = t.substring(0, t.length() - 3);
            t = t.trim();
        }
        return t;
    }

    /**
     * 모든 분석 결과를 통합한 종합 분석 narrative 생성. 구조화된 객체로 반환.
     * 4축 구조: 밸류에이션 / 추세·수급 / 리스크 지표 / 내수 펀더멘털
     * + 종합 전략 + 한 줄 요약 + 행동 지침
     * 실패 시 null 반환 (호출자가 빈 필드 유지).
     */
    public com.AISA.AISA.analysis.dto.MarketValuationDto.ComprehensiveAnalysis generateComprehensiveAnalysis(
            com.AISA.AISA.analysis.dto.MarketValuationDto dto, BigDecimal effectiveVkospi) {
        if (dto == null)
            return null;
        try {
            String prompt = buildComprehensiveAnalysisPrompt(dto, effectiveVkospi);
            String response = generateResponseWithRetry(prompt);
            if (response == null || response.isBlank())
                return null;
            return parseComprehensiveResponse(response);
        } catch (Exception e) {
            log.warn("Failed to generate comprehensive analysis: {}", e.getMessage());
            return null;
        }
    }

    private com.AISA.AISA.analysis.dto.MarketValuationDto.ComprehensiveAnalysis parseComprehensiveResponse(
            String response) {
        if (response == null || response.isBlank())
            return null;
        try {
            String cleaned = stripCodeFence(response.trim());
            int firstBrace = cleaned.indexOf('{');
            int lastBrace = cleaned.lastIndexOf('}');
            if (firstBrace < 0 || lastBrace < 0 || lastBrace <= firstBrace) {
                log.warn("Comprehensive response has no JSON object: {}", cleaned);
                return null;
            }
            String jsonOnly = cleaned.substring(firstBrace, lastBrace + 1);
            JsonNode root = objectMapper.readTree(jsonOnly);

            java.util.List<String> guidelines = new java.util.ArrayList<>();
            JsonNode guidelinesNode = root.path("actionGuidelines");
            if (guidelinesNode.isArray()) {
                for (JsonNode g : guidelinesNode) {
                    String text = g.asText("");
                    if (!text.isBlank())
                        guidelines.add(text);
                }
            }

            return com.AISA.AISA.analysis.dto.MarketValuationDto.ComprehensiveAnalysis.builder()
                    .overallSummary(root.path("overallSummary").asText(""))
                    .valuationAxis(root.path("valuationAxis").asText(""))
                    .trendAxis(root.path("trendAxis").asText(""))
                    .riskAxis(root.path("riskAxis").asText(""))
                    .domesticAxis(root.path("domesticAxis").asText(""))
                    .strategyNarrative(root.path("strategyNarrative").asText(""))
                    .oneLineSummary(root.path("oneLineSummary").asText(""))
                    .actionGuidelines(guidelines.isEmpty() ? null : guidelines)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse comprehensive JSON response: {}", e.getMessage());
            return null;
        }
    }

    private String buildComprehensiveAnalysisPrompt(com.AISA.AISA.analysis.dto.MarketValuationDto dto,
            BigDecimal effectiveVkospi) {
        StringBuilder sb = new StringBuilder();
        sb.append("역할: 한국 주식시장 종합 분석 어시스턴트\n");
        sb.append("목적: 사용자에게 현재 시장 상황을 정직하고 깊이 있게 설명하는 종합 분석 글 작성\n\n");

        sb.append("===== 현재 데이터 (이 값만 사용. 다른 수치 임의 생성 절대 금지) =====\n");
        sb.append("[기본 정보]\n");
        sb.append("- 시장: ").append(dto.getMarket()).append("\n");
        if (dto.getValuationAnalysis() != null) {
            sb.append("- 밸류에이션 점수: ").append(dto.getValuationAnalysis().getScore())
                    .append("/100 (").append(dto.getValuationAnalysis().getState()).append(")\n");
        }
        if (dto.getTrendAnalysis() != null) {
            sb.append("- 추세 점수: ").append(dto.getTrendAnalysis().getScore())
                    .append("/100 (").append(dto.getTrendAnalysis().getState()).append(")\n");
        }
        if (dto.getInvestmentStrategy() != null) {
            sb.append("- 최종 신호: ").append(dto.getInvestmentStrategy().getFinalActionSignal()).append("\n");
            if (Boolean.TRUE.equals(dto.getInvestmentStrategy().getOodFlag()))
                sb.append("- OOD 플래그: true (유사 과거 사례 부족)\n");
            if (Boolean.TRUE.equals(dto.getInvestmentStrategy().getUncertaintyFlag()))
                sb.append("- 불확실성 플래그: true (통계적 유의성 부족)\n");
        }

        sb.append("\n[1. 밸류에이션]\n");
        if (dto.getValuation() != null) {
            sb.append("- CAPE: ").append(dto.getValuation().getCape()).append("\n");
            sb.append("- PER: ").append(dto.getValuation().getPer()).append("\n");
            sb.append("- PBR: ").append(dto.getValuation().getPbr()).append("\n");
            sb.append("- Yield Gap: ").append(dto.getValuation().getYieldGap()).append("%\n");
            sb.append("- 국고채 10년 금리: ").append(dto.getValuation().getBondYield()).append("%\n");
        }
        if (dto.getScoreDetails() != null) {
            sb.append("- CAPE 분포 백분위: 상위 ").append(dto.getScoreDetails().getDistributionPercentile()).append("%\n");
            sb.append("- Yield Gap 백분위: ").append(dto.getScoreDetails().getYieldGapPercentile()).append("%\n");
        }
        if (dto.getMetadata() != null && dto.getMetadata().getHistoricalStats() != null) {
            sb.append("- 10년 CAPE 최고: ").append(dto.getMetadata().getHistoricalStats().getTenYearHigh())
                    .append(" / 최저: ").append(dto.getMetadata().getHistoricalStats().getTenYearLow())
                    .append(" / 평균: ").append(dto.getMetadata().getHistoricalStats().getTenYearAvg()).append("\n");
        }
        if (dto.getTheoreticalAnchor() != null) {
            sb.append("- Shiller 10년 기대수익률: ").append(dto.getTheoreticalAnchor().getExpectedReturn10YAnnual())
                    .append("% (범위 ").append(dto.getTheoreticalAnchor().getLowerBound())
                    .append("~").append(dto.getTheoreticalAnchor().getUpperBound()).append("%)\n");
            sb.append("- CAPE regime: ").append(dto.getTheoreticalAnchor().getCapeRegime()).append("\n");
        }

        sb.append("\n[2. 추세 및 수급]\n");
        if (dto.getInvestorTrend() != null) {
            sb.append("수급 단위 안내: 아래 외국인/개인/기관 순매수 금액은 모두 '만원' 단위 (예: 100000 = 10억원).\n");
            sb.append("- 외국인 5일 누적 순매수(만원): ").append(dto.getInvestorTrend().getForeignNet5d()).append("\n");
            sb.append("- 개인 5일 누적 순매수(만원): ").append(dto.getInvestorTrend().getIndividualNet5d()).append("\n");
            sb.append("- 기관 5일 누적 순매수(만원): ").append(dto.getInvestorTrend().getInstitutionalNet5d()).append("\n");
            sb.append("- 외국인 추세: ").append(dto.getInvestorTrend().getForeignTrend()).append("\n");
            sb.append("- 개인 추세: ").append(dto.getInvestorTrend().getIndividualTrend()).append("\n");
            sb.append("\nBreadth 지표 안내: 아래 두 값은 서로 다른 지표. 인용 시 정확한 라벨로 구분할 것.\n");
            sb.append("- [breadthSpot] 오늘 기준 스팟 breadth 지수: ")
                    .append(dto.getInvestorTrend().getCommonMarketBreadthIndex()).append("\n");
            sb.append("- [breadth5dAvg] breadth 5일 이동평균: ")
                    .append(dto.getInvestorTrend().getBreadth5dAvg()).append("\n");
            sb.append("  주의: 두 값을 합산/평균/혼동하지 말 것. 위 두 값 외의 breadth 수치는 존재하지 않음.\n");
        }

        sb.append("\n[3. 리스크 지표]\n");
        if (effectiveVkospi != null) {
            String vkLevel;
            double vk = effectiveVkospi.doubleValue();
            if (vk <= 15)
                vkLevel = "매우 안정";
            else if (vk <= 20)
                vkLevel = "안정";
            else if (vk <= 30)
                vkLevel = "보통";
            else if (vk <= 40)
                vkLevel = "경계";
            else
                vkLevel = "공포 구간";
            sb.append("- VKOSPI: ").append(effectiveVkospi).append(" (").append(vkLevel).append(")\n");
        }

        sb.append("\n[4. 내수 펀더멘털]\n");
        if (dto.getDomesticEconomy() != null) {
            com.AISA.AISA.analysis.dto.MarketValuationDto.DomesticEconomy de = dto.getDomesticEconomy();
            if (de.getDelinquencyHousehold() != null)
                sb.append("- 가계 연체율: ").append(de.getDelinquencyHousehold().getValue())
                        .append("% (").append(de.getDelinquencyHousehold().getRegime()).append(")\n");
            if (de.getDelinquencyCorporate() != null)
                sb.append("- 기업 전체 연체율: ").append(de.getDelinquencyCorporate().getValue())
                        .append("% (").append(de.getDelinquencyCorporate().getRegime()).append(")\n");
            if (de.getDelinquencyLarge() != null)
                sb.append("- 대기업 연체율: ").append(de.getDelinquencyLarge().getValue())
                        .append("% (").append(de.getDelinquencyLarge().getRegime()).append(")\n");
            if (de.getDelinquencySmall() != null)
                sb.append("- 중소기업 연체율: ").append(de.getDelinquencySmall().getValue())
                        .append("% (").append(de.getDelinquencySmall().getRegime()).append(")\n");
            if (de.getConsumerSentiment() != null)
                sb.append("- 소비자심리지수: ").append(de.getConsumerSentiment().getValue())
                        .append(" (").append(de.getConsumerSentiment().getRegime()).append(")\n");
        }

        sb.append("\n[5. KNN 예측 결과]\n");
        if (dto.getPredictionReport() != null) {
            com.AISA.AISA.analysis.dto.MarketValuationDto.PredictionReport pr = dto.getPredictionReport();
            if (pr.getKnnStats() != null) {
                sb.append("- KNN 신뢰도: ").append(pr.getKnnStats().getConfidence()).append("\n");
                sb.append("- 평균 z-score 거리: ").append(pr.getKnnStats().getAvgDistance()).append("\n");
            }
            if (pr.getHistoricalMatch() != null) {
                sb.append("- 30개 유사 사례 winRate: ").append(pr.getHistoricalMatch().getWinRate()).append("%\n");
                sb.append("- 평균 30일 수익률: ").append(pr.getHistoricalMatch().getAverageReturn()).append("%\n");
            }
            if (pr.getOutcomeDistribution() != null) {
                if (pr.getOutcomeDistribution().getBearCase() != null)
                    sb.append("- bear case (p10): ")
                            .append(pr.getOutcomeDistribution().getBearCase().getReturnValue()).append("%\n");
                if (pr.getOutcomeDistribution().getBaseCase() != null)
                    sb.append("- base case (p50): ")
                            .append(pr.getOutcomeDistribution().getBaseCase().getReturnValue()).append("%\n");
                if (pr.getOutcomeDistribution().getBullCase() != null)
                    sb.append("- bull case (p90): ")
                            .append(pr.getOutcomeDistribution().getBullCase().getReturnValue()).append("%\n");
            }
            if (pr.getTopMatches() != null && !pr.getTopMatches().isEmpty()) {
                sb.append("- 가장 유사한 과거 사례 (상위 3개):\n");
                int limit = Math.min(3, pr.getTopMatches().size());
                for (int i = 0; i < limit; i++) {
                    com.AISA.AISA.analysis.dto.MarketValuationDto.HistoricalMatchCase m = pr.getTopMatches().get(i);
                    sb.append("  ").append(i + 1).append(". ").append(m.getDate())
                            .append(" — CAPE ").append(m.getCape())
                            .append(", VKOSPI ").append(m.getVkospi())
                            .append(" → 30일 후 ").append(m.getForwardReturn()).append("%\n");
                }
            }
        }

        sb.append("\n===== 한국 시장 참고 사실 (필요 시만 인용. 이 목록 외 사건 임의 추가 절대 금지) =====\n");
        sb.append("KOSPI 주요 시기:\n");
        sb.append("- 1989-1990: KOSPI 1000p 첫 정점 후 조정\n");
        sb.append("- 1997-1998: IMF 외환위기, KOSPI 280p대 저점\n");
        sb.append("- 2007-2008: KOSPI 2000p 첫 돌파 후 글로벌 금융위기 조정\n");
        sb.append("- 2020-2021: 동학개미운동, KOSPI 3300p 역대 최고\n");
        sb.append("- 2022: 미국 긴축 영향 KOSPI 2200p대 조정\n");
        sb.append("KOSDAQ 주요 시기:\n");
        sb.append("- 1999-2000: 닷컴 버블, KOSDAQ 2925p 역대 최고\n");
        sb.append("- 2008: 글로벌 금융위기로 KOSDAQ 261p 저점\n");
        sb.append("- 2017-2018: 바이오/제약 주도 강세\n");
        sb.append("- 2020-2021: 코로나 후 회복, KOSDAQ 1060p\n\n");

        sb.append("===== 작성 규칙 (반드시 준수) =====\n");
        sb.append("1. 위 \"현재 데이터\" 값만 사용. 다른 수치(CAPE 과거값, 지수 정확값 등) 추측 절대 금지.\n");
        sb.append("2. 위 \"한국 시장 참고 사실\" 목록 외 다른 한국 시장 사건 인용 금지.\n");
        sb.append("3. 1929 미국 대공황, 2000 미국 닷컴버블, 2008 미국 서브프라임 등 해외 시장 사례 인용 금지.\n");
        sb.append("4. 객관적, 정보 중심 톤. 비유, 일상 비유 절대 금지.\n");
        sb.append("5. 미래 단정 (\"~가 됩니다\") 금지. 확률적 표현 (\"~가 시사됩니다\", \"~할 가능성 있음\") 사용.\n");
        sb.append("6. KNN OOD/VERY_LOW 신뢰도면 \"통계적 예측 신뢰도 낮음\"을 반드시 명시.\n");
        sb.append("7. 한국 거래소 1956년 설립이라 그 이전 한국 시장 사례 인용 금지.\n");
        sb.append("8. Shiller 모델 인용 시 \"미국 S&P 500 데이터 기반, 한국 적용은 방향성 참고 수준\" 한 번 명시.\n");
        sb.append("9. [수치 환각 방지] 위 \"현재 데이터\"에 명시되지 않은 수치는 절대 인용/생성 금지. ")
                .append("모든 숫자는 위 입력에서 그대로 복사하여 사용. ")
                .append("두 값을 임의로 합산/평균/변환하지 말 것 (특히 breadth, 수급 금액).\n\n");

        sb.append("===== 출력 형식 (반드시 JSON. 다른 형식 절대 금지) =====\n");
        sb.append("JSON 객체만 출력. 마크다운 코드 블록(```), 헤더(##), LaTeX($...$), 굵은 글씨(**) 사용 금지.\n");
        sb.append("각 필드는 plain text (줄바꿈은 \\n 이스케이프 사용).\n\n");

        sb.append("JSON 스키마:\n");
        sb.append("{\n");
        sb.append("  \"overallSummary\": \"전체 진단 요약 (3~4문장). OOD/VERY_LOW면 '통계적 예측 범위 초과 리스크 구간' 같은 표현 포함.\",\n");
        sb.append("  \"valuationAxis\": \"1. 밸류에이션 — CAPE, Yield Gap, Shiller 추정 등을 통합 설명 (2~4문장).\",\n");
        sb.append("  \"trendAxis\": \"2. 추세 및 수급 — 추세 점수, 외국인/개인 수급 엇갈림 (2~4문장).\",\n");
        sb.append("  \"riskAxis\": \"3. 리스크 지표 — VKOSPI 구간과 의미 (2~4문장).\",\n");
        sb.append("  \"domesticAxis\": \"4. 실물 경제 (내수 펀더멘털) — 연체율 regime + CSI (2~4문장).\",\n");
        sb.append("  \"strategyNarrative\": \"최종 신호(").append(dto.getInvestmentStrategy() != null
                ? dto.getInvestmentStrategy().getFinalActionSignal() : "").append(")의 의미를 한 단락(3~5문장)으로 풀이.\",\n");
        sb.append("  \"oneLineSummary\": \"현재 시장을 한 줄로 요약. 비유 금지. 펀더멘털/밸류에이션/변동성/수급 키워드 사용.\",\n");
        sb.append("  \"actionGuidelines\": [\n");
        sb.append("    \"현금 비중 / 포지션 유지 관련 행동 지침 (1~2문장).\",\n");
        sb.append("    \"리스크 관리 우선순위 — KNN 유사 사례의 손익비 언급 (1~2문장).\",\n");
        sb.append("    \"어떤 신호가 나오면 포지션 전환할지 명시 (1~2문장).\"\n");
        sb.append("  ]\n");
        sb.append("}\n\n");

        sb.append("각 필드 내용은 위 작성 규칙(해외 사례 인용 금지, 비유 금지, 확률적 표현 등)을 반드시 준수.\n");
        sb.append("JSON 객체만 출력 (앞뒤 어떤 텍스트나 마크다운도 금지):\n");
        return sb.toString();
    }

    private String generateResponseWithRetry(String context) throws Exception {
        // 전체 요청 단위 지연/성공률 측정 (키 로테이션 전체를 포함한 체감 시간)
        Timer.Sample requestSample = Timer.start(meterRegistry);
        String requestOutcome = "failure";
        try {
            String result = doGenerateResponseWithRetry(context);
            requestOutcome = "success";
            return result;
        } finally {
            requestSample.stop(meterRegistry.timer("gemini.request.latency"));
            meterRegistry.counter("gemini.requests", "outcome", requestOutcome).increment();
        }
    }

    private String doGenerateResponseWithRetry(String context) throws Exception {
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
                meterRegistry.counter("gemini.attempts", "result", "success").increment();
                return parseGeminiResponse(response);

            } catch (WebClientResponseException e) {
                lastError = e.getResponseBodyAsString();
                if (lastError == null || lastError.isEmpty())
                    lastError = e.getMessage();

                if (e.getStatusCode().value() == 429 || e.getStatusCode().value() == 400
                        || e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                    // 429: 키당 호출 한도 초과 → 라운드 로빈으로 다음 키 시도
                    String result = (e.getStatusCode().value() == 429) ? "rate_limited" : "client_error";
                    meterRegistry.counter("gemini.attempts", "result", result).increment();
                    log.warn("Gemini API Error ({}). Response: {}. Rotating key... (Attempt {}/{})",
                            e.getStatusCode().value(), lastError, attempt + 1, maxRetries);
                    continue;
                }
                if (e.getStatusCode().is5xxServerError()) {
                    meterRegistry.counter("gemini.attempts", "result", "server_error").increment();
                    log.warn("Gemini Server Error ({}). Rotating key...", e.getStatusCode().value());
                    continue;
                }
                throw e;
            }
        }
        // 모든 키가 소진됨 — 라운드 로빈으로도 막지 못한 최종 실패 (정적 폴백으로 이어짐)
        meterRegistry.counter("gemini.exhausted").increment();
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

    private String buildStrategyPrompt(com.AISA.AISA.analysis.dto.MarketValuationDto dto,
            BigDecimal effectiveVkospi) {
        StringBuilder sb = new StringBuilder();
        sb.append("Role: Professional Market Analyst (Quant-based)\n");
        sb.append("Task: Write three concise market analysis sections (Korean, 2~3 sentences each).\n");
        sb.append("1. [VALUATION_STRATEGY]: Focus ONLY on Valuation (Score, CAPE, Yield Gap, Valuation Signal).\n");
        sb.append(
                "2. [TREND_STRATEGY]: Focus ONLY on Market Trend (Trend Score, 수급, Breadth, VKOSPI, Trend Signal).\n");
        sb.append(
                "3. [COMBINED_STRATEGY]: Integrate Valuation, Trend, and Domestic Economy (내수 펀더멘털) to provide a final Investment Strategy based on the Final Combined Signal. 내수 펀더멘털의 regime이 valuation 과열도와 맞지 않으면 그 미스매치를 반드시 짚으세요.\n");
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
            // KOSDAQ 등 자체 VKOSPI가 없는 시장은 KOSPI VKOSPI 차용한 effectiveVkospi 사용
            BigDecimal vk = effectiveVkospi;
            if (vk != null) {
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
                sb.append("- VKOSPI Level: [").append(vkLevel).append("] (값: ").append(vk).append(")\n");
                sb.append("  * VKOSPI 해석 기준: ≤15 매우 안정, 15-20 안정, 20-30 보통, 30-40 경계, ≥40 공포 구간. ")
                        .append("40 이상은 시장 참여자들의 극단적 불안감을 반영하며, 급락 리스크 경고 신호입니다. ")
                        .append("상승장에서 VKOSPI가 높으면 '과열+불안' 공존 상태로 해석해야 합니다.\n");
            }
        } else {
            sb.append("- Investor Trend: Not available\n");
        }

        // 한국 내수 펀더멘털 — Combined Strategy에 반영. valuation 과열과 미스매치 시 "착시성 과열" 가능성 짚기.
        if (dto.getDomesticEconomy() != null) {
            com.AISA.AISA.analysis.dto.MarketValuationDto.DomesticEconomy de = dto.getDomesticEconomy();
            sb.append("\n--- 한국 내수 펀더멘털 (한국은행 ECOS) ---\n");
            if (de.getDelinquencySmall() != null) {
                sb.append("- 중소기업 연체율: ").append(de.getDelinquencySmall().getValue())
                        .append("% (regime: ").append(de.getDelinquencySmall().getRegime())
                        .append(", 평시 ").append(de.getDelinquencySmall().getNormalRange()).append(")\n");
            }
            if (de.getDelinquencyCorporate() != null) {
                sb.append("- 기업 전체 연체율: ").append(de.getDelinquencyCorporate().getValue())
                        .append("% (regime: ").append(de.getDelinquencyCorporate().getRegime())
                        .append(", 평시 ").append(de.getDelinquencyCorporate().getNormalRange()).append(")\n");
            }
            if (de.getDelinquencyLarge() != null) {
                sb.append("- 대기업 연체율: ").append(de.getDelinquencyLarge().getValue())
                        .append("% (regime: ").append(de.getDelinquencyLarge().getRegime())
                        .append(", 평시 ").append(de.getDelinquencyLarge().getNormalRange())
                        .append(") — 대기업이 BORDERLINE/RISK면 시장 전체 systemic stress 초기 시그널\n");
            }
            if (de.getDelinquencyHousehold() != null) {
                sb.append("- 가계 연체율: ").append(de.getDelinquencyHousehold().getValue())
                        .append("% (regime: ").append(de.getDelinquencyHousehold().getRegime())
                        .append(", 평시 ").append(de.getDelinquencyHousehold().getNormalRange()).append(")\n");
            }
            if (de.getConsumerSentiment() != null) {
                sb.append("- 소비자심리지수 (CSI): ").append(de.getConsumerSentiment().getValue())
                        .append(" (regime: ").append(de.getConsumerSentiment().getRegime())
                        .append(", 100 기준)\n");
            }
            sb.append("- 내수 진단 요약: ").append(de.getInterpretation()).append("\n");
            sb.append("  * 한국 시장 해석 가이드: 한국은 시가총액 상위 종목(반도체·금융 등)이 시장 평균 PER/CAPE를 끌어올리는 경향이 있어, ")
                    .append("내수 펀더멘털(연체율·CSI) regime이 valuation 과열도와 *미스매치*일 경우 '착시성 과열' 가능성을 시사합니다. ")
                    .append("즉 valuation은 EXTREME/STRONG_OVERHEATED인데 내수가 BORDERLINE/RISK면 ")
                    .append("주도주 캐리로 평균이 끌어올려진 반면 내수 종목들은 소외되어 양극화가 심화된 상태로 해석 가능. ")
                    .append("Combined Strategy에 이 미스매치를 반드시 짚으세요.\n");
        }

        sb.append("\nOutput (Korean):");
        return sb.toString();
    }
}
