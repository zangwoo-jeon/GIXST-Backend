package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.dto.CorrelationResultDto;
import com.AISA.AISA.analysis.dto.DiagnosisResultDto;
import com.AISA.AISA.analysis.dto.RollingCorrelationDto;
import com.AISA.AISA.portfolio.backtest.dto.BacktestResultDto;
import com.AISA.AISA.portfolio.backtest.dto.DailyPortfolioValueDto;
import com.AISA.AISA.portfolio.backtest.service.BacktestService;
import com.AISA.AISA.portfolio.PortfolioStock.PortStockService;
import com.AISA.AISA.portfolio.PortfolioStock.dto.PortfolioReturnResponse;
import com.AISA.AISA.portfolio.PortfolioStock.dto.PortStockResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PortfolioDiagnosisService {

    private final BacktestService backtestService;
    private final AnalysisService analysisService;
    private final GeminiService geminiService;
    private final PortStockService portStockService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Transactional(readOnly = true)
    @Cacheable(value = "portfolioDiagnosis", key = "#portfolioId.toString()", sync = true)
    public DiagnosisResultDto diagnosePortfolio(UUID portfolioId) {
        // 1. Setup Adaptive Time Horizon (Max 5 Years for Data Fetching)
        LocalDate endVal = LocalDate.now();
        LocalDate startVal = endVal.minusYears(5);
        String endDate = endVal.format(FORMATTER);
        String startDate = startVal.format(FORMATTER);

        // 2. Get Portfolio Daily Values (Virtual Asset) & Composition
        BacktestResultDto backtestResult = backtestService.calculatePortfolioBacktest(portfolioId, startDate, endDate);
        PortfolioReturnResponse portfolioComposition = portStockService.getPortStocks(portfolioId);

        // Extract Stock Codes for Interpretation Layer
        Set<String> portfolioStockCodes = portfolioComposition.getPortStocks().stream()
                .map(PortStockResponse::getStockCode)
                .collect(Collectors.toSet());

        Map<LocalDate, Double> portfolioSeries = new TreeMap<>();
        for (DailyPortfolioValueDto daily : backtestResult.getDailyValues()) {
            if (daily.getAdjustedValue() != null) {
                portfolioSeries.put(LocalDate.parse(daily.getDate(), FORMATTER),
                        daily.getAdjustedValue().doubleValue());
            } else {
                portfolioSeries.put(LocalDate.parse(daily.getDate(), FORMATTER), daily.getTotalValue().doubleValue());
            }
        }

        List<DiagnosisResultDto.FactorAnalysisResult> factorResults = new ArrayList<>();
        // Note: We no longer generate rule-based advice here. We use AI for that.
        List<String> rawSignals = new ArrayList<>(); // Temporary storage for rule-based signals to feed AI

        // 3. Define Factors to Analyze with Adaptive Windows
        // Factor 1: KOSPI (Domestic Market - 3 Years)
        Map<LocalDate, Double> kospiSeries = analyzeFactorAdaptive(portfolioSeries, "INDEX", "KOSPI", "국내 시장(KOSPI)", 3,
                factorResults, rawSignals, portfolioStockCodes);

        // Factor 2: KOSDAQ (Domestic Growth - 3 Years)
        analyzeFactorAdaptive(portfolioSeries, "INDEX", "KOSDAQ", "국내 코스닥(KOSDAQ)", 3, factorResults, rawSignals,
                portfolioStockCodes);

        // Factor 3: NASDAQ (Global Tech Market - 5 Years)
        analyzeFactorAdaptiveWithControl(portfolioSeries, "INDEX", "NASDAQ", "미국 기술주(NASDAQ)", 5, factorResults,
                rawSignals, kospiSeries, "국내 시장", portfolioStockCodes);

        // Factor 4: S&P 500 (US Broad Market - 5 Years)
        analyzeFactorAdaptiveWithControl(portfolioSeries, "INDEX", "SP500", "미국 대형주(S&P500)", 5, factorResults,
                rawSignals, kospiSeries, "국내 시장", portfolioStockCodes);

        // Factor 5: USD Exchange Rate (Currency Risk - 5 Years)
        analyzeFactorAdaptive(portfolioSeries, "EXCHANGE", "FX@KRW", "달러 환율", 5, factorResults, rawSignals,
                portfolioStockCodes);

        // Factor 6: KR Base Rate (Monetary Policy - 5 Years)
        analyzeFactorAdaptive(portfolioSeries, "BASE_RATE", "BASE_RATE", "한국 기준금리", 5, factorResults, rawSignals,
                portfolioStockCodes);

        // Factor 7: KR Bonds (Interest Rate Risk - 5 Years)
        analyzeFactorAdaptive(portfolioSeries, "BOND", "KR_3Y", "한국 국채 3년(금리)", 5, factorResults, rawSignals,
                portfolioStockCodes);
        analyzeFactorAdaptive(portfolioSeries, "BOND", "KR_10Y", "한국 국채 10년(금리)", 5, factorResults, rawSignals,
                portfolioStockCodes);

        // Factor 8: US Bonds (Interest Rate Risk - 5 Years)
        analyzeFactorAdaptive(portfolioSeries, "BOND", "US_1Y", "미국 국채 1년(금리)", 5, factorResults, rawSignals,
                portfolioStockCodes);
        analyzeFactorAdaptive(portfolioSeries, "BOND", "US_10Y", "미국 10년물 국채(금리)", 5, factorResults, rawSignals,
                portfolioStockCodes);

        // 4. Generate AI Advice
        String prompt = createPrompt(factorResults, rawSignals, portfolioComposition);
        String aiAdvice = geminiService.generateAdvice(prompt);

        List<String> adviceList = new ArrayList<>();
        // Split AI response into lines or use as is. Assuming AI returns a formatted
        // text.
        // For better UI, we might want to split by newlines if it returns bullet
        // points.
        if (aiAdvice != null && !aiAdvice.isEmpty()) {
            String[] lines = aiAdvice.split("\n");
            for (String line : lines) {
                if (!line.trim().isEmpty()) {
                    adviceList.add(line.trim());
                }
            }
        }

        return DiagnosisResultDto.builder()
                .portfolioId(portfolioId.toString())
                .diagnosisDate(LocalDate.now().format(FORMATTER))
                .factorAnalysis(factorResults)
                .adviceList(adviceList)
                .build();
    }

    private String createPrompt(List<DiagnosisResultDto.FactorAnalysisResult> results, List<String> rawSignals,
            PortfolioReturnResponse portfolioComposition) {
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 '전문 투자 포트폴리오 분석가'입니다. 아래 데이터를 바탕으로 사용자의 포트폴리오를 냉철하고 객관적으로 분석해주세요.\n\n");

        sb.append("[포트폴리오 구성]\n");
        if (portfolioComposition != null && portfolioComposition.getPortStocks() != null) {
            BigDecimal totalValue = portfolioComposition.getTotalValue();
            for (PortStockResponse stock : portfolioComposition.getPortStocks()) {
                double weight = 0;
                if (totalValue != null && totalValue.compareTo(BigDecimal.ZERO) > 0 && stock.getTotalValue() != null) {
                    weight = stock.getTotalValue().divide(totalValue, 4, java.math.RoundingMode.HALF_UP).doubleValue()
                            * 100;
                }
                sb.append(String.format("- %s (%s): 비중 %.1f%%\n", stock.getStockName(), stock.getStockCode(), weight));
            }
        }
        sb.append("\n");

        sb.append("[작성 가이드]\n");
        sb.append("1. **어조**: 비유나 은유를 최대한 배제하고, 금융 전문가로서 담백하고 알기 쉽게 설명하세요.\n");
        sb.append("2. **구조**: \n");
        sb.append("   - **종합 평가**: 포트폴리오의 전반적인 성격을 요약 (안정형/공격형, 특정 자산 편중 여부 등)\n");
        sb.append("   - **장점**: 데이터에 기반한 긍정적인 요소 (예: 낮은 변동성, 하락장 방어력 등)\n");
        sb.append("   - **단점 및 위험 요인**: 주의가 필요한 요소 (예: 특정 자산군 쏠림, 벤치마크 대비 저조한 성과 등)\n");
        sb.append("   - **개선 제안**: 구체적인 보완 방법 제시 (예: 벤치마크 성과가 우수한 자산군 편입 고려 등)\n");
        sb.append("3. **분석 기준**: 제공된 '진단 데이터'와 '포트폴리오 구성'을 종합적으로 고려하세요.\n");
        sb.append("4. **용어 설명**: 일반인이 이해하기 어려운 전문 용어는 쉽게 풀어서 설명하세요.\n\n");

        sb.append("[진단 데이터]\n");
        for (DiagnosisResultDto.FactorAnalysisResult res : results) {
            sb.append(String.format("- %s: 상관계수 %.2f | 베타: %.2f | 하락장상관: %.2f | 변동성 %.2f%%\n",
                    res.getFactorName(), res.getCorrelation(), res.getBeta(), res.getDownsideCorrelation(),
                    res.getVolatility() * 100));
            if (res.getScenarioAnalysis() != null && !"N/A".equals(res.getScenarioAnalysis())) {
                sb.append(String.format("  > 특이사항: %s\n", res.getScenarioAnalysis()));
            }
        }
        sb.append("\n[기술적 시그널]\n");
        for (String sig : rawSignals) {
            sb.append("- ").append(sig).append("\n");
        }

        sb.append("\n위 분석 결과를 바탕으로 고객에게 전달할 포트폴리오 진단 리포트를 작성해주세요. (경어체 사용)\n");
        sb.append("주의: '포트폴리오 진단 리포트', '발신', '수신', '날짜' 등의 헤더 정보는 제외하고, 바로 '### 1. 종합 평가'부터 시작하세요.");
        return sb.toString();
    }

    private Map<LocalDate, Double> analyzeFactorAdaptive(Map<LocalDate, Double> portfolioSeries, String factorType,
            String factorCode,
            String factorName, int pearsonYears,
            List<DiagnosisResultDto.FactorAnalysisResult> results, List<String> rawSignals,
            Set<String> portfolioStocks) {
        return analyzeFactorAdaptiveWithControl(portfolioSeries, factorType, factorCode, factorName, pearsonYears,
                results, rawSignals, null, null, portfolioStocks);
    }

    private Map<LocalDate, Double> analyzeFactorAdaptiveWithControl(Map<LocalDate, Double> portfolioSeries,
            String factorType, String factorCode,
            String factorName, int pearsonYears,
            List<DiagnosisResultDto.FactorAnalysisResult> results, List<String> rawSignals,
            Map<LocalDate, Double> controlSeries, String controlName, Set<String> portfolioStocks) {

        Map<LocalDate, Double> factorSeries = null;
        try {
            // Data Fetching
            LocalDate endVal = LocalDate.now();
            LocalDate startVal = endVal.minusYears(5);
            String endDate = endVal.format(FORMATTER);
            String startDate = startVal.format(FORMATTER);

            factorSeries = analysisService.fetchAssetData(factorType, factorCode, startDate, endDate);

            // 1. Adaptive Pearson Correlation (Sensitivity)
            LocalDate pearsonStart = endVal.minusYears(pearsonYears);
            String pearsonStartDate = pearsonStart.format(FORMATTER);

            CorrelationResultDto correlationResult = analysisService.calculateCorrelation(
                    portfolioSeries, "MyPortfolio", factorType, factorCode, pearsonStartDate, endDate, "AUTO");

            double correlation = correlationResult.getCoefficient();
            String sensitivity = determinesSensitivity(correlation);

            // Check if Factor is Investable (Asset) vs Macro (Environment)
            boolean isInvestable = isInvestable(factorType);

            // Interpretation Layer: Check if the factor is actually in the portfolio
            boolean isHeldInPortfolio = false;
            // Simplified check: exact match of code or name contains (heuristic for
            // indices)
            // Ideally, we should check IsHeld for STOCK only, and maybe 'IsTracking' for
            // ETFs.
            // For now, if it's a stock in portfolio, set true.
            if (portfolioStocks != null && "STOCK".equals(factorType) && portfolioStocks.contains(factorCode)) {
                isHeldInPortfolio = true;
            }
            // For indices, if the user holds an ETF tracking it, we can't easily know
            // without mapping.
            // But usually indices are benchmarks. Assumptions:
            // - If it's an asset (Stock/ETF), and in portfolio -> Internal
            // - If not in portfolio -> External Benchmark

            // 2. Risk & Return Metrics (Only for Investable Assets & FX)
            double factorVol = 0.0;
            double factorMDD = 0.0;
            double factorCAGR = 0.0;
            double beta = 0.0;
            double downsideCorr = 0.0;

            if (isInvestable) {
                factorVol = analysisService.calculateVolatility(factorSeries);
                factorMDD = analysisService.calculateMDD(factorSeries);
                factorCAGR = analysisService.calculateCAGR(factorSeries);

                // Advanced Metrics
                beta = analysisService.calculateBeta(portfolioSeries, factorSeries);
                // Downside Threshold: -1.0% (Using log return -0.01 approx)
                downsideCorr = analysisService.calculateDownsideCorrelation(portfolioSeries, factorSeries, -0.01);
            }

            double portfolioVol = analysisService.calculateVolatility(portfolioSeries);
            double portfolioCAGR = analysisService.calculateCAGR(portfolioSeries);

            // 3. Multi-Window Rolling Analysis
            List<Integer> windows = List.of(120, 250, 500);
            Map<Integer, Double> rollingCorrs = analysisService.calculateMultiWindowRollingCorrelation(
                    portfolioSeries, factorSeries, windows);

            double corrShort = rollingCorrs.getOrDefault(120, 0.0);
            double corrMid = rollingCorrs.getOrDefault(250, 0.0);
            double corrLong = rollingCorrs.getOrDefault(500, 0.0);

            String trend = interpretMultiWindowTrend(corrShort, corrMid, corrLong);

            // 4. Partial Correlation Analysis (If Control Series Provided)
            Double partialCorrelation = null;
            String exposureType = null;

            if (controlSeries != null && isInvestable) { // Assuming control only for investable assets for now
                CorrelationResultDto partialResult = analysisService.calculatePartialCorrelation(
                        portfolioSeries, "Portfolio",
                        factorSeries, factorName,
                        controlSeries, controlName);

                partialCorrelation = partialResult.getCoefficient();

                // Exposure Classification Logic
                if (correlation > 0.3 && partialCorrelation < 0.2) {
                    exposureType = "Indirect (via " + controlName + ")";
                } else if (partialCorrelation > 0.2) {
                    exposureType = "Direct";
                } else {
                    exposureType = "Low";
                }
            }

            // 5. Scenario Analysis (Only for Investable Assets)
            StringBuilder scenarioBuilder = new StringBuilder();
            boolean hasScenario = false;
            String scenarioAnalysis = "N/A";

            if (isInvestable) {
                // Interpretation Layer Refinement:
                // If External Benchmark (not held) -> "Benchmark Outperformance"
                // If Internal Asset (held) -> "Driving Returns"

                String performanceDesc = isHeldInPortfolio ? "수익 견인" : "벤치마크";

                if (factorCAGR > 0.1 && factorCAGR > portfolioCAGR * 1.2) {
                    if (isHeldInPortfolio) {
                        scenarioAnalysis = String.format("연평균 수익률(CAGR) %.1f%%로 포트폴리오(%.1f%%)를 상회 중 (수익 견인)",
                                factorCAGR * 100, portfolioCAGR * 100);
                    } else {
                        scenarioAnalysis = String.format("연평균 수익률(CAGR) %.1f%%로 포트폴리오(%.1f%%) 대비 성과 우수 (벤치마크)",
                                factorCAGR * 100, portfolioCAGR * 100);
                    }
                    hasScenario = true;
                }

                // A. Diversification (Volatility)
                if (correlation < 0.3) {
                    double w1 = 0.95;
                    double w2 = 0.05;
                    double var1 = Math.pow(portfolioVol, 2);
                    double var2 = Math.pow(factorVol, 2);
                    double newVariance = analysisService.calculatePortfolioVariance(w1, var1, w2, var2, correlation);
                    double newVol = Math.sqrt(newVariance);
                    if (newVol < portfolioVol) {
                        if (hasScenario)
                            scenarioBuilder.append(" | ");
                        double reduction = (portfolioVol - newVol) * 100;
                        scenarioBuilder.append(String.format("5%% 편입 시 변동성 %.2f%%p 감소", reduction));
                        hasScenario = true;
                    }
                }
                if (hasScenario && !scenarioAnalysis.contains(performanceDesc)) { // Check if we already added
                                                                                  // performance note
                    // ... existing uplift logic ...
                    if (factorCAGR > portfolioCAGR) {
                        double simulatedReturn = (portfolioCAGR * 0.95) + (factorCAGR * 0.05);
                        double uplift = (simulatedReturn - portfolioCAGR) * 100;
                        if (uplift > 0.05) {
                            if (scenarioBuilder.length() > 0)
                                scenarioBuilder.append(", ");
                            scenarioBuilder.append(String.format("수익률 %.2f%%p 개선 예상", uplift));
                        }
                    }
                    if (scenarioBuilder.length() > 0)
                        scenarioAnalysis = scenarioBuilder.toString();
                } else if (hasScenario && scenarioAnalysis.contains(performanceDesc) && scenarioBuilder.length() > 0) {
                    scenarioAnalysis += " | " + scenarioBuilder.toString();
                }

                // Add Exposure Info to Scenario Analysis field for AI visibility
                if (exposureType != null) {
                    String exposureMsg = "";
                    String indirectTarget = "Indirect (via " + controlName + ")";
                    if (indirectTarget.equals(exposureType)) {
                        exposureMsg = String.format(" [진단] %s 경유 간접 노출 (Partial Corr: %.2f)", controlName,
                                partialCorrelation);
                    } else if ("Direct".equals(exposureType)) {
                        exposureMsg = String.format(" [진단] 독립적 직접 노출 (Partial Corr: %.2f)", partialCorrelation);
                    }

                    if (!exposureMsg.isEmpty()) {
                        if ("N/A".equals(scenarioAnalysis))
                            scenarioAnalysis = exposureMsg.trim();
                        else
                            scenarioAnalysis += " | " + exposureMsg;
                    }
                }

                if (!hasScenario && exposureType == null)
                    scenarioAnalysis = "N/A";
            }

            String description = interpretFactorResult(factorName, correlation, trend, factorVol, isInvestable);
            // Append Partial Correlation Hint to Description if relevant
            String indirectTargetForDesc = "Indirect (via " + controlName + ")";
            if (exposureType != null && indirectTargetForDesc.equals(exposureType)) {
                description += " (" + controlName + "를 통한 간접 영향)";
            }

            results.add(DiagnosisResultDto.FactorAnalysisResult.builder()
                    .factorName(factorName)
                    .correlation(Math.round(correlation * 100.0) / 100.0)
                    .sensitivity(sensitivity)
                    .description(description)
                    .volatility(isInvestable ? Math.round(factorVol * 100.0) / 100.0 : 0.0)
                    .mdd(isInvestable ? Math.round(factorMDD * 100.0) / 100.0 : 0.0)
                    .correlationTrend(trend)
                    .scenarioAnalysis(scenarioAnalysis) // Contains Partial Corr Info
                    .beta(Math.round(beta * 100.0) / 100.0)
                    .downsideCorrelation(Math.round(downsideCorr * 100.0) / 100.0)
                    .build());

            // Collect Raw Signals for AI
            if (!"BASE_RATE".equals(factorType)) {
                generateAdviceAdaptive(factorName, correlation, corrShort, corrLong, isInvestable, scenarioAnalysis,
                        rawSignals);
                // Add Advanced Signals
                if (isInvestable) {
                    if (beta > 1.2)
                        rawSignals.add(String.format("[%s] 베타 %.2f로 시장 민감도가 매우 높습니다(레버리지 효과 주의).", factorName, beta));
                    if (downsideCorr > 0.6)
                        rawSignals.add(String.format("[%s] 하락장 상관계수(%.2f)가 높아 위기 시 함께 하락할 위험이 큽니다.", factorName,
                                downsideCorr));
                    else if (downsideCorr < 0.0 && correlation > 0.3)
                        rawSignals.add(String.format("[%s] 평소엔 동조하지만 하락장 상관계수(%.2f)가 낮아 방어력이 우수합니다.", factorName,
                                downsideCorr));

                    // Add Partial Correlation Signal
                    if (partialCorrelation != null) {
                        String indirectTargetForSignal = "Indirect (via " + controlName + ")";
                        if (indirectTargetForSignal.equals(exposureType)) {
                            rawSignals.add(String.format(
                                    "[%s] 미국 시장과의 연관성은 주로 %s를 통한 간접적인 영향입니다(Partial Corr: %.2f). 독자적인 연동성은 낮습니다.",
                                    factorName, controlName, partialCorrelation));
                        } else if ("Direct".equals(exposureType)) {
                            rawSignals.add(String.format("[%s] %s와 무관하게 독자적으로 연동되는 경향이 있습니다(Partial Corr: %.2f).",
                                    factorName, controlName, partialCorrelation));
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.warn("Failed to analyze factor {}: {}", factorName, e.getMessage());
            results.add(DiagnosisResultDto.FactorAnalysisResult.builder()
                    .factorName(factorName)
                    .description("데이터 부족 또는 분석 실패")
                    .build());
        }
        return factorSeries;
    }

    private boolean isInvestable(String factorType) {
        return "INDEX".equalsIgnoreCase(factorType) || "STOCK".equalsIgnoreCase(factorType)
                || "EXCHANGE".equalsIgnoreCase(factorType);
    }

    private String interpretMultiWindowTrend(double shortTerm, double midTerm, double longTerm) {
        if (Math.abs(shortTerm - longTerm) > 0.4) {
            if (shortTerm > longTerm)
                return "최근 급격한 동조화 (일시적 쏠림)";
            else
                return "최근 탈동조화 (관계 약화)";
        }
        if (shortTerm > 0.5 && longTerm > 0.5)
            return "구조적 동조화 (안정적)";
        if (shortTerm < -0.3 && longTerm < -0.3)
            return "구조적 역상관 (헷지 유효)";
        return "특이 사항 없음";
    }

    private void generateAdviceAdaptive(String factorName, double pearsonCorr, double rollShort, double rollLong,
            boolean isInvestable, String scenarioAnalysis, List<String> rawSignals) {

        // 1. Diversification & Return Enhancement Advice (Only Investable)
        if (isInvestable && !"N/A".equals(scenarioAnalysis)) {
            if (scenarioAnalysis.contains("수익 견인")) {
                rawSignals.add(String.format("[%s] %s", factorName, scenarioAnalysis));
                return;
            }
            if (scenarioAnalysis.contains("변동성") && scenarioAnalysis.contains("감소")) {
                String effect = scenarioAnalysis;
                if (effect.contains("|"))
                    effect = effect.split("\\|")[0].trim(); // Take first part if multiple
                rawSignals.add(String.format("[%s] 분산 투자 효과: %s (전체상관: %.2f)", factorName,
                        effect.replace("5% 편입 시 ", ""), pearsonCorr));
                return;
            }
        }

        // 2. Correlation Insight (For All)
        if (pearsonCorr > 0.6) {
            if (isInvestable) {
                if (factorName.contains("환율")) {
                    rawSignals.add(String.format("[%s] 포트폴리오가 환율 상승(달러 강세) 수혜를 크게 입고 있습니다.", factorName));
                } else {
                    rawSignals.add(String.format("[%s] 시장 민감도가 높습니다. 초과 수익을 위해 개별 종목 선별(Alpha)이 중요합니다.", factorName));
                }
            } else {
                rawSignals.add(String.format("[%s] 해당 지표가 상승할 때 포트폴리오 수익률도 함께 상승하는 경향이 강합니다.", factorName));
            }
        } else if (pearsonCorr < -0.4) {
            if (isInvestable)
                rawSignals.add(String.format("[%s] 포트폴리오의 리스크를 상쇄(Hedge)하는 역할을 하고 있습니다.", factorName));
            else
                rawSignals.add(String.format("[%s] 해당 지표가 상승할 때 포트폴리오 수익률은 하락하는 경향이 있습니다(부담 요인).", factorName));
        }

        // 3. Regime Change Warnings
        if (Math.abs(rollShort - rollLong) > 0.5) {
            rawSignals
                    .add(String.format("[%s] 최근 %s와의 상관관계가 과거와 다르게 움직이고 있습니다. 시장 변화에 유의하세요.", factorName, factorName));
        }
    }

    private String determinesSensitivity(double correlation) {
        if (correlation >= 0.7)
            return "매우 높음 (양의 상관)";
        if (correlation >= 0.5)
            return "높음 (양의 상관)";
        if (correlation >= 0.2)
            return "보통 (양의 상관)"; // New Category
        if (correlation > -0.2)
            return "낮음 (중립)";
        if (correlation > -0.5)
            return "보통 (음의 상관)";
        if (correlation > -0.7)
            return "높음 (음의 상관)";
        return "매우 높음 (음의 상관)";
    }

    private String interpretFactorResult(String factorName, double correlation, String trend, double volatility,
            boolean isInvestable) {
        StringBuilder sb = new StringBuilder();
        sb.append("포트폴리오가 ").append(factorName).append("와(과) ");

        if (correlation >= 0.7)
            sb.append(isInvestable ? "매우 유사하게 움직입니다." : "강한 양의 상관관계를 보입니다.");
        else if (correlation >= 0.5)
            sb.append(isInvestable ? "유사하게 움직입니다 (높은 연관)." : "양의 상관관계를 보입니다.");
        else if (correlation >= 0.2)
            sb.append("어느 정도 연관성이 있습니다."); // Moderate
        else if (correlation > -0.2)
            sb.append("무관하게 움직입니다.");
        else
            sb.append(isInvestable ? "반대로 움직이는 경향이 있습니다 (헷지 효과)." : "음의 상관관계를 보입니다.");

        sb.append(" 최근 상관관계는 ").append(trend).append(" 추세이며, ");

        if (isInvestable) {
            if (volatility > 0.2)
                sb.append("해당 자산의 변동성이 높습니다.");
            else
                sb.append("해당 자산의 변동성은 안정적입니다.");
        } else {
            sb.append("지표의 방향성에 유의하세요.");
        }

        return sb.toString();
    }

    @Transactional(readOnly = true)
    public RollingCorrelationDto getPortfolioRollingCorrelation(UUID portfolioId,
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
