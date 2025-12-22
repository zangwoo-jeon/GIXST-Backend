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
    private final GeminiService geminiService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Transactional(readOnly = true)
    public DiagnosisResultDto diagnosePortfolio(UUID portfolioId) {
        // 1. Setup Adaptive Time Horizon (Max 5 Years for Data Fetching)
        LocalDate endVal = LocalDate.now();
        LocalDate startVal = endVal.minusYears(5);
        String endDate = endVal.format(FORMATTER);
        String startDate = startVal.format(FORMATTER);

        // 2. Get Portfolio Daily Values (Virtual Asset)
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

        List<DiagnosisResultDto.FactorAnalysisResult> factorResults = new ArrayList<>();
        // Note: We no longer generate rule-based advice here. We use AI for that.
        List<String> rawSignals = new ArrayList<>(); // Temporary storage for rule-based signals to feed AI

        // 3. Define Factors to Analyze with Adaptive Windows
        // Factor 1: KOSPI (Domestic Market - 3 Years)
        analyzeFactorAdaptive(portfolioSeries, "INDEX", "KOSPI", "국내 시장(KOSPI)", 3, factorResults, rawSignals);

        // Factor 2: KOSDAQ (Domestic Growth - 3 Years)
        analyzeFactorAdaptive(portfolioSeries, "INDEX", "KOSDAQ", "국내 코스닥(KOSDAQ)", 3, factorResults, rawSignals);

        // Factor 3: NASDAQ (Global Tech Market - 5 Years)
        analyzeFactorAdaptive(portfolioSeries, "INDEX", "NASDAQ", "미국 기술주(NASDAQ)", 5, factorResults, rawSignals);

        // Factor 4: S&P 500 (US Broad Market - 5 Years)
        analyzeFactorAdaptive(portfolioSeries, "INDEX", "SP500", "미국 대형주(S&P500)", 5, factorResults, rawSignals);

        // Factor 5: USD Exchange Rate (Currency Risk - 5 Years)
        analyzeFactorAdaptive(portfolioSeries, "EXCHANGE", "FX@KRW", "달러 환율", 5, factorResults, rawSignals);

        // Factor 6: KR Base Rate (Monetary Policy - 5 Years)
        analyzeFactorAdaptive(portfolioSeries, "BASE_RATE", "BASE_RATE", "한국 기준금리", 5, factorResults, rawSignals);

        // Factor 7: KR Bonds (Interest Rate Risk - 5 Years)
        analyzeFactorAdaptive(portfolioSeries, "BOND", "KR_3Y", "한국 국채 3년(금리)", 5, factorResults, rawSignals);
        analyzeFactorAdaptive(portfolioSeries, "BOND", "KR_10Y", "한국 국채 10년(금리)", 5, factorResults, rawSignals);

        // Factor 8: US Bonds (Interest Rate Risk - 5 Years)
        analyzeFactorAdaptive(portfolioSeries, "BOND", "US_1Y", "미국 국채 1년(금리)", 5, factorResults, rawSignals);
        analyzeFactorAdaptive(portfolioSeries, "BOND", "US_10Y", "미국 10년물 국채(금리)", 5, factorResults, rawSignals);

        // 4. Generate AI Advice
        String prompt = createPrompt(factorResults, rawSignals);
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

    private String createPrompt(List<DiagnosisResultDto.FactorAnalysisResult> results, List<String> rawSignals) {
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 전문적인 PB(Private Banker)입니다. 아래의 포트폴리오 진단 데이터를 바탕으로 고객에게 3가지 핵심 조언을 해주세요.\n");
        sb.append("어조는 정중하고 전문적이어야 하며, 구체적인 수치보다는 인과관계와 방향성 위주로 설명해주세요.\n\n");

        sb.append("### ⚠️ 분석 가이드라인 (반드시 준수)\n");
        sb.append(
                "1. **국채 금리 해석**: 데이터에 포함된 '국채'는 가격이 아니라 '금리(Yield)'입니다. 따라서 포트폴리오(가격)와 상관계수가 음수(-)라면, 이는 금리 하락 시 채권 가격이 상승하여 이익을 얻는 '긍정적 헷지' 상태임을 의미합니다. 절대 '무관하다'고 하지 말고 '금리 위험에 대한 민감도'로 설명하세요.\n");
        sb.append(
                "2. **환율 쿠션 효과 (Currency Cushion)**: 해외 자산(예: 나스닥)의 상관계수가 낮게(0.3 미만) 나온다면, 이는 '환율 효과' 때문일 가능성이 높습니다. (달러 강세 시 원화 기준 가치 방어). 단, 이 효과는 양날의 검입니다. 'Risk-off 시에는 방어가 되지만, Risk-on 및 달러 약세 시에는 수익률을 깎아먹을 수 있음'을 반드시 경고하세요.\n");
        sb.append("3. **고급 지표(Advanced Metrics) 활용**: \n");
        sb.append("   - **베타(Beta)**: 1.0을 크게 상회하면 '시장 민감도가 높아 레버리지 위험이 있음'을 경고하세요.\n");
        sb.append("   - **하락장 상관계수(Downside Corr)**: 평소 상관계수가 낮아도 하락장 상관계수가 높다면 '위기 시 같이 무너지는 가짜 분산'임을 지적하세요.\n");
        sb.append("   - **수익 기여(Contribution)**: 상관계수는 낮지만 수익률(CAGR)이 높은 자산은 '숨겨진 효자 종목'으로 칭찬해주세요.\n\n");

        sb.append("### --- 진단 데이터 ---\n");
        for (DiagnosisResultDto.FactorAnalysisResult res : results) {
            sb.append(String.format("- %s: 상관계수 %.2f | 베타: %.2f | 하락장상관: %.2f | 변동성 %.2f%% | MDD %.2f%%\n",
                    res.getFactorName(), res.getCorrelation(), res.getBeta(), res.getDownsideCorrelation(),
                    res.getVolatility() * 100, res.getMdd() * 100));
            if (res.getScenarioAnalysis() != null && !"N/A".equals(res.getScenarioAnalysis())) {
                sb.append(String.format("  > 특이사항: %s\n", res.getScenarioAnalysis()));
            }
        }
        sb.append("\n### --- 감지된 신호 (Raw Signals) ---\n");
        for (String sig : rawSignals) {
            sb.append("- ").append(sig).append("\n");
        }

        sb.append("\n위 데이터를 종합하여, 고객 자산의 건전성을 평가하고 구체적인 행동 가이드를 제시해주세요. (서론 생략하고 본론만 작성)");
        return sb.toString();
    }

    private void analyzeFactorAdaptive(Map<LocalDate, Double> portfolioSeries, String factorType, String factorCode,
            String factorName, int pearsonYears,
            List<DiagnosisResultDto.FactorAnalysisResult> results, List<String> rawSignals) {
        try {
            // Data Fetching
            LocalDate endVal = LocalDate.now();
            LocalDate startVal = endVal.minusYears(5);
            String endDate = endVal.format(FORMATTER);
            String startDate = startVal.format(FORMATTER);

            Map<LocalDate, Double> factorSeries = analysisService.fetchAssetData(factorType, factorCode, startDate,
                    endDate);

            // 1. Adaptive Pearson Correlation (Sensitivity)
            LocalDate pearsonStart = endVal.minusYears(pearsonYears);
            String pearsonStartDate = pearsonStart.format(FORMATTER);

            CorrelationResultDto correlationResult = analysisService.calculateCorrelation(
                    portfolioSeries, "MyPortfolio", factorType, factorCode, pearsonStartDate, endDate, "AUTO");

            double correlation = correlationResult.getCoefficient();
            String sensitivity = determineSensitivity(correlation);

            // Check if Factor is Investable (Asset) vs Macro (Environment)
            boolean isInvestable = isInvestable(factorType);

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

            // 5. Scenario Analysis (Only for Investable Assets)
            StringBuilder scenarioBuilder = new StringBuilder();
            boolean hasScenario = false;
            String scenarioAnalysis = "N/A";

            if (isInvestable) {
                // Return Contribution Hint for AI (Simple Comparison)
                if (factorCAGR > 0.1 && factorCAGR > portfolioCAGR * 1.2) {
                    scenarioAnalysis = String.format("연평균 수익률(CAGR) %.1f%%로 포트폴리오(%.1f%%)를 상회 중 (수익 견인)",
                            factorCAGR * 100, portfolioCAGR * 100);
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
                if (hasScenario && !scenarioAnalysis.contains("수익 견인")) {
                    // If no contribution note yet, check uplift
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
                } else if (hasScenario && scenarioAnalysis.contains("수익 견인") && scenarioBuilder.length() > 0) {
                    scenarioAnalysis += " | " + scenarioBuilder.toString();
                }

                if (!hasScenario)
                    scenarioAnalysis = "N/A";
            }

            String description = interpretFactorResult(factorName, correlation, trend, factorVol, isInvestable);

            results.add(DiagnosisResultDto.FactorAnalysisResult.builder()
                    .factorName(factorName)
                    .correlation(Math.round(correlation * 100.0) / 100.0)
                    .sensitivity(sensitivity)
                    .description(description)
                    .volatility(isInvestable ? Math.round(factorVol * 100.0) / 100.0 : 0.0)
                    .mdd(isInvestable ? Math.round(factorMDD * 100.0) / 100.0 : 0.0)
                    .correlationTrend(trend)
                    .scenarioAnalysis(scenarioAnalysis)
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
                }
            }

        } catch (Exception e) {
            log.warn("Failed to analyze factor {}: {}", factorName, e.getMessage());
            results.add(DiagnosisResultDto.FactorAnalysisResult.builder()
                    .factorName(factorName)
                    .description("데이터 부족 또는 분석 실패")
                    .build());
        }
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
                rawSignals.add(String.format("[%s] 분산 투자 효과: %s (전체상관: %.2f)", factorName,
                        scenarioAnalysis.replace("5% 편입 시 ", ""), pearsonCorr));
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

    private String interpretFactorResult(String factorName, double correlation, String trend, double volatility,
            boolean isInvestable) {
        StringBuilder sb = new StringBuilder();
        sb.append("포트폴리오가 ").append(factorName).append("와(과) ");

        if (correlation >= 0.7)
            sb.append(isInvestable ? "매우 유사하게 움직입니다." : "강한 양의 상관관계를 보입니다.");
        else if (correlation >= 0.3)
            sb.append(isInvestable ? "어느 정도 유사하게 움직입니다." : "양의 상관관계를 보입니다.");
        else if (correlation > -0.3)
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
