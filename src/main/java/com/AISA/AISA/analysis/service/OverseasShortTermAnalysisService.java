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
        MACD macd = calculateMACD(prices);

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
        String momentum = determineMomentum(rsi, macd);

        String volatility = determineVolatility(prices);

        String valuationSignal = rsi > RSI_OVERBOUGHT ? "Overbought" : rsi < RSI_OVERSOLD ? "Oversold" : "Fair";
        String attractiveness = determineAttractiveness(momentum, valuationSignal);

        // 5. Generate AI Explanation
        String aiPrompt = generateShortTermPrompt(stock, rsi, rsiSignal, macd, stochastic, trendScore, trendDirection,
                momentum, volatility, currentPriceStr);
        String aiExplanation = geminiService.generateAdvice(aiPrompt);
        List<String> explanations = parseExplanation(aiExplanation);

        ShortTermVerdict verdict = ShortTermVerdict.builder()
                .trendScore(trendScore)
                .trendDirection(trendDirection)
                .momentum(momentum)
                .volatility(volatility)
                .supportResistance(calculateSupportResistance(priceHistory))
                .technicalIndicators(TechnicalIndicators.builder()
                        .rsi(rsi)
                        .rsiSignal(rsiSignal)
                        .macd(macd)
                        .stochastic(stochastic)
                        .build())
                .valuationSignal(valuationSignal)
                .investmentAttractiveness(attractiveness)
                .action(determineAction(attractiveness))
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
        sb.append("2. **매매 타이밍**: 현재 시점의 진입/관망/청산 적합성을 판단하라.\n");
        sb.append("3. **리스크 포인트**: 주의할 기술적 리스크를 2~3가지 제시하라.\n");
        sb.append("4. **핵심 모니터링 지표**: 향후 추적해야 할 지표와 기준을 제시하라.\n\n");

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
            return new RsiResult(50.0, 50.0);

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
                Math.round(signal * 100.0) / 100.0);
    }

    @lombok.Getter
    @lombok.AllArgsConstructor
    private static class RsiResult {
        private final double rsi;
        private final double signal;
    }

    private MACD calculateMACD(List<BigDecimal> prices) {
        if (prices.size() < 35) {
            return MACD.builder()
                    .macdLine(BigDecimal.ZERO)
                    .signalLine(BigDecimal.ZERO)
                    .histogram(BigDecimal.ZERO)
                    .build();
        }

        // EMA12, EMA26 시계열 계산
        List<BigDecimal> ema12Values = calculateEMAValues(prices, 12);
        List<BigDecimal> ema26Values = calculateEMAValues(prices, 26);

        // MACD Line = EMA12 - EMA26 (EMA26 기준으로 정렬)
        int ema12Offset = ema12Values.size() - ema26Values.size(); // = 26 - 12 = 14
        List<BigDecimal> macdLineValues = new ArrayList<>();
        for (int i = 0; i < ema26Values.size(); i++) {
            macdLineValues.add(ema12Values.get(i + ema12Offset).subtract(ema26Values.get(i)));
        }

        // Signal Line = EMA(9) of MACD Line values
        List<BigDecimal> signalValues = calculateEMAValues(macdLineValues, 9);

        BigDecimal macdLine = macdLineValues.get(macdLineValues.size() - 1);
        BigDecimal signalLine = signalValues.get(signalValues.size() - 1);
        BigDecimal histogram = macdLine.subtract(signalLine);

        return MACD.builder()
                .macdLine(macdLine.setScale(2, RoundingMode.HALF_UP))
                .signalLine(signalLine.setScale(2, RoundingMode.HALF_UP))
                .histogram(histogram.setScale(2, RoundingMode.HALF_UP))
                .build();
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
            return new Stochastic(50, 50);
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

        return new Stochastic(
                latestSlowK.setScale(2, RoundingMode.HALF_UP).doubleValue(),
                latestSlowD.setScale(2, RoundingMode.HALF_UP).doubleValue());
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

    private String determineMomentum(double rsi, MACD macd) {
        if (rsi > 60 && macd.getHistogram().compareTo(BigDecimal.ZERO) > 0)
            return "강함";
        if (rsi < 40)
            return "약함";
        return "보통";
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

    private String determineAttractiveness(String momentum, String valSignal) {
        if ("Overbought".equals(valSignal)) return "Caution";
        if ("강함".equals(momentum) && !"Overbought".equals(valSignal)) return "Attractive";
        if ("Oversold".equals(valSignal) && !"약함".equals(momentum)) return "Attractive";
        return "Neutral";
    }

    private String determineAction(String attr) {
        if ("Attractive".equals(attr)) return "Buy";
        if ("Caution".equals(attr)) return "Reduce";
        return "Wait";
    }

    @Transactional
    public void clearShortTermReports() {
        long count = shortTermReportRepository.count();
        log.info("[ShortTerm] Clearing all overseas short-term reports. Current count: {}", count);
        shortTermReportRepository.truncateTable();
        log.info("[ShortTerm] Overseas short-term reports cleared. All records and IDs reset.");
    }
}
