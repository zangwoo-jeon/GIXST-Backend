package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.dto.CorrelationResultDto;
import com.AISA.AISA.analysis.dto.DiagnosisResultDto;
import com.AISA.AISA.analysis.dto.RollingCorrelationDto;
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
            // 1. Basic Correlation
            CorrelationResultDto correlationResult = analysisService.calculateCorrelation(
                    portfolioSeries, "MyPortfolio", factorType, factorCode, startDate, endDate, "AUTO");

            double correlation = correlationResult.getCoefficient();
            String sensitivity = determineSensitivity(correlation);

            // 2. Risk Metrics (Volatility & MDD)
            Map<LocalDate, Double> factorSeries = analysisService.fetchAssetData(factorType, factorCode, startDate,
                    endDate);
            double factorVol = analysisService.calculateVolatility(factorSeries);
            double factorMDD = analysisService.calculateMDD(factorSeries);

            // Portfolio Volatility (for Scenario Analysis)
            double portfolioVol = analysisService.calculateVolatility(portfolioSeries);

            // 3. Trend Analysis (Rolling Correlation Slope)
            RollingCorrelationDto rollingResult = analysisService.calculateRollingCorrelation(
                    portfolioSeries, "MyPortfolio", factorType, factorCode, startDate, endDate, 60); // 60-day window
            String trend = analysisService.calculateTrend(rollingResult.getRollingData());

            // 4. Scenario Analysis (Diversification Check)
            String scenarioAnalysis = "N/A";
            if (correlation < 0.3) { // Potential Diversifier
                double w1 = 0.95; // Portfolio Weight
                double w2 = 0.05; // Factor Weight
                double var1 = Math.pow(portfolioVol, 2);
                double var2 = Math.pow(factorVol, 2);

                double newVariance = analysisService.calculatePortfolioVariance(w1, var1, w2, var2, correlation);
                double newVol = Math.sqrt(newVariance);

                if (newVol < portfolioVol) {
                    double reduction = (portfolioVol - newVol) * 100;
                    scenarioAnalysis = String.format("5%% 비중 추가 시 포트폴리오 변동성 %.2f%%p 감소 예상", reduction);
                } else {
                    scenarioAnalysis = "분산 효과 미미함";
                }
            }

            String description = interpretFactorResult(factorName, correlation, trend, factorVol);

            results.add(DiagnosisResultDto.FactorAnalysisResult.builder()
                    .factorName(factorName)
                    .correlation(Math.round(correlation * 100.0) / 100.0)
                    .sensitivity(sensitivity)
                    .description(description)
                    .volatility(Math.round(factorVol * 100.0) / 100.0)
                    .mdd(Math.round(factorMDD * 100.0) / 100.0)
                    .correlationTrend(trend)
                    .scenarioAnalysis(scenarioAnalysis)
                    .build());

            generateAdvice(factorName, correlation, trend, factorVol, scenarioAnalysis, adviceList);

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

    private String interpretFactorResult(String factorName, double correlation, String trend, double volatility) {
        StringBuilder sb = new StringBuilder();
        sb.append("포트폴리오가 ").append(factorName).append("와(과) ");

        if (correlation >= 0.7)
            sb.append("매우 유사하게 움직입니다.");
        else if (correlation >= 0.3)
            sb.append("어느 정도 유사하게 움직입니다.");
        else if (correlation > -0.3)
            sb.append("무관하게 움직입니다 (분산 효과).");
        else
            sb.append("반대로 움직이는 경향이 있습니다 (헷지 효과).");

        sb.append(" 최근 상관관계는 ").append(trend).append(" 추세이며, ");
        if (volatility > 0.2)
            sb.append("해당 자산의 변동성이 높습니다.");
        else
            sb.append("해당 자산의 변동성은 안정적입니다.");

        return sb.toString();
    }

    private void generateAdvice(String factorName, double correlation, String trend, double volatility,
            String scenarioAnalysis, List<String> adviceList) {
        // 1. Diversification Advice (Scenario based)
        if (!"N/A".equals(scenarioAnalysis) && scenarioAnalysis.contains("감소 예상")) {
            adviceList.add(
                    String.format("[%s] %s (상관관계: %.2f, 추세: %s)", factorName, scenarioAnalysis, correlation, trend));
            return; // Prioritize diversification advice
        }

        // 2. Risk Management Advice
        if (correlation >= 0.7) {
            if ("증가".equals(trend)) {
                adviceList.add(String.format("[%s] 의존도가 심화되고 있습니다(상관관계 증가). 리스크 관리를 위해 비중 축소를 고려하세요.", factorName));
            } else {
                adviceList.add(String.format("[%s] 시장과 매우 유사하게 움직입니다. 초과 수익을 위해 개별 종목 발굴이 필요할 수 있습니다.", factorName));
            }
        } else if (correlation <= -0.5) {
            adviceList.add(String.format("[%s] 훌륭한 헷지 수단으로 작용하고 있습니다. 현재 비중을 유지하거나 시장 불안 시 비중 확대를 고려하세요.", factorName));
        }

        // 3. Volatility Warning
        if (volatility > 0.25 && correlation > 0.3) {
            adviceList.add(String.format("[%s] 변동성이 높은 자산입니다. 포트폴리오 전체 리스크를 높일 수 있으니 유의하세요.", factorName));
        }
    }

    @Transactional(readOnly = true)
    public com.AISA.AISA.analysis.dto.RollingCorrelationDto getPortfolioRollingCorrelation(UUID portfolioId,
            String benchmarkType, String benchmarkCode, String startDate, String endDate, int windowSize) {
        // 1. Get Portfolio Daily Values (Virtual Asset)
        BacktestResultDto backtestResult = backtestService.calculatePortfolioBacktest(portfolioId, startDate, endDate);
        Map<LocalDate, Double> portfolioSeries = new TreeMap<>();
        for (DailyPortfolioValueDto daily : backtestResult.getDailyValues()) {
            if (daily.getAdjustedValue() != null) {
                portfolioSeries.put(LocalDate.parse(daily.getDate(), FORMATTER),
                        daily.getAdjustedValue().doubleValue());
            } else {
                portfolioSeries.put(LocalDate.parse(daily.getDate(), FORMATTER), daily.getTotalValue().doubleValue());
            }
        }

        // 2. Calculate Rolling Correlation
        return analysisService.calculateRollingCorrelation(
                portfolioSeries, "MyPortfolio",
                benchmarkType, benchmarkCode,
                startDate, endDate, windowSize);
    }
}
