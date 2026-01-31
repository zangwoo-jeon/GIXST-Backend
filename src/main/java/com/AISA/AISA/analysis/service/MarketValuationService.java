package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.dto.MarketValuationDto;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockBalanceSheet;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialStatement;
import com.AISA.AISA.kisStock.Entity.stock.StockMarketCap;
import com.AISA.AISA.kisStock.enums.MarketType;
import com.AISA.AISA.kisStock.repository.StockBalanceSheetRepository;
import com.AISA.AISA.kisStock.repository.StockFinancialStatementRepository;
import com.AISA.AISA.kisStock.repository.StockMarketCapRepository;
import com.AISA.AISA.kisStock.repository.StockRepository;
import com.AISA.AISA.kisStock.repository.IndexDailyDataRepository;
import com.AISA.AISA.kisStock.kisService.KisMacroService;
import com.AISA.AISA.portfolio.macro.repository.MacroDailyDataRepository;
import com.AISA.AISA.portfolio.macro.Entity.MacroDailyData;
import com.AISA.AISA.portfolio.macro.dto.MacroIndicatorDto;
import com.AISA.AISA.kisStock.Entity.Index.IndexDailyData;
import com.AISA.AISA.kisStock.Entity.stock.MarketInvestorDaily;
import com.AISA.AISA.kisStock.enums.BondYield;
import com.AISA.AISA.kisStock.repository.MarketInvestorDailyRepository;
import com.AISA.AISA.analysis.dto.MarketValuationDto.*;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketValuationService {

    private final StockRepository stockRepository;
    private final StockMarketCapRepository stockMarketCapRepository;
    private final StockFinancialStatementRepository stockFinancialStatementRepository;
    private final StockBalanceSheetRepository stockBalanceSheetRepository;
    private final IndexDailyDataRepository indexDailyDataRepository;
    private final MacroDailyDataRepository macroDailyDataRepository;
    private final MarketInvestorDailyRepository marketInvestorDailyRepository;
    private final KisMacroService kisMacroService;
    private final GeminiService geminiService;

    private static final String STAT_CODE_CPI = "901Y001";
    private static final String ITEM_CODE_CPI = "0";
    private static final String STAT_CODE_BOND_YIELD = "KIS_BOND_YIELD";

    @Cacheable(value = "marketValuation", key = "#market")
    public MarketValuationDto calculateMarketValuation(MarketType market) {
        return performCalculation(market);
    }

    @CacheEvict(value = "marketValuation", key = "#market")
    public void evictMarketValuationCache(MarketType market) {
        log.info("Evicting market valuation cache for {}", market);
    }

    private MarketValuationDto performCalculation(MarketType market) {
        log.info("Calculating market valuation for {}", market);

        // 1. Fetch all stocks in the market (Deduplicated)
        List<Stock> allStocks = stockRepository.findByMarketName(market);
        Map<String, Stock> stockMap = allStocks.stream()
                .collect(Collectors.toMap(Stock::getStockCode, s -> s, (s1, s2) -> s1));
        List<Stock> stocks = new ArrayList<>(stockMap.values());
        List<String> stockCodes = stocks.stream().map(Stock::getStockCode).collect(Collectors.toList());
        Set<String> stockCodeSet = new java.util.HashSet<>(stockCodes);

        // 2. Fetch market caps (Deduplicated)
        List<StockMarketCap> allMarketCaps = stockMarketCapRepository.findByStockIn(stocks);
        Map<String, StockMarketCap> marketCapMap = allMarketCaps.stream()
                .filter(mc -> mc.getStock() != null)
                .collect(Collectors.toMap(mc -> mc.getStock().getStockCode(), mc -> mc, (mc1, mc2) -> mc1));
        List<StockMarketCap> marketCaps = new ArrayList<>(marketCapMap.values());

        BigDecimal rawTotalMarketCapUnits = marketCaps.stream()
                .map(smc -> smc.getMarketCap() != null ? smc.getMarketCap() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 3. Fetch all annual income statements and pick latest per stock
        List<StockFinancialStatement> allAnnualStatements = stockFinancialStatementRepository
                .findByStockCodeInAndDivCode(stockCodeSet, "0");
        Map<String, StockFinancialStatement> latestStatements = allAnnualStatements.stream()
                .filter(s -> stockCodeSet.contains(s.getStockCode()))
                .collect(Collectors.toMap(
                        StockFinancialStatement::getStockCode,
                        s -> s,
                        (s1, s2) -> s1.getStacYymm().compareTo(s2.getStacYymm()) >= 0 ? s1 : s2));

        // 4. Fetch all annual balance sheets and pick latest per stock
        List<StockBalanceSheet> allAnnualBalanceSheets = stockBalanceSheetRepository
                .findByStockCodeInAndDivCode(stockCodeSet, "0");
        Map<String, StockBalanceSheet> latestBalanceSheets = allAnnualBalanceSheets.stream()
                .filter(b -> stockCodeSet.contains(b.getStockCode()))
                .collect(Collectors.toMap(
                        StockBalanceSheet::getStockCode,
                        b -> b,
                        (b1, b2) -> b1.getStacYymm().compareTo(b2.getStacYymm()) >= 0 ? b1 : b2));

        // 5. Intersection Logic: Only include stocks that have ALL required data points
        // This ensures the Numerator (Market Cap) and Denominator (Earnings/Capital)
        // use the same pool of stocks.
        Set<String> validStockCodes = latestStatements.keySet().stream()
                .filter(latestBalanceSheets::containsKey)
                .filter(code -> marketCaps.stream().anyMatch(mc -> mc.getStock().getStockCode().equals(code)))
                .collect(Collectors.toSet());

        BigDecimal filteredMarketCapUnits = marketCaps.stream()
                .filter(mc -> validStockCodes.contains(mc.getStock().getStockCode()))
                .map(smc -> smc.getMarketCap() != null ? smc.getMarketCap() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal filteredNetIncomeUnits = latestStatements.entrySet().stream()
                .filter(e -> validStockCodes.contains(e.getKey()))
                .map(e -> e.getValue().getNetIncome() != null ? e.getValue().getNetIncome() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal filteredCapitalUnits = latestBalanceSheets.entrySet().stream()
                .filter(e -> validStockCodes.contains(e.getKey()))
                .map(e -> e.getValue().getTotalCapital() != null ? e.getValue().getTotalCapital() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 6. Calculate Metrics with consistent data pool
        BigDecimal totalMarketCapValue = filteredMarketCapUnits.multiply(new BigDecimal("100000000"));
        BigDecimal totalNetIncomeValue = filteredNetIncomeUnits.multiply(new BigDecimal("100000000"));
        BigDecimal totalCapitalValue = filteredCapitalUnits.multiply(new BigDecimal("100000000"));

        BigDecimal per = null;
        if (totalNetIncomeValue.compareTo(BigDecimal.ZERO) > 0) {
            per = totalMarketCapValue.divide(totalNetIncomeValue, 2, RoundingMode.HALF_UP);
        }

        BigDecimal pbr = null;
        if (totalCapitalValue.compareTo(BigDecimal.ZERO) > 0) {
            pbr = totalMarketCapValue.divide(totalCapitalValue, 2, RoundingMode.HALF_UP);
        }

        // 6. CAPE Calculation
        List<MarketValuationDto.TimeSeriesPoint> timeSeries = new ArrayList<>();
        BigDecimal currentCape = null;
        BigDecimal dataCoverage = BigDecimal.ZERO;
        BigDecimal bondYield = null;
        BigDecimal yieldGap = null;

        try {
            // Fetch Bond Yield (10Y Gov) from KIS
            bondYield = kisMacroService.getLatestBondYield(BondYield.KR_10Y);

            // Fetch CPI Map
            Map<Integer, BigDecimal> cpiMap = getAnnualCpiMap();
            int currentYear = LocalDate.now().getYear();
            BigDecimal currentCpi = cpiMap.getOrDefault(currentYear,
                    cpiMap.values().stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ONE));

            // Fetch all historical earnings for current stocks (Deduplicated)
            List<StockFinancialStatement> rawHistoricalEarnings = stockFinancialStatementRepository
                    .findByStockCodeInAndDivCode(stockCodeSet, "0");
            List<StockFinancialStatement> historicalEarnings = new ArrayList<>(
                    rawHistoricalEarnings.stream()
                            .collect(Collectors.toMap(
                                    s -> s.getStockCode() + s.getStacYymm(),
                                    s -> s,
                                    (s1, s2) -> s1))
                            .values());

            // 10-year adjusted earnings sums
            Map<Integer, BigDecimal> annualAdjustedSums = new HashMap<>();
            Set<String> stocksWithFullHistory = new HashSet<>();
            Map<String, Set<Integer>> stockYearsData = new HashMap<>();

            for (StockFinancialStatement s : historicalEarnings) {
                int year = Integer.parseInt(s.getStacYymm().substring(0, 4));
                if (year >= currentYear - 10 && year < currentYear) {
                    BigDecimal cpiPast = cpiMap.getOrDefault(year, currentCpi);
                    BigDecimal adjEarnings = s.getNetIncome().multiply(new BigDecimal("100000000"))
                            .multiply(currentCpi).divide(cpiPast, 0, RoundingMode.HALF_UP);

                    annualAdjustedSums.merge(year, adjEarnings, BigDecimal::add);
                    stockYearsData.computeIfAbsent(s.getStockCode(), k -> new HashSet<>()).add(year);
                }
            }

            // Calculate coverage
            for (String code : stockCodeSet) {
                if (stockYearsData.getOrDefault(code, Collections.emptySet()).size() >= 8) { // 8+ years as proxy for
                                                                                             // "full"
                    stocksWithFullHistory.add(code);
                }
            }

            BigDecimal capWithHistory = marketCaps.stream()
                    .filter(mc -> stocksWithFullHistory.contains(mc.getStock().getStockCode()))
                    .map(mc -> mc.getMarketCap() != null ? mc.getMarketCap() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (rawTotalMarketCapUnits.compareTo(BigDecimal.ZERO) > 0) {
                dataCoverage = capWithHistory.divide(rawTotalMarketCapUnits, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
            }

            // Stats
            List<BigDecimal> sortedAnnualSums = annualAdjustedSums.values().stream().sorted()
                    .collect(Collectors.toList());
            if (!sortedAnnualSums.isEmpty()) {
                BigDecimal avgEarnings10Y = sortedAnnualSums.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(new BigDecimal(sortedAnnualSums.size()), 0, RoundingMode.HALF_UP);

                if (avgEarnings10Y.compareTo(BigDecimal.ZERO) > 0) {
                    // Critical Fix: Numerator for CAPE must be the sum of Market Cap of ONLY the
                    // stocks with full history
                    BigDecimal totalMarketCapForCape = marketCaps.stream()
                            .filter(mc -> stocksWithFullHistory.contains(mc.getStock().getStockCode()))
                            .map(mc -> mc.getMarketCap() != null ? mc.getMarketCap() : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .multiply(new BigDecimal("100000000"));

                    currentCape = totalMarketCapForCape.divide(avgEarnings10Y, 2, RoundingMode.HALF_UP);
                }

                // Median
                int mid = sortedAnnualSums.size() / 2;
                BigDecimal medianEarnings = sortedAnnualSums.size() % 2 == 0
                        ? sortedAnnualSums.get(mid).add(sortedAnnualSums.get(mid - 1)).divide(new BigDecimal("2"), 0,
                                RoundingMode.HALF_UP)
                        : sortedAnnualSums.get(mid);
                if (medianEarnings.compareTo(BigDecimal.ZERO) > 0) {
                    // This medianCape calculation is redundant because we calculate it from
                    // timeSeries later
                    // But we keep it in the score details if needed.
                    // Actually, let's just use the one from timeSeries.
                }

                // Yield Gap Calculation
                if (currentCape != null && currentCape.compareTo(BigDecimal.ZERO) > 0 && bondYield != null) {
                    BigDecimal earningsYield = BigDecimal.ONE.divide(currentCape, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal(100));
                    yieldGap = earningsYield.subtract(bondYield).setScale(2, RoundingMode.HALF_UP);
                }

                // Time Series Points (Monthly)
                timeSeries = generateTimeSeries(market, currentCape, avgEarnings10Y, bondYield);

                MarketValuationDto.HistoricalStats historicalStats = null;
                MarketValuationDto.ScoreDetails scoreDetails = null;
                BigDecimal totalScore = BigDecimal.ZERO;
                String grade = "UNKNOWN";

                if (!timeSeries.isEmpty()) {
                    List<BigDecimal> capes = timeSeries.stream()
                            .map(MarketValuationDto.TimeSeriesPoint::getCape)
                            .filter(Objects::nonNull)
                            .sorted()
                            .collect(Collectors.toList());

                    if (!capes.isEmpty()) {
                        // Chronological capes for 3Y analysis
                        List<BigDecimal> chronologicalCapes = timeSeries.stream()
                                .map(MarketValuationDto.TimeSeriesPoint::getCape)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toList());

                        BigDecimal low = capes.get(0);
                        BigDecimal high = capes.get(capes.size() - 1);
                        BigDecimal avg = capes.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                                .divide(new BigDecimal(capes.size()), 4, RoundingMode.HALF_UP);
                        BigDecimal median = capes.get(capes.size() / 2);

                        historicalStats = MarketValuationDto.HistoricalStats.builder()
                                .tenYearLow(low)
                                .tenYearHigh(high)
                                .tenYearAvg(avg.setScale(2, RoundingMode.HALF_UP))
                                .tenYearMedian(median)
                                .build();

                        // Statistical Scoring (Phase 3 - Precision Tuning)
                        scoreDetails = calculateScoresRefined(currentCape, yieldGap, capes, chronologicalCapes, avg,
                                median, timeSeries);

                        BigDecimal baseScore = scoreDetails.getDistributionPercentile().multiply(new BigDecimal("0.4")) // Dist
                                                                                                                        // (40%)
                                .add(scoreDetails.getYieldGapScore())
                                .add(scoreDetails.getDeviationScore());

                        // Range Position Bonus: If range position is extreme (>90%), ensure score
                        // reflects it
                        BigDecimal rangeBonus = scoreDetails.getCapeRangePosition().subtract(new BigDecimal("50"))
                                .multiply(new BigDecimal("0.2")).max(BigDecimal.ZERO);

                        totalScore = baseScore.add(rangeBonus);
                        if (Boolean.TRUE.equals(scoreDetails.getYieldGapInversion())) {
                            totalScore = totalScore.add(new BigDecimal("25"));
                        }

                        // Phase 7.5: Scoring Cap (Max 90.0 without Sentiment Signal)
                        totalScore = totalScore.min(new BigDecimal("90.0"));

                        // 7. Supply & Demand Integration (Phase 7)
                        InvestorTrendInfo investorTrend = null;
                        SentimentSignal finalSentiment = SentimentSignal.NEUTRAL;
                        try {
                            investorTrend = calculateInvestorTrend(market);
                            if (investorTrend != null) {
                                finalSentiment = determineHysteresisSentiment(market, totalScore, investorTrend);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to calculate investor trend: {}", e.getMessage());
                        }

                        // Final Score Boost (Phase 7.6): Only reach 100 if Sentiment matches valuation
                        // danger
                        // Final Score Boost (Phase 7.7): Only reach 100 if SENTIMENT + VALUATION +
                        // MACRO (Inversion) all align
                        if (totalScore.doubleValue() >= 90.0
                                && scoreDetails.getCapeRangePosition().doubleValue() >= 95.0
                                && finalSentiment == SentimentSignal.INDIVIDUAL_FOMO
                                && Boolean.TRUE.equals(scoreDetails.getYieldGapInversion())) {
                            totalScore = new BigDecimal("100.0");
                        } else {
                            totalScore = totalScore.setScale(1, RoundingMode.HALF_UP);
                        }

                        // Inject final sentiment
                        scoreDetails = MarketValuationDto.ScoreDetails.builder()
                                .capeRangePosition(scoreDetails.getCapeRangePosition())
                                .distributionPercentile(scoreDetails.getDistributionPercentile())
                                .yieldGapScore(scoreDetails.getYieldGapScore())
                                .deviationScore(scoreDetails.getDeviationScore())
                                .yieldGapInversion(scoreDetails.getYieldGapInversion())
                                .dataDistortionWarning(scoreDetails.getDataDistortionWarning())
                                .sentimentSignal(finalSentiment)
                                .build();

                        grade = determineGrade(totalScore);
                        String strategyText = determineStrategy(totalScore);
                        if (finalSentiment != SentimentSignal.NEUTRAL) {
                            strategyText += " " + getSentimentContext(finalSentiment, investorTrend);
                        } else if (investorTrend != null && totalScore.doubleValue() > 80) {
                            SentimentSignal rawSig = determineRawSentiment(totalScore, investorTrend);
                            if (rawSig == SentimentSignal.INDIVIDUAL_FOMO) {
                                strategyText += " [수급 경보] 지수가 과열권인 가운데 최근 개인의 매수세가 가속화되고 있어 단기 변동성 확대가 우려됩니다.";
                            }
                        }

                        // Set bounds in time series
                        for (MarketValuationDto.TimeSeriesPoint p : timeSeries) {
                            p.setLowerBound(low);
                            p.setUpperBound(high);
                        }

                        MarketValuationDto finalDto = MarketValuationDto.builder()
                                .market(market)
                                .marketDescription(market.getDescription())
                                .totalScore(totalScore)
                                .grade(grade)
                                .strategy(strategyText)
                                .valuation(MarketValuationDto.ValuationInfo.builder()
                                        .per(per)
                                        .pbr(pbr)
                                        .cape(currentCape)
                                        .yieldGap(yieldGap)
                                        .bondYield(bondYield)
                                        .build())
                                .scoreDetails(scoreDetails)
                                .investorTrend(investorTrend)
                                .metadata(MarketValuationDto.MetadataInfo.builder()
                                        .stockCount(validStockCodes.size())
                                        .totalMarketCap(formatLargeNumber(totalMarketCapValue))
                                        .dataCoverage(dataCoverage)
                                        .updatedAt(java.time.OffsetDateTime.now().toString())
                                        .historicalStats(historicalStats)
                                        .build())
                                .timeSeries(timeSeries)
                                .build();

                        // 9. Final AI Strategy Generation (Phase 7.8)
                        String aiStrategy = geminiService.generateMarketStrategy(finalDto);
                        if (aiStrategy != null && !aiStrategy.trim().isEmpty()) {
                            return finalDto.toBuilder().strategy(aiStrategy).build();
                        }
                        return finalDto;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error calculating market valuation for {}: {}", market, e.getMessage(), e);
        }

        return MarketValuationDto.builder().market(market).marketDescription(market.getDescription() + " (데이터 부족)")
                .build();
    }

    private InvestorTrendInfo calculateInvestorTrend(MarketType market) {
        String marketCode = market == MarketType.KOSPI ? "0001" : "1001";
        if (market != MarketType.KOSPI && market != MarketType.KOSDAQ)
            return null;

        List<MarketInvestorDaily> history = marketInvestorDailyRepository
                .findTop30ByMarketCodeOrderByDateDesc(marketCode);
        if (history.size() < 20)
            return null;

        return processTrend(history.subList(0, 20));
    }

    private InvestorTrendInfo processTrend(List<MarketInvestorDaily> subHistory) {
        long individual5d = 0, foreign5d = 0, institutional5d = 0;
        long foreign20d = 0, institutional20d = 0;

        for (int i = 0; i < 5; i++) {
            individual5d += subHistory.get(i).getPersonalNetBuy().longValue();
            foreign5d += subHistory.get(i).getForeignerNetBuy().longValue();
            institutional5d += subHistory.get(i).getInstitutionNetBuy().longValue();
        }

        for (int i = 0; i < 20; i++) {
            foreign20d += subHistory.get(i).getForeignerNetBuy().longValue();
            institutional20d += subHistory.get(i).getInstitutionNetBuy().longValue();
        }

        double foreignRS = (foreign20d == 0) ? 1.0 : (double) (foreign5d * 4) / foreign20d;
        double instRS = (institutional20d == 0) ? 1.0 : (double) (institutional5d * 4) / institutional20d;

        List<Long> individualDays = subHistory.stream().map(m -> m.getPersonalNetBuy().longValue())
                .collect(Collectors.toList());
        List<Long> foreignDays = subHistory.stream().map(m -> m.getForeignerNetBuy().longValue())
                .collect(Collectors.toList());
        List<Long> institutionalDays = subHistory.stream().map(m -> m.getInstitutionNetBuy().longValue())
                .collect(Collectors.toList());

        return InvestorTrendInfo.builder()
                .individualNet5d(individual5d)
                .foreignNet5d(foreign5d)
                .institutionalNet5d(institutional5d)
                .foreignRelativeStrength(BigDecimal.valueOf(foreignRS).setScale(2, RoundingMode.HALF_UP).doubleValue())
                .institutionalRelativeStrength(
                        BigDecimal.valueOf(instRS).setScale(2, RoundingMode.HALF_UP).doubleValue())
                .individualTrend(calculateDirection(individualDays))
                .foreignTrend(calculateDirection(foreignDays))
                .institutionalTrend(calculateDirection(institutionalDays))
                .build();
    }

    private TrendDirection calculateDirection(List<Long> values) {
        if (values.size() < 5)
            return TrendDirection.NEUTRAL;
        long currentAvg = (values.get(0) + values.get(1) + values.get(2)) / 3;
        long prevAvg = (values.get(2) + values.get(3) + values.get(4)) / 3;

        if (currentAvg > 0) {
            return currentAvg > prevAvg ? TrendDirection.BUYING_ACCELERATED : TrendDirection.BUYING_SLOWED;
        } else if (currentAvg < 0) {
            return currentAvg < prevAvg ? TrendDirection.SELLING_ACCELERATED : TrendDirection.SELLING_SLOWED;
        }
        return TrendDirection.NEUTRAL;
    }

    private SentimentSignal determineHysteresisSentiment(MarketType market, BigDecimal score,
            InvestorTrendInfo currentTrend) {
        String marketCode = market == MarketType.KOSPI ? "0001" : "1001";
        List<MarketInvestorDaily> history = marketInvestorDailyRepository
                .findTop30ByMarketCodeOrderByDateDesc(marketCode);

        // Calculate raw signals for today, yesterday, and day before
        SentimentSignal sig0 = determineRawSentiment(score, currentTrend);

        if (history.size() < 22)
            return sig0;

        InvestorTrendInfo trend1 = processTrend(history.subList(1, 21));
        SentimentSignal sig1 = determineRawSentiment(score, trend1);

        InvestorTrendInfo trend2 = processTrend(history.subList(2, 22));
        SentimentSignal sig2 = determineRawSentiment(score, trend2);

        // 3-day hysteresis: only confirm if sig0 matches at least one previous day
        // Overheating Protection: If it's a high-priority signal (FOMO) and score is
        // very high, flag it immediately
        if (sig0 == sig1 || sig0 == sig2 || (sig0 == SentimentSignal.INDIVIDUAL_FOMO && score.doubleValue() >= 90)) {
            return sig0;
        }

        return sig1; // Stick to previous if today is a flash outlier
    }

    private SentimentSignal determineRawSentiment(BigDecimal totalScore, InvestorTrendInfo trend) {
        double score = totalScore.doubleValue();

        if (score > 80 && trend.getIndividualTrend() == TrendDirection.BUYING_ACCELERATED
                && trend.getForeignNet5d() < 0) {
            return SentimentSignal.INDIVIDUAL_FOMO;
        }

        if (score < 40 && (trend.getForeignNet5d() > 0 || trend.getInstitutionalNet5d() > 0)) {
            return SentimentSignal.SMART_MONEY_INFLOW;
        }

        if (score > 60 && trend.getForeignRelativeStrength() > 1.2) {
            return SentimentSignal.HEALTHY_BULL;
        }

        return SentimentSignal.NEUTRAL;
    }

    private String getSentimentContext(SentimentSignal signal, InvestorTrendInfo trend) {
        String individualFlowStr = trend != null ? formatAmountInTrillion(trend.getIndividualNet5d()) : "";
        long smartMoneyFlow = trend != null ? (trend.getForeignNet5d() + trend.getInstitutionalNet5d()) : 0;
        String smartMoneyFlowStr = formatAmountInTrillion(Math.abs(smartMoneyFlow));

        switch (signal) {
            case INDIVIDUAL_FOMO:
                return String.format(
                        "현재 상승은 개인 투자자의 추격 매수세가 주도하고 있습니다. 특히 외국인과 기관이 약 %s을 매도하며 차익 실현에 나선 반면, 개인이 %s을 순매수하며 가속하고 있어 변동성 확대에 유의하십시오.",
                        smartMoneyFlowStr, individualFlowStr);
            case SMART_MONEY_INFLOW:
                return String.format("지수는 저평가 영역에 머물고 있으나, 기관과 외국인의 선제적인 매수세(약 %s)가 포착됩니다. 분할 매수 관점에서의 접근이 유효합니다.",
                        smartMoneyFlowStr);
            case HEALTHY_BULL:
                return "고평가 구간임에도 불구하고 외국인의 지속적인 자금 유입이 확인됩니다. 실적 기반의 추세적 상승이 유지될 가능성이 높습니다.";
            default:
                return "";
        }
    }

    private String formatAmountInTrillion(long amountMillion) {
        BigDecimal amount = new BigDecimal(amountMillion).multiply(new BigDecimal("1000000"));
        return formatLargeNumber(amount);
    }

    private MarketValuationDto.ScoreDetails calculateScoresRefined(BigDecimal currentCape, BigDecimal yieldGap,
            List<BigDecimal> sortedCapes, List<BigDecimal> chronologicalCapes, BigDecimal avgCape10Y,
            BigDecimal medianCape10Y, List<MarketValuationDto.TimeSeriesPoint> timeSeries) {

        // 1. Yield Gap Score (40점) - Phase 3: Dynamic Scaling based on 10Y Max/Min
        BigDecimal ygScoreValue = BigDecimal.ZERO;
        boolean inversion = false;

        if (yieldGap != null) {
            inversion = yieldGap.compareTo(BigDecimal.ZERO) < 0;

            // Find 10Y Max/Min Yield Gap from timeSeries
            List<BigDecimal> historicalYGs = timeSeries.stream()
                    .map(MarketValuationDto.TimeSeriesPoint::getYieldGap)
                    .filter(Objects::nonNull)
                    .sorted()
                    .collect(Collectors.toList());

            if (!historicalYGs.isEmpty()) {
                BigDecimal minYg = historicalYGs.get(0); // Most dangerous historical outlier
                BigDecimal maxYg = historicalYGs.get(historicalYGs.size() - 1); // Most safe historical point

                // Formula: 맵핑 (MaxYG -> 0점, MinYG -> 40점)
                // score = (MaxYG - currentYG) / (MaxYG - MinYG) * 40
                BigDecimal range = maxYg.subtract(minYg);
                if (range.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal score = maxYg.subtract(yieldGap)
                            .divide(range, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("40"));
                    ygScoreValue = score.max(BigDecimal.ZERO).min(new BigDecimal("40"));
                } else {
                    // Fallback to old linear scaling if historical data is insufficient
                    BigDecimal five = new BigDecimal("5.0");
                    BigDecimal scale = new BigDecimal("3.0");
                    BigDecimal score = five.subtract(yieldGap).divide(scale, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("40"));
                    ygScoreValue = score.max(BigDecimal.ZERO).min(new BigDecimal("40"));
                }
            }
        }

        // 3. Median Deviation Score (20점)
        BigDecimal devScoreValue = BigDecimal.ZERO;
        boolean distortion = false;
        if (medianCape10Y != null && medianCape10Y.compareTo(BigDecimal.ZERO) > 0 && currentCape != null) {
            BigDecimal deviation = currentCape.subtract(medianCape10Y).divide(medianCape10Y, 4, RoundingMode.HALF_UP);
            // +20% deviation = 20 points
            BigDecimal score = deviation.divide(new BigDecimal("0.20"), 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("20"));
            devScoreValue = score.max(BigDecimal.ZERO).min(new BigDecimal("20"));

            // Note: If index is extremely high but CAPE is low, it might be structural
            // change.
            // 15% below median despite bull context = distortion
            distortion = currentCape.compareTo(medianCape10Y.multiply(new BigDecimal("0.85"))) < 0;
        }

        // 4. Range Position (Phase 7.5 - New)
        BigDecimal rangePosition = BigDecimal.ZERO;
        if (!sortedCapes.isEmpty() && currentCape != null) {
            BigDecimal min = sortedCapes.get(0);
            BigDecimal max = sortedCapes.get(sortedCapes.size() - 1);
            BigDecimal range = max.subtract(min);
            if (range.compareTo(BigDecimal.ZERO) > 0) {
                rangePosition = currentCape.subtract(min).divide(range, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
            }
        }

        // 5. Statistical Rank (distributionPercentile) (Phase 7.6 - Corrected)
        BigDecimal percentile = BigDecimal.ZERO;
        if (!sortedCapes.isEmpty() && currentCape != null) {
            long countLower = sortedCapes.stream()
                    .filter(c -> c.compareTo(currentCape) <= 0)
                    .count();
            percentile = new BigDecimal(countLower)
                    .multiply(new BigDecimal("100"))
                    .divide(new BigDecimal(sortedCapes.size()), 1, RoundingMode.HALF_UP);
        }

        return MarketValuationDto.ScoreDetails.builder()
                .capeRangePosition(rangePosition.setScale(1, RoundingMode.HALF_UP))
                .distributionPercentile(percentile)
                .yieldGapScore(ygScoreValue.setScale(1, RoundingMode.HALF_UP))
                .deviationScore(devScoreValue.setScale(1, RoundingMode.HALF_UP))
                .yieldGapInversion(inversion)
                .dataDistortionWarning(distortion)
                .build();
    }

    private String determineGrade(BigDecimal totalScore) {
        double score = totalScore.doubleValue();
        if (score >= 80)
            return "EXTREME_GREED";
        if (score >= 60)
            return "OVERHEATED";
        if (score >= 35)
            return "FAIR"; // Kept 35 to give more room for FAIR
        if (score >= 15)
            return "UNDERVALUED";
        return "EXTREME_FEAR";
    }

    private String determineStrategy(BigDecimal totalScore) {
        double score = totalScore.doubleValue();
        if (score >= 90)
            return "극심한 과열: 기대수익 대비 위험이 비정상적으로 커진 상태입니다. 상승 시 얻을 수익보다 하락 시 입을 손실이 압도적으로 큰 비대칭적 위험 구간이므로, 적극적인 수익 실현과 현금 비중 확대를 권고합니다.";
        if (score >= 80)
            return "상당한 과열: 밸류에이션 부담이 가중되고 있습니다. 추격 매수보다는 리스크 관리에 집중해야 하며, 보유 종목의 비중을 점진적으로 축소할 필요가 있습니다.";
        if (score >= 60)
            return "주의: 시장의 심리가 고조되어 있으나 펀더멘털과의 괴리가 발생하기 시작했습니다. 손절선(Stop-loss)을 타이트하게 운영하며 보수적으로 대응하십시오.";
        if (score >= 35)
            return "적정: 지수의 밸류에이션이 평균 회귀 수준에 머물러 있습니다. 지수 방향성보다는 개별 기업의 실적과 가치에 집중하는 종목 장세 대응이 유효합니다.";
        if (score >= 15)
            return "저평가: 시장의 공포로 인해 자산 가치 대비 가격 매력이 발생했습니다. 우량주를 중심으로 분할 매수 관점의 접근이 가능한 구간입니다.";
        return "과매도: 역사적 바닥 구간입니다. 기대수익률이 리스크를 압도하는 구간으로, 공포를 이겨내고 적극적인 비중 확대를 고려할 타이밍입니다.";
    }

    private Map<Integer, BigDecimal> getAnnualCpiMap() {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusYears(11);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd");

        List<MacroIndicatorDto> cpiList = kisMacroService.fetchMacroData(STAT_CODE_CPI, ITEM_CODE_CPI, "X", "CPI",
                start.format(fmt), end.format(fmt));

        Map<Integer, List<BigDecimal>> annualValues = new HashMap<>();
        for (MacroIndicatorDto dto : cpiList) {
            int year = Integer.parseInt(dto.getDate().substring(0, 4));
            annualValues.computeIfAbsent(year, k -> new ArrayList<>()).add(new BigDecimal(dto.getValue()));
        }

        Map<Integer, BigDecimal> resultMap = new HashMap<>();
        annualValues.forEach((year, values) -> {
            BigDecimal avg = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(new BigDecimal(values.size()), 4, RoundingMode.HALF_UP);
            resultMap.put(year, avg);
        });

        return resultMap;
    }

    private List<MarketValuationDto.TimeSeriesPoint> generateTimeSeries(MarketType market, BigDecimal currentCape,
            BigDecimal currentAvgEarnings, BigDecimal currentBondYield) {
        List<MarketValuationDto.TimeSeriesPoint> points = new ArrayList<>();
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusYears(10);

        List<IndexDailyData> indexData = indexDailyDataRepository
                .findAllByMarketNameAndDateBetweenOrderByDateDesc(market.name(), start, end);

        DateTimeFormatter monthFmt = DateTimeFormatter.ofPattern("yyyy-MM");

        // Fetch historical bond yields for accurate Yield Gap band (Actual data from
        // MacroDailyData)
        String bondSymbol = market.name().startsWith("KOS") ? BondYield.KR_10Y.getSymbol()
                : BondYield.US_10Y.getSymbol();
        List<MacroDailyData> bondDataList = macroDailyDataRepository
                .findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(STAT_CODE_BOND_YIELD, bondSymbol, start, end);

        Map<String, BigDecimal> monthlyBondYields = new HashMap<>();
        for (MacroDailyData b : bondDataList) {
            String bKey = b.getDate().format(monthFmt);
            monthlyBondYields.put(bKey, b.getValue());
        }

        // Sample monthly (last day of each month)
        Map<String, IndexDailyData> monthlySamples = new TreeMap<>();
        for (IndexDailyData d : indexData) {
            String key = d.getDate().format(monthFmt);
            if (!monthlySamples.containsKey(key)) {
                monthlySamples.put(key, d);
            }
        }

        // Critical Fix: currentIndexPrice must be the most recent closing price
        BigDecimal currentIndexPrice = indexData.isEmpty() ? BigDecimal.ONE : indexData.get(0).getClosingPrice();

        for (Map.Entry<String, IndexDailyData> entry : monthlySamples.entrySet()) {
            BigDecimal indexPrice = entry.getValue().getClosingPrice();
            // Simple proxy for historical CAPE time series
            // CAPE_M = Index_M * (CurrentCAPE / CurrentIndex)
            BigDecimal historicalCape = indexPrice.multiply(currentCape).divide(currentIndexPrice, 2,
                    RoundingMode.HALF_UP);

            BigDecimal historicalYieldGap = null;
            if (historicalCape.compareTo(BigDecimal.ZERO) > 0) {
                // Use historical bond yield if available, fallback to current
                BigDecimal monthBondYield = monthlyBondYields.getOrDefault(entry.getKey(), currentBondYield);

                if (monthBondYield != null) {
                    BigDecimal earningsYield = BigDecimal.ONE.divide(historicalCape, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal(100));
                    historicalYieldGap = earningsYield.subtract(monthBondYield).setScale(2, RoundingMode.HALF_UP);
                }
            }

            points.add(MarketValuationDto.TimeSeriesPoint.builder()
                    .date(entry.getKey())
                    .cape(historicalCape)
                    .yieldGap(historicalYieldGap)
                    .build());
        }

        return points;
    }

    private String formatLargeNumber(BigDecimal value) {
        if (value == null)
            return "N/A";
        BigDecimal trillion = new BigDecimal("1000000000000");
        BigDecimal billion = new BigDecimal("100000000");

        if (value.compareTo(trillion) >= 0) {
            return value.divide(trillion, 1, RoundingMode.HALF_UP).toPlainString() + "조 원";
        } else if (value.compareTo(billion) >= 0) {
            return value.divide(billion, 0, RoundingMode.HALF_UP).toPlainString() + "억 원";
        }
        return value.toPlainString() + "원";
    }
}
