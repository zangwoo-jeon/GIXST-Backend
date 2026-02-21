package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.dto.OverseasMomentumAnalysisDto.*;
import com.AISA.AISA.analysis.dto.OverseasMomentumAnalysisDto;
import com.AISA.AISA.analysis.entity.OverseasShortTermReport;
import com.AISA.AISA.analysis.repository.OverseasShortTermReportRepository;
import com.AISA.AISA.kisOverseasStock.entity.OverseasStockDailyData;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockDailyDataRepository;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockRepository;
import com.AISA.AISA.kisOverseasStock.service.KisOverseasStockService;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.dto.StockPrice.StockPriceDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OverseasShortTermAnalysisService {

    private final KisOverseasStockRepository stockRepository;
    private final KisOverseasStockDailyDataRepository dailyDataRepository;
    private final KisOverseasStockService kisOverseasStockService;
    private final OverseasShortTermReportRepository shortTermReportRepository;
    private final GeminiService geminiService;

    // --- Refresh Thresholds ---
    private static final double PRICE_CHANGE_THRESHOLD = 0.02; // 2%
    private static final int RSI_OVERBOUGHT = 70;
    private static final int RSI_OVERSOLD = 30;

    @Transactional
    public OverseasMomentumAnalysisDto analyze(String stockCode, boolean forceRefresh) {
        Stock stock = stockRepository.findByStockCode(stockCode)
                .orElseThrow(() -> new IllegalArgumentException("Stock not found: " + stockCode));

        // 0. Get Latest Real-time Price
        StockPriceDto priceDto = kisOverseasStockService.getOverseasStockPrice(stockCode);
        String currentPriceStr = priceDto != null ? priceDto.getStockPrice() : "0.0";
        BigDecimal currentPrice = new BigDecimal(currentPriceStr);

        // 1. Get Historical Price Data (Last 300 days for MA calculations)
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(300);
        List<OverseasStockDailyData> priceHistory = dailyDataRepository.findByStockAndDateBetween(stock, startDate,
                endDate);

        if (priceHistory.size() < 30) {
            throw new IllegalStateException("Insufficient price history for technical analysis");
        }

        List<BigDecimal> prices = priceHistory.stream()
                .map(OverseasStockDailyData::getClosingPrice)
                .collect(Collectors.toList());

        // 2. Calculate Indicators
        RsiResult rsiResult = calculateRSI(prices, 14, 6);
        double rsi = rsiResult.rsi;
        double rsiSignal = rsiResult.signal;
        double prevRsi = rsiResult.prevRsi;
        MacdResult macdResult = calculateMACD(prices);
        MACD macd = macdResult.getMacd();

        // 3. Check if refresh is needed
        if (!forceRefresh) {
            OverseasMomentumAnalysisDto cached = loadSavedReport(stockCode);
            if (cached != null) {
                OverseasShortTermReport savedReport = shortTermReportRepository.findByStockCode(stockCode).orElse(null);
                if (savedReport != null && !shouldRefresh(savedReport, currentPrice, rsi, macd.getHistogram())) {
                    log.info("[ShortTerm] Using cached report for {} (no significant change detected)", stockCode);
                    return cached;
                }
            }
        }

        log.info("[ShortTerm] Generating new short-term analysis for {}", stockCode);

        // 4. Calculate remaining indicators
        Stochastic stochastic = calculateStochastic(priceHistory);
        int trendScore = calculateTrendScore(prices);
        String trendDirection = determineTrendDirection(prices);
        Momentum momentum = buildMomentum(rsi, prevRsi, macd.getMacdLine(), macd.getHistogram(),
                macdResult.getRecentHistograms(), stochastic.getK(), stochastic.getD());

        // 모멘텀 추세에 따른 trendScore 보정
        trendScore = adjustTrendScoreByMomentum(trendScore, momentum);

        String volatility = determineVolatility(prices);

        String valuationSignal = rsi > RSI_OVERBOUGHT ? "Overbought" : rsi < RSI_OVERSOLD ? "Oversold" : "Fair";
        String attractiveness = determineAttractiveness(momentum.getStrength(), valuationSignal);

        // 5. Generate AI Explanation
        String momentumDesc = String.format("%s (방향: %s, 강도: %s, 추세: %s, 신뢰도: %d%%)",
                momentum.getSummary(), momentum.getDirection(),
                momentum.getStrength(), momentum.getTrend(), momentum.getConfidence());
        String aiPrompt = generateShortTermPrompt(stock, rsi, rsiSignal, macd, stochastic, trendScore, trendDirection,
                momentumDesc, volatility, currentPriceStr);
        String aiExplanation = geminiService.generateAdvice(aiPrompt);
        List<String> explanations = parseExplanation(aiExplanation);

        // 6. Support/Resistance & Technical Indicators (action 판정에 필요)
        SupportResistance supportResistance = calculateSupportResistance(priceHistory);
        TechnicalIndicators technicalIndicators = TechnicalIndicators.builder()
                .rsi(rsi)
                .rsiSignal(rsiSignal)
                .rsiStatus(determineRsiStatus(rsi))
                .macd(MACD.builder()
                        .macdLine(macd.getMacdLine())
                        .signalLine(macd.getSignalLine())
                        .histogram(macd.getHistogram())
                        .status(determineMacdStatus(macd.getMacdLine(), macd.getHistogram()))
                        .build())
                .stochastic(Stochastic.builder()
                        .k(stochastic.getK())
                        .d(stochastic.getD())
                        .status(determineStochasticStatus(stochastic.getK(), stochastic.getD()))
                        .build())
                .build();

        // 7. Action — 3계층 의사결정 (Signal → Risk → Decision)
        BigDecimal currentPriceBd = new BigDecimal(currentPriceStr.replaceAll(",", ""));
        double resistanceProximity = calculateResistanceProximity(currentPriceBd, supportResistance.getResistanceLevel());
        String action = determineAction(attractiveness, trendScore, momentum,
                technicalIndicators, resistanceProximity, prices);

        ShortTermVerdict verdict = ShortTermVerdict.builder()
                .trendScore(trendScore)
                .trendDirection(trendDirection)
                .momentum(momentum)
                .volatility(volatility)
                .supportResistance(supportResistance)
                .technicalIndicators(technicalIndicators)
                .valuationSignal(valuationSignal)
                .investmentAttractiveness(attractiveness)
                .action(action)
                .reEntryCondition("RSI 50~60 조정 및 MACD 골든크로스 대기")
                .holdingHorizon("1~3개월")
                .build();

        OverseasMomentumAnalysisDto result = OverseasMomentumAnalysisDto.builder()
                .stockCode(stockCode)
                .stockName(stock.getStockName())
                .currentPrice(currentPriceStr)
                .shortTermVerdict(verdict)
                .explanation(explanations)
                .build();

        // 6. Save to DB
        saveReport(stockCode, stock.getStockName(), currentPrice, rsi, macd.getHistogram(), result);

        return result;
    }

    // ========================
    // Hybrid Refresh Logic
    // ========================

    private boolean shouldRefresh(OverseasShortTermReport saved, BigDecimal currentPrice, double currentRsi,
            BigDecimal currentMacdHistogram) {
        // 1. Daily refresh: if report was saved on a different day
        if (saved.getLastModifiedDate().toLocalDate().isBefore(LocalDate.now())) {
            log.info("[ShortTerm] Daily refresh triggered (saved: {}, now: {})",
                    saved.getLastModifiedDate().toLocalDate(), LocalDate.now());
            return true;
        }

        // 2. Price change >= 2%
        if (saved.getLastPrice() != null && saved.getLastPrice().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal priceChange = currentPrice.subtract(saved.getLastPrice()).abs()
                    .divide(saved.getLastPrice(), 4, RoundingMode.HALF_UP);
            if (priceChange.doubleValue() >= PRICE_CHANGE_THRESHOLD) {
                log.info("[ShortTerm] Price change refresh triggered: {}% (threshold: {}%)",
                        String.format("%.2f", priceChange.doubleValue() * 100), PRICE_CHANGE_THRESHOLD * 100);
                return true;
            }
        }

        // 3. RSI entering overbought/oversold zone
        Integer lastRsi = saved.getLastRsi();
        if (lastRsi != null) {
            boolean enteredOverbought = currentRsi > RSI_OVERBOUGHT && lastRsi <= RSI_OVERBOUGHT;
            boolean enteredOversold = currentRsi < RSI_OVERSOLD && lastRsi >= RSI_OVERSOLD;
            if (enteredOverbought || enteredOversold) {
                log.info("[ShortTerm] RSI extreme refresh triggered: {} -> {} ({})",
                        lastRsi, String.format("%.2f", currentRsi), enteredOverbought ? "Overbought" : "Oversold");
                return true;
            }
        }

        // 4. MACD Golden/Dead Cross
        BigDecimal lastHist = saved.getLastMacdHistogram();
        if (lastHist != null && currentMacdHistogram != null) {
            boolean goldenCross = currentMacdHistogram.compareTo(BigDecimal.ZERO) >= 0
                    && lastHist.compareTo(BigDecimal.ZERO) < 0;
            boolean deadCross = currentMacdHistogram.compareTo(BigDecimal.ZERO) <= 0
                    && lastHist.compareTo(BigDecimal.ZERO) > 0;
            if (goldenCross || deadCross) {
                log.info("[ShortTerm] MACD cross refresh triggered: {} -> {} ({})",
                        lastHist, currentMacdHistogram, goldenCross ? "Golden Cross" : "Dead Cross");
                return true;
            }
        }

        return false;
    }

    // ========================
    // DB Save / Load
    // ========================

    private void saveReport(String stockCode, String stockName, BigDecimal price, double rsi,
            BigDecimal macdHistogram, OverseasMomentumAnalysisDto dto) {
        try {
            OverseasShortTermReport report = shortTermReportRepository
                    .findByStockCode(stockCode)
                    .orElse(OverseasShortTermReport.builder().stockCode(stockCode).build());

            report.setStockName(stockName);
            report.setLastPrice(price);
            report.setLastRsi((int) Math.round(rsi));
            report.setLastMacdHistogram(macdHistogram);
            report.setFullReportJson(new ObjectMapper().writeValueAsString(dto));
            report.setLastModifiedDate(LocalDateTime.now());

            shortTermReportRepository.save(report);
            log.info("[ShortTerm] Report saved for {}", stockCode);
        } catch (Exception e) {
            log.error("[ShortTerm] Failed to save report for {}: {}", stockCode, e.getMessage());
        }
    }

    private OverseasMomentumAnalysisDto loadSavedReport(String stockCode) {
        return shortTermReportRepository
                .findByStockCode(stockCode)
                .map(report -> {
                    try {
                        return new ObjectMapper().readValue(report.getFullReportJson(),
                                OverseasMomentumAnalysisDto.class);
                    } catch (Exception e) {
                        log.warn("[ShortTerm] Failed to deserialize saved report for {}: {}", stockCode,
                                e.getMessage());
                        return null;
                    }
                }).orElse(null);
    }

    // ========================
    // AI Prompt Generation
    // ========================

    private String generateShortTermPrompt(Stock stock, double rsi, double rsiSignal, MACD macd, Stochastic stochastic,
            int trendScore, String trendDirection, String momentum, String volatility, String currentPrice) {
        StringBuilder sb = new StringBuilder();
        sb.append("너는 '단기 기술적 분석 해설가(Short-Term Technical Analyst)'이다.\n");
        sb.append("아래 기술적 지표 데이터를 기반으로, 해당 종목의 단기(1~3개월) 투자 전략을 구조적으로 해설하라.\n\n");

        sb.append("### [분석 대상]\n");
        sb.append(String.format("- 종목: %s (%s)\n", stock.getStockName(), stock.getStockCode()));
        sb.append(String.format("- 현재가: $%s\n", currentPrice));

        sb.append("\n### [기술적 지표 요약]\n");
        sb.append(String.format("- RSI(14): %.2f, Signal(6): %.2f (%s)\n", rsi, rsiSignal,
                rsi > RSI_OVERBOUGHT ? "과매수" : rsi < RSI_OVERSOLD ? "과매도" : "중립"));
        sb.append(String.format("- MACD Line: %s, Signal: %s, Histogram: %s\n",
                macd.getMacdLine(), macd.getSignalLine(), macd.getHistogram()));
        sb.append(String.format("- Stochastic: %%K=%.2f, %%D=%.2f\n", stochastic.getK(), stochastic.getD()));
        sb.append(String.format("- 추세 점수: %d/100, 방향: %s\n", trendScore, trendDirection));
        sb.append(String.format("- 모멘텀: %s, 변동성: %s\n", momentum, volatility));

        sb.append("\n### [분석 작성 가이드]\n");
        sb.append("1. **현재 추세 진단**: 기술적 데이터를 바탕으로 현재 단기 추세를 진단하라.\n");
        sb.append("2. **모멘텀 평가**: 모멘텀 둔화/가속 여부를 반드시 반영하라.\n");
        sb.append("3. **매매 타이밍**: 현재 시점의 진입/관망/청산 적합성을 판단하라.\n");
        sb.append("4. **리스크 포인트**: 주의할 기술적 리스크를 2~3가지 제시하라.\n");
        sb.append("5. **핵심 모니터링 지표**: 향후 추적해야 할 지표와 기준을 제시하라.\n\n");

        sb.append("[OUTPUT FORMAT]\n");
        sb.append("한글로 작성. 각 항목을 '|' 구분자로 연결하여 단일 문장으로 반환.\n");
        sb.append("예시: 현재 추세 진단 내용|매매 타이밍 판단|리스크 포인트|모니터링 지표\n");

        return sb.toString();
    }

    private List<String> parseExplanation(String aiText) {
        if (aiText == null || aiText.isBlank()) {
            return Collections.singletonList("AI 분석 결과를 생성하지 못했습니다.");
        }
        try {
            String[] parts = aiText.split("\\|");
            List<String> result = new ArrayList<>();
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
            return result.isEmpty()
                    ? Collections.singletonList(aiText.trim())
                    : result;
        } catch (Exception e) {
            return Collections.singletonList(aiText.trim());
        }
    }

    // ========================
    // Technical Indicators
    // ========================

    /**
     * RSI with Wilder's Smoothing (period=14, signalPeriod=6)
     * Step 1: First average = SMA of gains/losses over initial period
     * Step 2: Subsequent averages = (prevAvg * (period-1) + current) / period
     * Signal = SMA of RSI values over signalPeriod
     */
    private RsiResult calculateRSI(List<BigDecimal> prices, int period, int signalPeriod) {
        if (prices.size() <= period)
            return new RsiResult(50.0, 50.0, 50.0);

        // Calculate price changes
        List<BigDecimal> gains = new ArrayList<>();
        List<BigDecimal> losses = new ArrayList<>();
        for (int i = 1; i < prices.size(); i++) {
            BigDecimal diff = prices.get(i).subtract(prices.get(i - 1));
            if (diff.compareTo(BigDecimal.ZERO) > 0) {
                gains.add(diff);
                losses.add(BigDecimal.ZERO);
            } else {
                gains.add(BigDecimal.ZERO);
                losses.add(diff.abs());
            }
        }

        // Step 1: First SMA for initial period
        double avgGain = 0, avgLoss = 0;
        for (int i = 0; i < period; i++) {
            avgGain += gains.get(i).doubleValue();
            avgLoss += losses.get(i).doubleValue();
        }
        avgGain /= period;
        avgLoss /= period;

        // Step 2: Wilder's smoothing for remaining periods, collecting RSI values
        List<Double> rsiValues = new ArrayList<>();
        double rsi = avgLoss == 0 ? 100.0 : 100.0 - (100.0 / (1.0 + avgGain / avgLoss));
        rsiValues.add(rsi);

        for (int i = period; i < gains.size(); i++) {
            avgGain = (avgGain * (period - 1) + gains.get(i).doubleValue()) / period;
            avgLoss = (avgLoss * (period - 1) + losses.get(i).doubleValue()) / period;
            rsi = avgLoss == 0 ? 100.0 : 100.0 - (100.0 / (1.0 + avgGain / avgLoss));
            rsiValues.add(rsi);
        }

        // Latest RSI
        double latestRsi = rsiValues.get(rsiValues.size() - 1);
        double prevRsi = rsiValues.size() >= 2
                ? rsiValues.get(rsiValues.size() - 2)
                : latestRsi;

        // Signal = SMA of last signalPeriod RSI values
        double signal = latestRsi;
        if (rsiValues.size() >= signalPeriod) {
            double sum = 0;
            for (int i = rsiValues.size() - signalPeriod; i < rsiValues.size(); i++) {
                sum += rsiValues.get(i);
            }
            signal = sum / signalPeriod;
        }

        return new RsiResult(
                Math.round(latestRsi * 100.0) / 100.0,
                Math.round(signal * 100.0) / 100.0,
                Math.round(prevRsi * 100.0) / 100.0);
    }

    @lombok.Getter
    @lombok.AllArgsConstructor
    private static class RsiResult {
        private final double rsi;
        private final double signal;
        private final double prevRsi;
    }

    @lombok.Getter
    @lombok.AllArgsConstructor
    private static class MacdResult {
        private final MACD macd;
        private final List<BigDecimal> recentHistograms;
    }

    private MacdResult calculateMACD(List<BigDecimal> prices) {
        if (prices.size() < 35) {
            MACD macd = MACD.builder()
                    .macdLine(BigDecimal.ZERO)
                    .signalLine(BigDecimal.ZERO)
                    .histogram(BigDecimal.ZERO)
                    .build();
            return new MacdResult(macd, List.of());
        }

        // EMA12, EMA26 시계열 계산
        List<BigDecimal> ema12Values = calculateEMAValues(prices, 12);
        List<BigDecimal> ema26Values = calculateEMAValues(prices, 26);

        // MACD Line = EMA12 - EMA26 (EMA26 기준으로 정렬)
        int ema12Offset = ema12Values.size() - ema26Values.size();
        List<BigDecimal> macdLineValues = new ArrayList<>();
        for (int i = 0; i < ema26Values.size(); i++) {
            macdLineValues.add(ema12Values.get(i + ema12Offset).subtract(ema26Values.get(i)));
        }

        // Signal Line = EMA(9) of MACD Line values
        List<BigDecimal> signalValues = calculateEMAValues(macdLineValues, 9);

        // histogram 시계열 생성 (signalValues 기준으로 정렬)
        int macdOffset = macdLineValues.size() - signalValues.size();
        List<BigDecimal> histogramSeries = new ArrayList<>();
        for (int i = 0; i < signalValues.size(); i++) {
            histogramSeries.add(macdLineValues.get(i + macdOffset).subtract(signalValues.get(i)));
        }

        BigDecimal macdLine = macdLineValues.get(macdLineValues.size() - 1);
        BigDecimal signalLine = signalValues.get(signalValues.size() - 1);
        BigDecimal histogram = macdLine.subtract(signalLine);

        MACD macd = MACD.builder()
                .macdLine(macdLine.setScale(2, RoundingMode.HALF_UP))
                .signalLine(signalLine.setScale(2, RoundingMode.HALF_UP))
                .histogram(histogram.setScale(2, RoundingMode.HALF_UP))
                .build();

        // 최근 5일 histogram 반환
        int recentCount = Math.min(5, histogramSeries.size());
        List<BigDecimal> recentHistograms = new ArrayList<>(
                histogramSeries.subList(histogramSeries.size() - recentCount, histogramSeries.size()));

        return new MacdResult(macd, recentHistograms);
    }

    /**
     * EMA 시계열 반환. 초기값은 첫 period개의 SMA.
     */
    private List<BigDecimal> calculateEMAValues(List<BigDecimal> prices, int period) {
        List<BigDecimal> emaValues = new ArrayList<>();
        if (prices.size() < period) return emaValues;

        BigDecimal multiplier = BigDecimal.valueOf(2.0 / (period + 1));

        // 초기값: 첫 period개의 SMA
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            sum = sum.add(prices.get(i));
        }
        BigDecimal ema = sum.divide(BigDecimal.valueOf(period), 6, RoundingMode.HALF_UP);
        emaValues.add(ema);

        for (int i = period; i < prices.size(); i++) {
            ema = prices.get(i).subtract(ema).multiply(multiplier).add(ema);
            emaValues.add(ema);
        }
        return emaValues;
    }

    private BigDecimal calculateEMA(List<BigDecimal> prices, int period) {
        List<BigDecimal> values = calculateEMAValues(prices, period);
        return values.isEmpty() ? prices.get(prices.size() - 1) : values.get(values.size() - 1);
    }

    /**
     * Slow Stochastic Oscillator (5, 3, 3)
     * 1. Fast %K = (Close - Lowest Low over N) / (Highest High - Lowest Low) * 100
     * [N=5]
     * 2. Slow %K = SMA(Fast %K, SlowK period) [SlowK=3]
     * 3. Slow %D = SMA(Slow %K, SlowD period) [SlowD=3]
     */
    private Stochastic calculateStochastic(List<OverseasStockDailyData> history) {
        int kPeriod = 5; // Fast %K lookback period
        int slowK = 3; // Slow %K smoothing (SMA of Fast %K)
        int slowD = 3; // Slow %D smoothing (SMA of Slow %K)

        // Need enough data: kPeriod + slowK + slowD - 2
        int minRequired = kPeriod + slowK + slowD - 2;
        if (history.size() < minRequired) {
            return Stochastic.builder().k(50).d(50).build();
        }

        // Step 1: Calculate all Fast %K values
        List<BigDecimal> fastKValues = new ArrayList<>();
        for (int i = kPeriod - 1; i < history.size(); i++) {
            List<OverseasStockDailyData> window = history.subList(i - kPeriod + 1, i + 1);

            BigDecimal currentClose = window.get(window.size() - 1).getClosingPrice();
            BigDecimal lowestLow = window.stream()
                    .map(OverseasStockDailyData::getLowPrice)
                    .min(BigDecimal::compareTo).get();
            BigDecimal highestHigh = window.stream()
                    .map(OverseasStockDailyData::getHighPrice)
                    .max(BigDecimal::compareTo).get();

            BigDecimal range = highestHigh.subtract(lowestLow);
            if (range.compareTo(BigDecimal.ZERO) == 0) {
                fastKValues.add(BigDecimal.valueOf(50));
            } else {
                BigDecimal fk = currentClose.subtract(lowestLow)
                        .divide(range, 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                fastKValues.add(fk);
            }
        }

        // Step 2: Slow %K = SMA(Fast %K, slowK)
        List<BigDecimal> slowKValues = new ArrayList<>();
        for (int i = slowK - 1; i < fastKValues.size(); i++) {
            BigDecimal sum = BigDecimal.ZERO;
            for (int j = i - slowK + 1; j <= i; j++) {
                sum = sum.add(fastKValues.get(j));
            }
            slowKValues.add(sum.divide(BigDecimal.valueOf(slowK), 6, RoundingMode.HALF_UP));
        }

        // Step 3: Slow %D = SMA(Slow %K, slowD)
        BigDecimal latestSlowK = slowKValues.get(slowKValues.size() - 1);
        BigDecimal latestSlowD;
        if (slowKValues.size() >= slowD) {
            BigDecimal sum = BigDecimal.ZERO;
            for (int i = slowKValues.size() - slowD; i < slowKValues.size(); i++) {
                sum = sum.add(slowKValues.get(i));
            }
            latestSlowD = sum.divide(BigDecimal.valueOf(slowD), 6, RoundingMode.HALF_UP);
        } else {
            latestSlowD = latestSlowK;
        }

        return Stochastic.builder()
                .k(latestSlowK.setScale(2, RoundingMode.HALF_UP).doubleValue())
                .d(latestSlowD.setScale(2, RoundingMode.HALF_UP).doubleValue())
                .build();
    }

    private int calculateTrendScore(List<BigDecimal> prices) {
        int score = 0;
        BigDecimal current = prices.get(prices.size() - 1);

        // MA5 위에 있으면 +20
        if (prices.size() >= 5) {
            BigDecimal ma5 = calculateMA(prices, 5);
            if (current.compareTo(ma5) > 0) score += 20;
        }

        // MA20 위에 있으면 +20
        if (prices.size() >= 20) {
            BigDecimal ma20 = calculateMA(prices, 20);
            if (current.compareTo(ma20) > 0) score += 20;

            // MA5 > MA20 (골든크로스 구간) +20
            if (prices.size() >= 5) {
                BigDecimal ma5 = calculateMA(prices, 5);
                if (ma5.compareTo(ma20) > 0) score += 20;
            }
        }

        // MA60 위에 있으면 +20
        if (prices.size() >= 60) {
            BigDecimal ma60 = calculateMA(prices, 60);
            if (current.compareTo(ma60) > 0) score += 20;
        }

        // 최근 5일 수익률 양수면 +20
        if (prices.size() >= 5) {
            BigDecimal prev5 = prices.get(prices.size() - 5);
            if (current.compareTo(prev5) > 0) score += 20;
        }

        return Math.min(score, 100);
    }

    private BigDecimal calculateMA(List<BigDecimal> prices, int period) {
        return prices.subList(prices.size() - period, prices.size()).stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(period), 2, RoundingMode.HALF_UP);
    }

    private String determineTrendDirection(List<BigDecimal> prices) {
        if (prices.size() < 20) return "Sideways";

        BigDecimal ma5 = calculateMA(prices, 5);
        BigDecimal ma20 = calculateMA(prices, 20);

        if (ma5.compareTo(ma20) > 0) return "Bullish";
        if (ma5.compareTo(ma20) < 0) return "Bearish";
        return "Sideways";
    }

    private int adjustTrendScoreByMomentum(int score, Momentum momentum) {
        String trend = momentum.getTrend();
        int confidence = momentum.getConfidence();

        if ("둔화".equals(trend)) {
            int penalty = (int) (confidence * 0.11);
            score -= penalty;
        } else if ("가속".equals(trend)) {
            int bonus = (int) (confidence * 0.08);
            score += bonus;
        }

        return Math.max(0, Math.min(100, score));
    }

    private Momentum buildMomentum(double rsi, double prevRsi,
            BigDecimal macdLine, BigDecimal histogram, List<BigDecimal> recentHistograms,
            double stochK, double stochD) {
        String direction = determineMomentumDirection(rsi, macdLine, histogram);
        String strength = determineMomentumStrength(rsi, histogram);
        int[] signals = determineMomentumTrendSignals(recentHistograms, rsi, prevRsi, stochK, stochD);
        int accelSignals = signals[0];
        int decelSignals = signals[1];

        String trend;
        int confidence;
        if (decelSignals >= 2) {
            trend = "둔화";
            confidence = decelSignals >= 3 ? 90 : 75;
        } else if (accelSignals >= 2) {
            trend = "가속";
            confidence = accelSignals >= 3 ? 90 : 75;
        } else {
            trend = "유지";
            confidence = 50;
        }

        // 3축 교차 검증: 가속 중이면 strength 최소 "보통", 둔화 중이면 최대 "보통"
        if ("가속".equals(trend) && "약함".equals(strength)) {
            strength = "보통";
        } else if ("둔화".equals(trend) && "강함".equals(strength)) {
            strength = "보통";
        }

        String summary = buildMomentumSummary(direction, strength, trend);

        return Momentum.builder()
                .direction(direction)
                .strength(strength)
                .trend(trend)
                .confidence(confidence)
                .summary(summary)
                .build();
    }

    private String determineMomentumDirection(double rsi, BigDecimal macdLine, BigDecimal histogram) {
        boolean macdPositive = macdLine != null && macdLine.compareTo(BigDecimal.ZERO) > 0;
        boolean histPositive = histogram != null && histogram.compareTo(BigDecimal.ZERO) > 0;
        if (rsi > 50 && (macdPositive || histPositive)) return "상승";
        if (rsi < 50 && (!macdPositive || !histPositive)) return "하락";
        return "횡보";
    }

    private String determineMomentumStrength(double rsi, BigDecimal histogram) {
        double histAbs = histogram != null ? histogram.abs().doubleValue() : 0;
        boolean histPositive = histogram != null && histogram.compareTo(BigDecimal.ZERO) > 0;
        boolean histNegative = histogram != null && histogram.compareTo(BigDecimal.ZERO) < 0;

        // RSI 60% + histogram 크기 40% 결합 판정
        // 강함: histogram 강하고 RSI도 방향 확인
        if (histAbs >= 0.3 && ((rsi > 55 && histPositive) || (rsi < 45 && histNegative)))
            return "강함";
        // 보통: histogram 양수 + RSI 50 이상, 또는 histogram 음수 + RSI 50 이하
        if ((histPositive && rsi >= 50) || (histNegative && rsi <= 50))
            return "보통";
        // 약함: histogram과 RSI가 엇갈리거나 histogram이 0에 가까움
        if (histAbs < 0.1 || (rsi >= 45 && rsi <= 55 && histAbs < 0.3))
            return "약함";
        return "보통";
    }

    private int[] determineMomentumTrendSignals(List<BigDecimal> recentHistograms,
            double rsi, double prevRsi, double stochK, double stochD) {
        int accelSignals = 0;
        int decelSignals = 0;

        // 1. histogram 분석: slope + 부호 전환
        if (recentHistograms != null && recentHistograms.size() >= 2) {
            BigDecimal prev = recentHistograms.get(recentHistograms.size() - 2);
            BigDecimal curr = recentHistograms.get(recentHistograms.size() - 1);

            // 부호 전환 감지 (가중치 2): 양→음 or 음→양
            boolean signChanged = prev.signum() != curr.signum() && prev.signum() != 0 && curr.signum() != 0;
            if (signChanged) {
                if (curr.signum() < 0) decelSignals += 2;
                else accelSignals += 2;
            }

            // slope: 최근 3일 절대값 기울기
            if (recentHistograms.size() >= 3) {
                List<BigDecimal> last3 = recentHistograms.subList(recentHistograms.size() - 3, recentHistograms.size());
                BigDecimal absFirst = last3.get(0).abs();
                BigDecimal absLast = last3.get(2).abs();
                if (absLast.compareTo(absFirst) > 0) accelSignals++;
                else if (absLast.compareTo(absFirst) < 0) decelSignals++;
            }
        }

        // 2. RSI 변화: 현재 vs 이전
        if (rsi > prevRsi) accelSignals++;
        else if (rsi < prevRsi) decelSignals++;

        // 3. Stochastic: 극단 구간 교차
        if (stochK >= 80 && stochK < stochD) decelSignals++;       // 과매수 둔화 → 감속
        else if (stochK <= 20 && stochK > stochD) accelSignals++;  // 과매도 반등 → 가속
        else if (Math.abs(stochK - stochD) > 10) accelSignals++;

        return new int[]{accelSignals, decelSignals};
    }

    private String buildMomentumSummary(String direction, String strength, String trend) {
        String base = "강함".equals(strength) ? "강한 " : "약함".equals(strength) ? "약한 " : "";
        String dirStr = "상승".equals(direction) ? "상승" : "하락".equals(direction) ? "하락" : "횡보";
        String trendStr = "둔화".equals(trend) ? "이나 둔화 조짐" : "가속".equals(trend) ? ", 가속 중" : "";
        return base + dirStr + " 모멘텀" + trendStr;
    }

    private String determineVolatility(List<BigDecimal> prices) {
        // 최근 20일 일별 등락률의 평균으로 변동성 측정
        int period = Math.min(20, prices.size() - 1);
        if (period <= 0) return "보통";

        double sumAbsReturn = 0;
        int start = prices.size() - period - 1;
        for (int i = start + 1; i <= start + period; i++) {
            double ret = prices.get(i).subtract(prices.get(i - 1)).abs()
                    .divide(prices.get(i - 1), 6, RoundingMode.HALF_UP)
                    .doubleValue();
            sumAbsReturn += ret;
        }
        double avgDailyChange = sumAbsReturn / period;

        if (avgDailyChange >= 0.025) return "높음"; // 일평균 2.5% 이상
        if (avgDailyChange <= 0.010) return "낮음"; // 일평균 1.0% 이하
        return "보통";
    }

    private SupportResistance calculateSupportResistance(List<OverseasStockDailyData> history) {
        // 최근 20일 실제 고가/저가 기반 지지/저항
        int period = Math.min(20, history.size());
        List<OverseasStockDailyData> recent = history.subList(history.size() - period, history.size());

        BigDecimal supportLevel = recent.stream()
                .map(OverseasStockDailyData::getLowPrice)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal resistanceLevel = recent.stream()
                .map(OverseasStockDailyData::getHighPrice)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        return SupportResistance.builder()
                .supportLevel(supportLevel)
                .resistanceLevel(resistanceLevel)
                .build();
    }

    private String determineAttractiveness(String strength, String valSignal) {
        if ("Overbought".equals(valSignal)) return "Caution";
        if ("강함".equals(strength) && !"Overbought".equals(valSignal)) return "Attractive";
        if ("Oversold".equals(valSignal) && !"약함".equals(strength)) return "Attractive";
        return "Neutral";
    }

    // === Action 3계층 의사결정 엔진 ===

    private static final List<String> ACTION_LEVELS = List.of("Sell", "Reduce", "Wait", "Buy", "Strong Buy");
    private static final int MAX_DEMOTION = 2;
    private static final int MAX_PROMOTION = 1;

    private String determineAction(String attractiveness, int trendScore,
            OverseasMomentumAnalysisDto.Momentum momentum,
            TechnicalIndicators technicalIndicators,
            double resistanceProximity, List<BigDecimal> prices) {

        // 1. Signal Layer — attractiveness 기반 base action + trendScore 보정
        String base;
        if ("Attractive".equals(attractiveness)) base = "Buy";
        else if ("Caution".equals(attractiveness)) base = "Reduce";
        else base = "Wait";

        // trendScore ≥ 90 + strength가 약함이 아니면 base를 Buy로 승격
        if (trendScore >= 90 && !"약함".equals(momentum.getStrength()) && "Wait".equals(base)) {
            base = "Buy";
        }

        int baseIdx = ACTION_LEVELS.indexOf(base);

        // 2. Risk Layer — 강등
        int demotions = 0;

        if ("둔화".equals(momentum.getTrend()) && momentum.getConfidence() >= 75)
            demotions++;

        if (resistanceProximity >= 97.0)
            demotions++;

        // 과매수 + histogram 음수일 때만 강등 (histogram 양수면 강한 추세)
        if (technicalIndicators.getRsi() >= 75 && technicalIndicators.getStochastic().getK() >= 80
                && technicalIndicators.getMacd().getHistogram().signum() < 0)
            demotions++;

        boolean trendCollapse = isTrendCollapse(technicalIndicators, prices);
        int maxDemotion = trendCollapse ? 4 : MAX_DEMOTION;
        demotions = Math.min(demotions, maxDemotion);

        // 3. Promotion Layer — 승격 (비대칭: 최대 1단계)
        int promotions = 0;

        // 상승 가속일 때만 승격 (하락 가속은 승격 금지)
        if ("상승".equals(momentum.getDirection())
                && "가속".equals(momentum.getTrend()) && momentum.getConfidence() >= 75)
            promotions++;

        if (technicalIndicators.getRsi() <= 25 && technicalIndicators.getStochastic().getK() <= 20)
            promotions++;

        promotions = Math.min(promotions, MAX_PROMOTION);

        // 4. Decision Layer — 최종 계산
        int finalIdx = Math.max(0, Math.min(ACTION_LEVELS.size() - 1,
                baseIdx - demotions + promotions));

        // 5. 하한 안전장치: 추세 붕괴가 아니면 Sell까지 직행 불가 (Reduce가 최저)
        if (!trendCollapse && finalIdx < ACTION_LEVELS.indexOf("Reduce")) {
            finalIdx = ACTION_LEVELS.indexOf("Reduce");
        }

        // 6. trendScore 하한 보호: trendScore ≥ 80이면 최소 Wait 이상
        if (trendScore >= 80 && finalIdx < ACTION_LEVELS.indexOf("Wait")) {
            finalIdx = ACTION_LEVELS.indexOf("Wait");
        }

        return ACTION_LEVELS.get(finalIdx);
    }

    /**
     * 추세 붕괴 판정 (수치 기반)
     * 해외는 거래량 데이터 없으므로 3가지 조건 모두 충족 시 추세 붕괴
     * - MACD line < 0 (추세 하향)
     * - MACD histogram < 0 (하락 압력 지속 — false signal 방지)
     * - 현재가 < MA20 (20일선 하향 이탈)
     */
    private boolean isTrendCollapse(TechnicalIndicators indicators, List<BigDecimal> prices) {
        boolean macdNegative = indicators.getMacd().getMacdLine().signum() < 0;
        boolean histNegative = indicators.getMacd().getHistogram().signum() < 0;

        boolean belowMa20 = false;
        if (prices.size() >= 20) {
            BigDecimal ma20 = prices.subList(prices.size() - 20, prices.size()).stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(20), 4, RoundingMode.HALF_UP);
            belowMa20 = prices.get(prices.size() - 1).compareTo(ma20) < 0;
        }

        return macdNegative && histNegative && belowMa20;
    }

    private double calculateResistanceProximity(BigDecimal currentPrice, BigDecimal resistance) {
        if (resistance == null || resistance.compareTo(BigDecimal.ZERO) == 0) return 0.0;
        return currentPrice.divide(resistance, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();
    }

    private String determineRsiStatus(double rsi) {
        if (rsi >= 70) return "과매수";
        if (rsi <= 30) return "과매도";
        if (rsi > 50) return "상승 우세";
        if (rsi < 50) return "하락 우세";
        return "중립";
    }

    private String determineMacdStatus(BigDecimal macdLine, BigDecimal histogram) {
        if (macdLine == null || histogram == null) return "중립";
        boolean macdPositive = macdLine.compareTo(BigDecimal.ZERO) > 0;
        boolean histPositive = histogram.compareTo(BigDecimal.ZERO) > 0;
        if (macdPositive && histPositive) return "강한 상승";
        if (macdPositive && !histPositive) return "상승 추세 조정";
        if (!macdPositive && histPositive) return "하락 추세 반등";
        if (!macdPositive && !histPositive) return "강한 하락";
        return "중립";
    }

    private String determineStochasticStatus(double k, double d) {
        if (k >= 80 && k < d) return "과매수 둔화";
        if (k >= 80) return "과매수";
        if (k <= 20 && k > d) return "과매도 반등";
        if (k <= 20) return "과매도";
        if (k > d) return "상승 우세";
        if (k < d) return "하락 우세";
        return "중립";
    }

    @Transactional
    public void clearShortTermReports() {
        long count = shortTermReportRepository.count();
        log.info("[ShortTerm] Clearing all overseas short-term reports. Current count: {}", count);
        shortTermReportRepository.truncateTable();
        log.info("[ShortTerm] Overseas short-term reports cleared. All records and IDs reset.");
    }
}
