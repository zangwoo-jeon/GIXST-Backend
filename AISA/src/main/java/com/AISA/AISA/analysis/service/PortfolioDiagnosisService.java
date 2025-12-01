package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.dto.CorrelationResultDto;
import com.AISA.AISA.analysis.dto.DiagnosisResultDto;
import com.AISA.AISA.portfolio.backtest.dto.BacktestResultDto;
import com.AISA.AISA.portfolio.backtest.dto.DailyPortfolioValueDto;
import com.AISA.AISA.portfolio.backtest.service.BacktestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PortfolioDiagnosisService {

    private final BacktestService backtestService;
    private final AnalysisService analysisService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Transactional(readOnly = true)
    public DiagnosisResultDto diagnosePortfolio(UUID portfolioId, String startDate, String endDate) {
        // 1. Get Portfolio Daily Values (Virtual Asset)
        BacktestResultDto backtestResult = backtestService.calculatePortfolioBacktest(portfolioId, startDate, endDate);
        Map<LocalDate, Double> portfolioSeries = new TreeMap<>();
        for (DailyPortfolioValueDto daily : backtestResult.getDailyValues()) {
            // Use adjustedValue for correlation analysis to handle new listings smoothly
            if (daily.getAdjustedValue() != null) {
                portfolioSeries.put(LocalDate.parse(daily.getDate(), FORMATTER),
                        daily.getAdjustedValue().doubleValue());
            } else {
                // Fallback for old data or if adjustedValue is missing (shouldn't happen with
                // new logic)
                portfolioSeries.put(LocalDate.parse(daily.getDate(), FORMATTER), daily.getTotalValue().doubleValue());
            }
        }

        List<DiagnosisResultDto.FactorAnalysisResult> factorResults = new ArrayList<>();
        List<String> adviceList = new ArrayList<>();

        // 2. Define Factors to Analyze
        // Factor 1: KOSPI (Domestic Market)
        analyzeFactor(portfolioSeries, "INDEX", "KOSPI", "국내 시장(KOSPI)", startDate, endDate, factorResults, adviceList);

        // Factor 2: KOSDAQ (Domestic Growth)
        analyzeFactor(portfolioSeries, "INDEX", "KOSDAQ", "국내 코스닥(KOSDAQ)", startDate, endDate, factorResults,
                adviceList);

        // Factor 3: NASDAQ (Global Tech Market)
        analyzeFactor(portfolioSeries, "INDEX", "NASDAQ", "미국 기술주(NASDAQ)", startDate, endDate, factorResults,
                adviceList);

        // Factor 4: S&P 500 (US Broad Market)
        analyzeFactor(portfolioSeries, "INDEX", "SP500", "미국 대형주(S&P500)", startDate, endDate, factorResults,
                adviceList);

        // Factor 5: USD Exchange Rate (Currency Risk)
        analyzeFactor(portfolioSeries, "EXCHANGE", "FX@KRW", "달러 환율", startDate, endDate, factorResults, adviceList);

        // Factor 6: KR Base Rate (Monetary Policy)
        analyzeFactor(portfolioSeries, "BASE_RATE", "BASE_RATE", "한국 기준금리", startDate, endDate, factorResults,
                adviceList);

        // Factor 7: KR Bonds (Interest Rate Risk)
        analyzeFactor(portfolioSeries, "BOND", "KR_3Y", "한국 국채 3년", startDate, endDate, factorResults, adviceList);
        analyzeFactor(portfolioSeries, "BOND", "KR_10Y", "한국 국채 10년", startDate, endDate, factorResults, adviceList);

        // Factor 8: US Bonds (Interest Rate Risk)
        analyzeFactor(portfolioSeries, "BOND", "US_1Y", "미국 국채 1년", startDate, endDate, factorResults, adviceList);
        analyzeFactor(portfolioSeries, "BOND", "US_10Y", "미국 10년물 국채", startDate, endDate, factorResults, adviceList);

        return DiagnosisResultDto.builder()
                .portfolioId(portfolioId.toString())
                .diagnosisDate(LocalDate.now().format(FORMATTER))
                .factorAnalysis(factorResults)
                .adviceList(adviceList)
                .build();
    }

    private void analyzeFactor(Map<LocalDate, Double> portfolioSeries, String factorType, String factorCode,
            String factorName,
            String startDate, String endDate,
            List<DiagnosisResultDto.FactorAnalysisResult> results, List<String> adviceList) {
        try {
            CorrelationResultDto correlationResult = analysisService.calculateCorrelation(
                    portfolioSeries, "MyPortfolio", factorType, factorCode, startDate, endDate, "AUTO");

            double correlation = correlationResult.getCoefficient();
            String sensitivity = determineSensitivity(correlation);
            String description = interpretFactorResult(factorName, correlation);

            results.add(DiagnosisResultDto.FactorAnalysisResult.builder()
                    .factorName(factorName)
                    .correlation(Math.round(correlation * 100.0) / 100.0)
                    .sensitivity(sensitivity)
                    .description(description)
                    .build());

            generateAdvice(factorName, correlation, adviceList);

        } catch (Exception e) {
            log.warn("Failed to analyze factor {}: {}", factorName, e.getMessage());
            results.add(DiagnosisResultDto.FactorAnalysisResult.builder()
                    .factorName(factorName)
                    .description("데이터 부족 또는 분석 실패")
                    .build());
        }
    }

    private String determineSensitivity(double correlation) {
        if (correlation >= 0.7)
            return "매우 높음 (양의 상관)";
        if (correlation >= 0.3)
            return "높음 (양의 상관)";
        if (correlation > -0.3)
            return "낮음 (중립)";
        if (correlation > -0.7)
            return "높음 (음의 상관)";
        return "매우 높음 (음의 상관)";
    }

    private String interpretFactorResult(String factorName, double correlation) {
        if (correlation >= 0.7)
            return "포트폴리오가 " + factorName + "와(과) 매우 유사하게 움직입니다.";
        if (correlation >= 0.3)
            return "포트폴리오가 " + factorName + "의 영향을 어느 정도 받습니다.";
        if (correlation > -0.3)
            return "포트폴리오가 " + factorName + "와(과) 무관하게 움직입니다 (분산 효과).";
        if (correlation > -0.7)
            return "포트폴리오가 " + factorName + "와(과) 반대로 움직이는 경향이 있습니다 (헷지 효과).";
        return "포트폴리오가 " + factorName + "와(과) 정반대로 움직입니다 (강한 헷지).";
    }

    // TODO: 추후 LLM(ChatGPT, HyperClova 등)과 연동하여 더 정교하고 개인화된 조언을 생성하도록 고도화 필요
    private void generateAdvice(String factorName, double correlation, List<String> adviceList) {
        if ("국내 시장(KOSPI)".equals(factorName)) {
            if (correlation >= 0.7) {
                adviceList.add("국내 시장 의존도가 매우 높습니다. 해외 자산이나 채권 비중을 늘려 분산투자 효과를 높이세요.");
            } else if (correlation >= 0.4) {
                adviceList.add("국내 시장의 흐름을 어느 정도 따르고 있습니다. 포트폴리오 변동성 관리에 유의하세요.");
            }
        } else if ("국내 코스닥(KOSDAQ)".equals(factorName)) {
            if (correlation >= 0.6) {
                adviceList.add("변동성이 큰 코스닥 시장과 유사하게 움직입니다. 중소형주 비중이 높다면 우량주 비중을 늘려 안정성을 확보하세요.");
            } else if (correlation >= 0.4) {
                adviceList.add("코스닥 시장의 영향을 받고 있습니다. 변동성이 커질 수 있으니 리스크 관리가 필요합니다.");
            }
        } else if ("미국 기술주(NASDAQ)".equals(factorName)) {
            if (correlation >= 0.7) {
                adviceList.add("기술주 중심의 공격적 성향입니다. 시장 하락 시 변동성이 클 수 있으니 방어 자산(배당주, 채권)을 고려하세요.");
            } else if (correlation >= 0.5) {
                adviceList.add("미국 기술주의 영향을 꽤 받고 있습니다. 성장주 위주의 포트폴리오라면 금리 변화에 민감할 수 있습니다.");
            }
        } else if ("미국 대형주(S&P500)".equals(factorName)) {
            if (correlation >= 0.7) {
                adviceList.add("미국 시장 전반과 동행하는 안정적인 포트폴리오입니다. 초과 수익을 원한다면 개별 성장주나 섹터 ETF를 고려해보세요.");
            } else if (correlation >= 0.5) {
                adviceList.add("미국 대형주의 흐름을 따르고 있습니다. 장기적으로 안정적인 성장이 기대되나, 환율 리스크도 함께 고려해야 합니다.");
            }
        } else if ("달러 환율".equals(factorName)) {
            if (correlation <= -0.5) {
                adviceList.add("환율 상승 시(원화 가치 하락) 포트폴리오 가치가 하락하는 구조입니다. 달러 자산 비중을 늘려 환 헷지를 고려해보세요.");
            } else if (correlation >= 0.5) {
                adviceList.add("환율 상승 시 수혜를 보는 구조입니다(달러 자산 과다). 원화 자산 비중을 점검하세요.");
            }
        } else if ("한국 기준금리".equals(factorName)) {
            if (correlation >= 0.4) {
                adviceList.add("금리 인상기에 유리한 자산(현금, 변동금리 채권 등)이 포함되어 있습니다.");
            } else if (correlation <= -0.4) {
                adviceList.add("금리 인상 시 부정적 영향을 받는 구조입니다. 금리 리스크 관리가 필요합니다.");
            }
        } else if (factorName.contains("국채")) {
            if (correlation <= -0.5) {
                adviceList.add(factorName + " 금리 상승 시(채권 가격 하락) 포트폴리오가 타격을 입을 수 있습니다. 금리 인상기에 주의가 필요합니다.");
            }
        }
    }
}
