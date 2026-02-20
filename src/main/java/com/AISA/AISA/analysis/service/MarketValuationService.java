package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.dto.MarketValuationDto;
import com.AISA.AISA.kisStock.Entity.stock.FuturesInvestorDaily;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockBalanceSheet;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialStatement;
import com.AISA.AISA.kisStock.Entity.stock.StockMarketCap;
import com.AISA.AISA.kisStock.dto.Index.IndexChartInfoDto;
import com.AISA.AISA.kisStock.enums.MarketType;
import com.AISA.AISA.kisStock.kisService.KisIndexService;
import com.AISA.AISA.kisStock.repository.*;
import com.AISA.AISA.kisStock.kisService.KisMacroService;
import com.AISA.AISA.portfolio.macro.repository.MacroDailyDataRepository;
import com.AISA.AISA.portfolio.macro.Entity.MacroDailyData;
import com.AISA.AISA.portfolio.macro.dto.MacroIndicatorDto;
import com.AISA.AISA.kisStock.Entity.Index.IndexDailyData;
import com.AISA.AISA.kisStock.Entity.stock.MarketInvestorDaily;
import com.AISA.AISA.kisStock.enums.BondYield;
import com.AISA.AISA.kisStock.enums.FuturesMarketType;
import com.AISA.AISA.analysis.dto.MarketValuationDto.*;
import com.AISA.AISA.kisStock.dto.Index.BreadthHistoryDto;
import org.springframework.data.domain.PageRequest;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;

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
    private final FuturesInvestorDailyRepository futuresInvestorDailyRepository;
    private final KisMacroService kisMacroService;
    private final StockDailyDataRepository stockDailyDataRepository;
    private final GeminiService geminiService;
    private final KisIndexService kisIndexService;

    // Inner record for KNN calculation
    private static class KnnCandidate {
        String date;
        double distance;
        double forwardReturn;
        double weight;

        KnnCandidate(String date, double distance, double forwardReturn) {
            this.date = date;
            this.distance = distance;
            this.forwardReturn = forwardReturn;
            // Adaptive Weight: closer distance -> higher weight
            // standard epsilon for numerical stability
            this.weight = 1.0 / (distance + 1e-4);
        }
    }

    private static final String STAT_CODE_CPI = "901Y001";
    private static final String ITEM_CODE_CPI = "0";
    private static final String STAT_CODE_BOND_YIELD = "KIS_BOND_YIELD";

    @Cacheable(value = "marketValuation", key = "#market")
    public MarketValuationDto calculateMarketValuation(MarketType market) {
        return performCalculation(market, true);
    }

    /**
     * 장중(10시, 13시, 16시) 갱신 시에는 includeBreadth=false로 호출하여
     * StockDailyData 기반 breadth 계산을 건너뛰고 기본값을 사용합니다.
     * 장 마감 후(22시) 갱신 시에는 includeBreadth=true로 전체 계산합니다.
     */
    @CachePut(value = "marketValuation", key = "#market")
    public MarketValuationDto calculateMarketValuationWithOptions(MarketType market, boolean includeBreadth) {
        return performCalculation(market, includeBreadth);
    }

    @CacheEvict(value = "marketValuation", key = "#market")
    public void evictMarketValuationCache(MarketType market) {
        log.info("Evicting market valuation cache for {}", market);
    }

    // 스케줄링은 MarketValuationScheduler에서 관리합니다.

    private MarketValuationDto performCalculation(MarketType market, boolean includeBreadth) {
        log.info("Calculating market valuation for {}", market);
        try {
            // 1. Fetch all stocks in the market
            List<Stock> allStocks = stockRepository.findByMarketName(market);
            Map<String, Stock> stockMap = allStocks.stream()
                    .collect(Collectors.toMap(Stock::getStockCode, s -> s, (s1, s2) -> s1));
            List<Stock> stocks = new ArrayList<>(stockMap.values());
            List<String> stockCodes = stocks.stream().map(Stock::getStockCode).collect(Collectors.toList());
            Set<String> stockCodeSet = new HashSet<>(stockCodes);

            // 2. Fetch market caps
            List<StockMarketCap> allMarketCaps = stockMarketCapRepository.findByStockIn(stocks);
            Map<String, StockMarketCap> marketCapMap = allMarketCaps.stream()
                    .filter(mc -> mc.getStock() != null)
                    .filter(mc -> mc.getMarketCap() != null && mc.getMarketCap().compareTo(BigDecimal.ZERO) > 0)
                    .collect(Collectors.toMap(mc -> mc.getStock().getStockCode(), mc -> mc, (mc1, mc2) -> mc1));
            List<StockMarketCap> marketCaps = new ArrayList<>(marketCapMap.values());

            BigDecimal rawTotalMarketCapUnits = marketCaps.stream()
                    .map(smc -> smc.getMarketCap() != null ? smc.getMarketCap() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // 3. Fetch latest financial statements
            Map<String, StockFinancialStatement> latestStatements = stockFinancialStatementRepository
                    .findByStockCodeInAndDivCode(stockCodeSet, "0").stream()
                    .collect(Collectors.toMap(
                            StockFinancialStatement::getStockCode,
                            s -> s,
                            (s1, s2) -> s1.getStacYymm().compareTo(s2.getStacYymm()) >= 0 ? s1 : s2));

            Map<String, StockBalanceSheet> latestBalanceSheets = stockBalanceSheetRepository
                    .findByStockCodeInAndDivCode(stockCodeSet, "0").stream()
                    .collect(Collectors.toMap(
                            StockBalanceSheet::getStockCode,
                            b -> b,
                            (b1, b2) -> b1.getStacYymm().compareTo(b2.getStacYymm()) >= 0 ? b1 : b2));

            // 4. Data Pool Filter
            Set<String> validStockCodes = latestStatements.keySet().stream()
                    .filter(latestBalanceSheets::containsKey)
                    .filter(code -> marketCapMap.containsKey(code))
                    .collect(Collectors.toSet());

            // 5. Calculate Basic Metrics
            // Try fetching real-time index price for more accurate valuation
            BigDecimal realTimeIndexPrice = null;
            try {
                IndexChartInfoDto indexStatus = kisIndexService.getIndexStatus(market.name());
                if (indexStatus != null && indexStatus.getCurrentIndices() != null) {
                    realTimeIndexPrice = new BigDecimal(indexStatus.getCurrentIndices());
                }
            } catch (Exception e) {
                log.warn("Failed to fetch real-time {} price, using DB market cap only: {}", market, e.getMessage());
            }

            BigDecimal totalMarketCapValue = marketCaps.stream()
                    .filter(mc -> validStockCodes.contains(mc.getStock().getStockCode()))
                    .map(smc -> smc.getMarketCap() != null ? smc.getMarketCap() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .multiply(new BigDecimal("100000000"));

            BigDecimal totalNetIncomeValue = latestStatements.entrySet().stream()
                    .filter(e -> validStockCodes.contains(e.getKey()))
                    .map(e -> e.getValue().getNetIncome() != null ? e.getValue().getNetIncome() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .multiply(new BigDecimal("100000000"));

            BigDecimal totalCapitalValue = latestBalanceSheets.entrySet().stream()
                    .filter(e -> validStockCodes.contains(e.getKey()))
                    .map(e -> e.getValue().getTotalCapital() != null ? e.getValue().getTotalCapital() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .multiply(new BigDecimal("100000000"));

            BigDecimal per = (totalNetIncomeValue.compareTo(BigDecimal.ZERO) > 0)
                    ? totalMarketCapValue.divide(totalNetIncomeValue, 2, RoundingMode.HALF_UP)
                    : null;
            BigDecimal pbr = (totalCapitalValue.compareTo(BigDecimal.ZERO) > 0)
                    ? totalMarketCapValue.divide(totalCapitalValue, 2, RoundingMode.HALF_UP)
                    : null;

            // 6. CAPE and Yield Gap
            BigDecimal bondYield = kisMacroService
                    .getLatestBondYield(market.name().startsWith("KOS") ? BondYield.KR_10Y : BondYield.US_10Y);
            Map<Integer, BigDecimal> cpiMap = getAnnualCpiMap();
            int currentYear = LocalDate.now().getYear();
            BigDecimal currentCpi = cpiMap.getOrDefault(currentYear,
                    cpiMap.values().stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ONE));

            // Historical Earnings for CAPE
            List<StockFinancialStatement> histEarnings = stockFinancialStatementRepository
                    .findByStockCodeInAndDivCode(stockCodeSet, "0");
            Map<Integer, BigDecimal> annualAdjSums = new HashMap<>();
            Set<String> stocksWithHistory = new HashSet<>();
            Map<String, Set<Integer>> stockYears = new HashMap<>();

            for (StockFinancialStatement s : histEarnings) {
                try {
                    int year = Integer.parseInt(s.getStacYymm().substring(0, 4));
                    // Expand range to support rolling 10-year average for historical CAPE time series
                    if (year >= currentYear - 20 && year < currentYear) {
                        BigDecimal cpiPast = cpiMap.getOrDefault(year, currentCpi);
                        BigDecimal adj = s.getNetIncome().multiply(new BigDecimal("100000000"))
                                .multiply(currentCpi).divide(cpiPast, 0, RoundingMode.HALF_UP);
                        annualAdjSums.merge(year, adj, BigDecimal::add);
                        if (year >= currentYear - 10) {
                            stockYears.computeIfAbsent(s.getStockCode(), k -> new HashSet<>()).add(year);
                        }
                    }
                } catch (Exception e) {
                }
            }

            for (String code : stockCodeSet) {
                if (stockYears.getOrDefault(code, Collections.emptySet()).size() >= 8)
                    stocksWithHistory.add(code);
            }

            // Use only the recent 10 years for current CAPE (annualAdjSums has up to 20 years for historical rolling)
            BigDecimal avgEarnings10Y = BigDecimal.ZERO;
            {
                List<BigDecimal> recent10YEarnings = new ArrayList<>();
                for (int y = currentYear - 10; y < currentYear; y++) {
                    BigDecimal earnings = annualAdjSums.get(y);
                    if (earnings != null) recent10YEarnings.add(earnings);
                }
                if (!recent10YEarnings.isEmpty()) {
                    avgEarnings10Y = recent10YEarnings.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(new BigDecimal(recent10YEarnings.size()), 0, RoundingMode.HALF_UP);
                }
            }

            BigDecimal currentCape = BigDecimal.ZERO;
            if (avgEarnings10Y.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal mCapForCape = marketCaps.stream()
                        .filter(mc -> stocksWithHistory.contains(mc.getStock().getStockCode()))
                        .map(StockMarketCap::getMarketCap)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .multiply(new BigDecimal("100000000"));
                currentCape = mCapForCape.divide(avgEarnings10Y, 2, RoundingMode.HALF_UP);
            }

            BigDecimal yieldGap = (currentCape.compareTo(BigDecimal.ZERO) > 0 && bondYield != null)
                    ? BigDecimal.ONE.divide(currentCape, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal(100)).subtract(bondYield).setScale(2, RoundingMode.HALF_UP)
                    : null;

            // 7. Scoring and Time Series
            List<MarketValuationDto.TimeSeriesPoint> timeSeries = generateTimeSeries(market, currentCape, bondYield,
                    annualAdjSums, marketCaps, stocksWithHistory, cpiMap, currentCpi);
            List<BigDecimal> sortedCapes = timeSeries.stream().map(MarketValuationDto.TimeSeriesPoint::getCape)
                    .filter(Objects::nonNull).sorted().collect(Collectors.toList());
            List<BigDecimal> chronCapes = timeSeries.stream().map(MarketValuationDto.TimeSeriesPoint::getCape)
                    .filter(Objects::nonNull).collect(Collectors.toList());

            BigDecimal low = sortedCapes.isEmpty() ? BigDecimal.ZERO : sortedCapes.get(0);
            BigDecimal high = sortedCapes.isEmpty() ? BigDecimal.ZERO : sortedCapes.get(sortedCapes.size() - 1);
            BigDecimal avg = sortedCapes.isEmpty() ? BigDecimal.ZERO
                    : sortedCapes.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(
                            new BigDecimal(sortedCapes.size()), 4,
                            RoundingMode.HALF_UP);
            BigDecimal median = sortedCapes.isEmpty() ? BigDecimal.ZERO : sortedCapes.get(sortedCapes.size() / 2);

            MarketValuationDto.ScoreDetails scoreDetails = calculateScoresRefined(currentCape, yieldGap, sortedCapes,
                    chronCapes,
                    avg, median, timeSeries);

            // 8. Trend and Sentiment
            // YG Score (40 max) + Deviation Score (20 max) = 60 base
            BigDecimal valuationScore = scoreDetails.getYieldGapScore().add(scoreDetails.getDeviationScore());
            // CAPE range position bonus (max 10)
            BigDecimal bonus = scoreDetails.getCapeRangePosition().subtract(new BigDecimal("50"))
                    .multiply(new BigDecimal("0.2")).max(BigDecimal.ZERO);
            valuationScore = valuationScore.add(bonus);
            // YG inversion penalty: relative to market's own distribution (max 25)
            // Uses YG percentile: 50% median → 0 pts, 100% extreme → 25 pts
            if (Boolean.TRUE.equals(scoreDetails.getYieldGapInversion()) && scoreDetails.getYieldGapPercentile() != null) {
                double ygPct = scoreDetails.getYieldGapPercentile().doubleValue();
                double inversionPenalty = Math.max(0, (ygPct - 50.0) / 50.0) * 25.0;
                valuationScore = valuationScore.add(new BigDecimal(inversionPenalty));
            }
            valuationScore = valuationScore.min(new BigDecimal("100.0")).setScale(1, RoundingMode.HALF_UP);

            InvestorTrendInfo trend = calculateInvestorTrend(market, includeBreadth);
            TrendScoreResult trendResult = (trend != null) ? calculateTrendScore(market, trend)
                    : new TrendScoreResult(BigDecimal.ZERO, "수급 분석 불가");

            ValuationSignal vSignal = (trend != null) ? determineValuationSignal(valuationScore, trend)
                    : ValuationSignal.NEUTRAL;
            TrendSignal tSignal = (trend != null) ? determineTrendSignal(trendResult.score, trend)
                    : TrendSignal.NEUTRAL;

            scoreDetails = scoreDetails.toBuilder()
                    .valuationSignal(vSignal)
                    .trendSignal(tSignal)
                    .build();

            BigDecimal coverage = rawTotalMarketCapUnits.compareTo(BigDecimal.ZERO) > 0
                    ? marketCaps.stream().filter(mc -> stocksWithHistory.contains(mc.getStock().getStockCode()))
                            .map(mc -> mc.getMarketCap()).reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(rawTotalMarketCapUnits, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100))
                    : BigDecimal.ZERO;

            MarketValuationDto dto = MarketValuationDto.builder()
                    .market(market)
                    .marketDescription(market.getDescription())
                    .valuationScore(valuationScore)
                    .grade(determineGrade(valuationScore))
                    .trendScore(trendResult.score)
                    .trendDescription(trendResult.description)
                    .valuation(MarketValuationDto.ValuationInfo.builder()
                            .per(per).pbr(pbr).cape(currentCape).yieldGap(yieldGap).bondYield(bondYield).build())
                    .scoreDetails(scoreDetails)
                    .investorTrend(trend)
                    .predictionReport(
                            calculateTrendProbability(currentCape, yieldGap, valuationScore, trendResult.score,
                                    trend, timeSeries, market))
                    .metadata(MarketValuationDto.MetadataInfo.builder()
                            .stockCount(marketCaps.size())
                            .totalMarketCap(formatLargeNumber(totalMarketCapValue))
                            .dataCoverage(coverage)
                            .updatedAt(java.time.OffsetDateTime.now().toString())
                            .historicalStats(MarketValuationDto.HistoricalStats.builder()
                                    .tenYearLow(low).tenYearHigh(high).tenYearAvg(avg.setScale(2, RoundingMode.HALF_UP))
                                    .tenYearMedian(median).build())
                            .build())
                    .timeSeries(timeSeries)
                    .build();

            // 9. AI Strategy (Split)
            GeminiService.StrategyResult aiRes = geminiService.generateMarketStrategy(dto);
            String fallbackV = determineStrategy(valuationScore)
                    + " " + getValuationSentimentContext(vSignal)
                    + " " + getTrendSentimentContext(tSignal);

            return dto.toBuilder()
                    .valuationStrategy(
                            aiRes != null && aiRes.valuationStrategy() != null ? aiRes.valuationStrategy() : fallbackV)
                    .trendStrategy(aiRes != null ? aiRes.trendStrategy() : null)
                    .build();

        } catch (Exception e) {
            log.error("Error calculating market valuation for {}: {}", market, e.getMessage(), e);
            return MarketValuationDto.builder()
                    .market(market)
                    .marketDescription(market.getDescription() + " (데이터 부족 또는 계산 오류)")
                    .build();
        }
    }

    private Map<Integer, BigDecimal> getAnnualCpiMap() {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusYears(21);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd");

        try {
            List<MacroIndicatorDto> cpiList = kisMacroService.fetchMacroData(STAT_CODE_CPI, ITEM_CODE_CPI, "X", "CPI",
                    start.format(fmt), end.format(fmt));

            if (cpiList == null)
                return Collections.emptyMap();

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
        } catch (Exception e) {
            log.warn("Failed to fetch CPI map: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private List<MarketValuationDto.TimeSeriesPoint> generateTimeSeries(MarketType market, BigDecimal currentCape,
            BigDecimal currentBondYield, Map<Integer, BigDecimal> annualAdjSums,
            List<StockMarketCap> marketCaps, Set<String> stocksWithHistory,
            Map<Integer, BigDecimal> cpiMap, BigDecimal currentCpi) {
        List<MarketValuationDto.TimeSeriesPoint> points = new ArrayList<>();
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusYears(10);

        List<IndexDailyData> indexData = indexDailyDataRepository
                .findAllByMarketNameAndDateBetweenOrderByDateDesc(market.name(), start, end);

        if (indexData.isEmpty())
            return points;

        BigDecimal currentIndexPrice = indexData.get(0).getClosingPrice();
        String bondSymbol = market.name().startsWith("KOS") ? BondYield.KR_10Y.getSymbol()
                : BondYield.US_10Y.getSymbol();
        List<MacroDailyData> bondDataList = macroDailyDataRepository
                .findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(STAT_CODE_BOND_YIELD, bondSymbol, start, end);

        Map<String, BigDecimal> monthlyBondYields = new HashMap<>();
        DateTimeFormatter monthFmt = DateTimeFormatter.ofPattern("yyyy-MM");
        for (MacroDailyData b : bondDataList) {
            monthlyBondYields.put(b.getDate().format(monthFmt), b.getValue());
        }

        // Pre-calculate current market cap for CAPE stocks (for proportional scaling)
        BigDecimal currentMCapForCape = marketCaps.stream()
                .filter(mc -> stocksWithHistory.contains(mc.getStock().getStockCode()))
                .map(StockMarketCap::getMarketCap)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .multiply(new BigDecimal("100000000"));

        Map<String, IndexDailyData> monthlySamples = new TreeMap<>();
        for (IndexDailyData d : indexData) {
            DateTimeFormatter yyyyMM = DateTimeFormatter.ofPattern("yyyy-MM");
            String key = d.getDate().format(yyyyMM);
            monthlySamples.putIfAbsent(key, d);
        }

        for (Map.Entry<String, IndexDailyData> entry : monthlySamples.entrySet()) {
            IndexDailyData d = entry.getValue();
            String fullDate = d.getDate().toString();
            String monthKey = entry.getKey();
            int pointYear = d.getDate().getYear();

            BigDecimal indexPrice = d.getClosingPrice();

            // Calculate rolling 10-year average CPI-adjusted earnings for this point
            BigDecimal rollingAvgEarnings = calculateRollingAvgEarnings(pointYear, annualAdjSums, cpiMap, currentCpi);

            BigDecimal historicalCape;
            if (rollingAvgEarnings != null && rollingAvgEarnings.compareTo(BigDecimal.ZERO) > 0
                    && currentIndexPrice.compareTo(BigDecimal.ZERO) > 0) {
                // Proportional market cap at historical point
                BigDecimal historicalMCap = currentMCapForCape.multiply(indexPrice)
                        .divide(currentIndexPrice, 0, RoundingMode.HALF_UP);
                historicalCape = historicalMCap.divide(rollingAvgEarnings, 2, RoundingMode.HALF_UP);
            } else {
                // Fallback: proportional scaling from current CAPE
                historicalCape = indexPrice.multiply(currentCape).divide(currentIndexPrice, 2, RoundingMode.HALF_UP);
            }

            BigDecimal historicalYieldGap = null;
            if (historicalCape.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal monthBondYield = monthlyBondYields.getOrDefault(monthKey, currentBondYield);
                if (monthBondYield != null) {
                    BigDecimal earningsYield = BigDecimal.ONE.divide(historicalCape, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal(100));
                    historicalYieldGap = earningsYield.subtract(monthBondYield).setScale(2, RoundingMode.HALF_UP);
                }
            }

            points.add(MarketValuationDto.TimeSeriesPoint.builder()
                    .date(fullDate)
                    .cape(historicalCape)
                    .yieldGap(historicalYieldGap)
                    .build());
        }
        return points;
    }

    /**
     * Calculate rolling 10-year average CPI-adjusted earnings for a given year.
     * Uses the annualAdjSums map (already CPI-adjusted to current year's CPI).
     */
    private BigDecimal calculateRollingAvgEarnings(int pointYear, Map<Integer, BigDecimal> annualAdjSums,
            Map<Integer, BigDecimal> cpiMap, BigDecimal currentCpi) {
        List<BigDecimal> earningsInWindow = new ArrayList<>();
        for (int y = pointYear - 10; y < pointYear; y++) {
            BigDecimal earnings = annualAdjSums.get(y);
            if (earnings != null) {
                earningsInWindow.add(earnings);
            }
        }
        if (earningsInWindow.size() < 5) {
            return null; // Not enough data for reliable rolling average
        }
        return earningsInWindow.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(earningsInWindow.size()), 0, RoundingMode.HALF_UP);
    }

    private MarketValuationDto.ScoreDetails calculateScoresRefined(BigDecimal currentCape, BigDecimal yieldGap,
            List<BigDecimal> sortedCapes, List<BigDecimal> chronologicalCapes, BigDecimal avgCape10Y,
            BigDecimal medianCape10Y, List<MarketValuationDto.TimeSeriesPoint> timeSeries) {

        BigDecimal ygScoreValue = BigDecimal.ZERO;
        boolean inversion = false;
        BigDecimal ygDeviationFromMedian = BigDecimal.ZERO;

        if (yieldGap != null) {
            List<BigDecimal> historicalYGs = timeSeries.stream()
                    .map(MarketValuationDto.TimeSeriesPoint::getYieldGap)
                    .filter(Objects::nonNull)
                    .sorted()
                    .collect(Collectors.toList());

            if (!historicalYGs.isEmpty()) {
                BigDecimal medianYg = historicalYGs.get(historicalYGs.size() / 2);
                BigDecimal minYg = historicalYGs.get(0);
                BigDecimal maxYg = historicalYGs.get(historicalYGs.size() - 1);
                BigDecimal range = maxYg.subtract(minYg);

                // Inversion: current YG is below its own market's historical median
                inversion = yieldGap.compareTo(medianYg) < 0;
                ygDeviationFromMedian = medianYg.subtract(yieldGap);

                if (range.compareTo(BigDecimal.ZERO) > 0) {
                    // YG Score: percentile-based (where current YG sits in its own distribution)
                    // Lower YG relative to history = higher score (more overvalued)
                    BigDecimal score = maxYg.subtract(yieldGap).divide(range, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("40"));
                    ygScoreValue = score.max(BigDecimal.ZERO).min(new BigDecimal("40"));
                }
            }
        }

        BigDecimal devScoreValue = BigDecimal.ZERO;
        boolean distortion = false;
        if (medianCape10Y != null && medianCape10Y.compareTo(BigDecimal.ZERO) > 0 && currentCape != null) {
            BigDecimal deviation = currentCape.subtract(medianCape10Y).divide(medianCape10Y, 4, RoundingMode.HALF_UP);
            BigDecimal score = deviation.divide(new BigDecimal("0.20"), 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("20"));
            devScoreValue = score.max(BigDecimal.ZERO).min(new BigDecimal("20"));
            distortion = currentCape.compareTo(medianCape10Y.multiply(new BigDecimal("0.85"))) < 0;
        }

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

        BigDecimal percentile = BigDecimal.ZERO;
        if (!sortedCapes.isEmpty() && currentCape != null) {
            long countLower = sortedCapes.stream().filter(c -> c.compareTo(currentCape) <= 0).count();
            percentile = new BigDecimal(countLower).multiply(new BigDecimal("100"))
                    .divide(new BigDecimal(sortedCapes.size()), 1, RoundingMode.HALF_UP);
        }

        // Calculate YG percentile (how overvalued relative to own market history)
        // Higher percentile = current YG is lower than most historical points = more overvalued
        BigDecimal ygPercentile = BigDecimal.ZERO;
        {
            List<BigDecimal> historicalYGs = timeSeries.stream()
                    .map(MarketValuationDto.TimeSeriesPoint::getYieldGap)
                    .filter(Objects::nonNull)
                    .sorted()
                    .collect(Collectors.toList());
            if (!historicalYGs.isEmpty() && yieldGap != null) {
                long countHigherYg = historicalYGs.stream().filter(yg -> yg.compareTo(yieldGap) > 0).count();
                ygPercentile = new BigDecimal(countHigherYg).multiply(new BigDecimal("100"))
                        .divide(new BigDecimal(historicalYGs.size()), 1, RoundingMode.HALF_UP);
            }
        }

        return MarketValuationDto.ScoreDetails.builder()
                .capeRangePosition(rangePosition.setScale(1, RoundingMode.HALF_UP))
                .distributionPercentile(percentile)
                .yieldGapScore(ygScoreValue.setScale(1, RoundingMode.HALF_UP))
                .deviationScore(devScoreValue.setScale(1, RoundingMode.HALF_UP))
                .yieldGapInversion(inversion)
                .yieldGapPercentile(ygPercentile)
                .dataDistortionWarning(distortion)
                .build();
    }

    private String determineGrade(BigDecimal valuationScore) {
        double score = valuationScore.doubleValue();
        if (score >= 80)
            return "EXTREME_GREED";
        if (score >= 60)
            return "OVERHEATED";
        if (score >= 35)
            return "FAIR";
        if (score >= 15)
            return "UNDERVALUED";
        return "EXTREME_FEAR";
    }

    private String determineStrategy(BigDecimal valuationScore) {
        double score = valuationScore.doubleValue();
        if (score >= 90)
            return "극심한 과열: 수익실현 권고";
        if (score >= 80)
            return "상당한 과열: 비중 축소";
        if (score >= 60)
            return "주의: 보수적 대응";
        if (score >= 35)
            return "적정: 종목별 장세";
        if (score >= 15)
            return "저평가: 분할 매수";
        return "과매도: 비중 확대 고려";
    }

    private String getValuationSentimentContext(ValuationSignal signal) {
        if (signal == null || signal == ValuationSignal.NEUTRAL)
            return "";
        switch (signal) {
            case UNDERVALUED_BUY:
                return "[저평가 매수] 스마트 머니 유입 신호";
            case OVERVALUED_CAUTION:
                return "[고평가 경계] 메이저 자금 이탈 관찰";
            case VALUE_TRAP:
                return "[가치 함정] 저평가에도 불구하고 수급 부재";
            case FAIR_VALUE:
                return "[적정 가치] 시장 균형 상태";
            default:
                return "";
        }
    }

    private String getTrendSentimentContext(TrendSignal signal) {
        if (signal == null || signal == TrendSignal.NEUTRAL)
            return "";
        switch (signal) {
            case HEALTHY_BULL:
                return "[건강한 상승] 추세적 매수세 확인";
            case BULL_TRAP:
                return "[불 트랩] 모멘텀 둔화 및 이탈 징후";
            case OVERSOLD_REBOUND:
                return "[과매도 반등] 기술적 반등 가능성";
            case PANIC_SELLING:
                return "[투매 발생] 공포 섞인 이탈 가속";
            case STAGNANT:
                return "[정체] 뚜렷한 방향성 부재";
            default:
                return "";
        }
    }

    private InvestorTrendInfo calculateInvestorTrend(MarketType market, boolean includeBreadth) {
        String marketCode = market == MarketType.KOSPI ? "0001" : "1001";
        List<MarketInvestorDaily> history = marketInvestorDailyRepository
                .findTop30ByMarketCodeOrderByDateDesc(marketCode);
        if (history.size() < 22)
            return null;
        return processTrend(market, history.subList(0, 20), includeBreadth);
    }

    private InvestorTrendInfo processTrend(MarketType market, List<MarketInvestorDaily> subHistory, boolean includeBreadth) {
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

        BreadthResult breadth = includeBreadth
                ? calculateMarketBreadth(market, subHistory.get(0).getDate())
                : new BreadthResult(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "장중 - 전일 기준 breadth 미포함");

        // VKOSPI: Only applicable for KOSPI (no equivalent index for KOSDAQ)
        BigDecimal vkospi = null;
        if (market == MarketType.KOSPI) {
            try {
                IndexChartInfoDto vkospiStatus = kisIndexService.getIndexStatus("VKOSPI");
                if (vkospiStatus != null && vkospiStatus.getCurrentIndices() != null) {
                    vkospi = new BigDecimal(vkospiStatus.getCurrentIndices());
                }
            } catch (Exception e) {
                log.warn("Failed to fetch real-time VKOSPI, falling back to DB: {}", e.getMessage());
            }

            if (vkospi == null) {
                vkospi = indexDailyDataRepository.findFirstByMarketNameOrderByDateDesc("VKOSPI")
                        .map(IndexDailyData::getClosingPrice).orElse(null);
            }
        }

        // Futures Integration
        long futForeignNet5d = 0, futIndividualNet5d = 0, futInstitutionalNet5d = 0;
        try {
            FuturesMarketType futMarket = (market == MarketType.KOSPI) ? FuturesMarketType.KOSPI200
                    : FuturesMarketType.KOSDAQ150;
            List<FuturesInvestorDaily> futHistory = futuresInvestorDailyRepository
                    .findAllByMarketTypeAndDateBetweenOrderByDateAsc(futMarket,
                            subHistory.get(0).getDate().minusDays(10), subHistory.get(0).getDate());

            List<FuturesInvestorDaily> last5Fut = futHistory.stream()
                    .sorted(Comparator.comparing(FuturesInvestorDaily::getDate).reversed())
                    .limit(5).collect(Collectors.toList());

            futForeignNet5d = last5Fut.stream().mapToLong(f -> f.getForeignerNetBuyAmount().longValue()).sum();
            futIndividualNet5d = last5Fut.stream().mapToLong(f -> f.getPersonalNetBuyAmount().longValue()).sum();
            futInstitutionalNet5d = last5Fut.stream().mapToLong(f -> f.getInstitutionNetBuyAmount().longValue()).sum();
        } catch (Exception e) {
            log.warn("Failed to fetch futures trend: {}", e.getMessage());
        }

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
                .commonRisingStockCount(breadth.rising)
                .commonFallingStockCount(breadth.falling)
                .commonMarketBreadthIndex(breadth.index)
                .breadth5dAvg(breadth.avg5d)
                .breadth20dAvg(breadth.avg20d)
                .breadth60dAvg(breadth.avg60d)
                .breadthDate(breadth.date)
                .vkospi(vkospi)
                .futuresForeignNet5d(futForeignNet5d)
                .futuresIndividualNet5d(futIndividualNet5d)
                .futuresInstitutionalNet5d(futInstitutionalNet5d)
                .build();
    }

    private BreadthResult calculateMarketBreadth(MarketType market, LocalDate date) {
        try {
            List<LocalDate> recentDates = stockDailyDataRepository.findDistinctDatesByMarketName(market,
                    PageRequest.of(0, 60));
            List<BreadthHistoryDto> history = stockDailyDataRepository.findBreadthHistoryByDates(market, recentDates);
            if (history.isEmpty())
                return new BreadthResult(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        "N/A");

            BreadthHistoryDto latest = history.get(0);
            return new BreadthResult(latest.getRisingCount(), latest.getFallingCount(),
                    BigDecimal.valueOf(latest.getBreadthIndex()).setScale(2, RoundingMode.HALF_UP),
                    calculateAvgBreadth(history, 5), calculateAvgBreadth(history, 20), calculateAvgBreadth(history, 60),
                    latest.getDate().toString());
        } catch (Exception e) {
            return new BreadthResult(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "N/A");
        }
    }

    private BigDecimal calculateAvgBreadth(List<BreadthHistoryDto> history, int days) {
        int count = Math.min(history.size(), days);
        if (count == 0)
            return BigDecimal.ZERO;
        double sum = history.stream().limit(count).mapToDouble(BreadthHistoryDto::getBreadthIndex).sum();
        return BigDecimal.valueOf(sum / count).setScale(2, RoundingMode.HALF_UP);
    }

    private TrendScoreResult calculateTrendScore(MarketType market, InvestorTrendInfo trend) {
        BigDecimal score = BigDecimal.ZERO;

        // 1. Spot Foreigner Score (25 pts)
        long foreignTarget = (market == MarketType.KOSPI) ? 500000 : 100000;
        double foreignRatio = (double) trend.getForeignNet5d() / foreignTarget;
        double foreignScoreRaw = (Math.max(-1.0, Math.min(1.0, foreignRatio)) + 1.0) / 2.0 * 25.0;
        score = score.add(new BigDecimal(foreignScoreRaw));

        // 2. Spot Institutional Score (15 pts)
        long instTarget = (market == MarketType.KOSPI) ? 250000 : 50000;
        double instRatio = (double) trend.getInstitutionalNet5d() / instTarget;
        double instScoreRaw = (Math.max(-1.0, Math.min(1.0, instRatio)) + 1.0) / 2.0 * 15.0;
        score = score.add(new BigDecimal(instScoreRaw));

        // 3. Market Breadth Score (30 pts)
        double currentBreadth = trend.getCommonMarketBreadthIndex().doubleValue();
        double avg5dBreadth = trend.getBreadth5dAvg().doubleValue();
        double avg20dBreadth = trend.getBreadth20dAvg().doubleValue();
        double avg60dBreadth = trend.getBreadth60dAvg().doubleValue();

        double alignmentScore = 0;
        if (avg5dBreadth > avg20dBreadth)
            alignmentScore += 5;
        if (avg20dBreadth > avg60dBreadth)
            alignmentScore += 5;

        double momentumScore = (Math.max(-20.0, Math.min(20.0, currentBreadth)) + 20.0) / 40.0 * 10.0;
        double stabilityScore = (Math.max(-20.0, Math.min(20.0, avg20dBreadth)) + 20.0) / 40.0 * 10.0;

        score = score.add(new BigDecimal(alignmentScore + momentumScore + stabilityScore));

        // Divergence Detection (-10 pts)
        try {
            Optional<IndexDailyData> idxOpt = indexDailyDataRepository
                    .findFirstByMarketNameOrderByDateDesc(market.name());
            if (idxOpt.isPresent()) {
                IndexDailyData idx = idxOpt.get();
                double changeRate = idx.getChangeRate() != null ? idx.getChangeRate() : 0.0;
                if (changeRate > 0.5 && currentBreadth < (avg5dBreadth - 5)) {
                    score = score.subtract(new BigDecimal("10.0"));
                }
            }
        } catch (Exception e) {
        }

        // 4. Futures Foreigner Score (20 pts)
        long futuresTarget = (market == MarketType.KOSPI) ? 200000 : 40000;
        double futuresRatio = (double) trend.getFuturesForeignNet5d() / futuresTarget;
        double futuresScoreRaw = (Math.max(-1.0, Math.min(1.0, futuresRatio)) + 1.0) / 2.0 * 20.0;
        score = score.add(new BigDecimal(futuresScoreRaw));

        // 5. VKOSPI Stability Score (10 pts)
        double vkospiScoreRaw = 5.0;
        if (trend.getVkospi() != null) {
            double v = trend.getVkospi().doubleValue();
            if (v <= 15.0)
                vkospiScoreRaw = 10.0;
            else if (v >= 25.0)
                vkospiScoreRaw = 0.0;
            else
                vkospiScoreRaw = 10.0 - (v - 15.0);
        }
        score = score.add(new BigDecimal(vkospiScoreRaw));

        score = score.max(BigDecimal.ZERO).setScale(1, RoundingMode.HALF_UP);
        return new TrendScoreResult(score, determineTrendDescription(score, trend));
    }

    private String determineTrendDescription(BigDecimal score, InvestorTrendInfo trend) {
        double s = score.doubleValue();
        String base = "";
        if (s >= 80)
            base = "강력한 상승장 (메이저 수급 집중)";
        else if (s >= 60)
            base = "상승 우위 (매수세 유입)";
        else if (s >= 40)
            base = "중립 (방향성 모색 횡보)";
        else if (s >= 20)
            base = "하락 우위 (매물 출회)";
        else
            base = "강력한 하락장 (투매 발생)";

        double currentB = trend.getCommonMarketBreadthIndex().doubleValue();
        double avg5B = trend.getBreadth5dAvg().doubleValue();
        if (currentB > avg5B + 10)
            base += " [상승세 가속] 종목별 순환매 유입";
        else if (currentB < avg5B - 10)
            base += " [추세 약화] 이탈 종목 증가";

        return base;
    }

    private TrendDirection calculateDirection(List<Long> values) {
        if (values.size() < 5)
            return TrendDirection.NEUTRAL;
        long currentAvg = (values.get(0) + values.get(1) + values.get(2)) / 3;
        long prevAvg = (values.get(2) + values.get(3) + values.get(4)) / 3;
        if (currentAvg > 0)
            return currentAvg > prevAvg ? TrendDirection.BUYING_ACCELERATED : TrendDirection.BUYING_SLOWED;
        if (currentAvg < 0)
            return currentAvg < prevAvg ? TrendDirection.SELLING_ACCELERATED : TrendDirection.SELLING_SLOWED;
        return TrendDirection.NEUTRAL;
    }

    private ValuationSignal determineValuationSignal(BigDecimal valuationScore, InvestorTrendInfo trend) {
        double score = valuationScore.doubleValue();
        if (score < 40) {
            // Low score (Fear/Value)
            if (trend.getForeignNet5d() > 0 || trend.getInstitutionalNet5d() > 0) {
                return ValuationSignal.UNDERVALUED_BUY;
            } else if (trend.getCommonMarketBreadthIndex().doubleValue() < -20) {
                return ValuationSignal.VALUE_TRAP;
            }
        } else if (score > 80) {
            // High score (Greed/Overvalued)
            if (trend.getForeignNet5d() < 0 && trend.getInstitutionalNet5d() < 0) {
                return ValuationSignal.OVERVALUED_CAUTION;
            }
        } else if (score >= 45 && score <= 55) {
            return ValuationSignal.FAIR_VALUE;
        }
        return ValuationSignal.NEUTRAL;
    }

    private TrendSignal determineTrendSignal(BigDecimal trendScore, InvestorTrendInfo trend) {
        double score = trendScore.doubleValue();
        double breadth = trend.getCommonMarketBreadthIndex().doubleValue();

        if (score > 70) {
            if (trend.getForeignNet5d() > 0 && breadth > trend.getBreadth5dAvg().doubleValue()) {
                return TrendSignal.HEALTHY_BULL;
            }
        } else if (score < 30) {
            if (breadth < -40 && trend.getForeignNet5d() < -500000) {
                return TrendSignal.PANIC_SELLING;
            }
            if (breadth < trend.getBreadth60dAvg().doubleValue() - 20) {
                return TrendSignal.OVERSOLD_REBOUND; // Potential for rebound if extreme
            }
        }

        // Divergence Check for Bull Trap
        if (score > 50 && breadth < trend.getBreadth5dAvg().doubleValue() - 15) {
            return TrendSignal.BULL_TRAP;
        }

        if (Math.abs(breadth) < 5 && Math.abs(trend.getBreadth5dAvg().doubleValue()) < 5) {
            return TrendSignal.STAGNANT;
        }

        return TrendSignal.NEUTRAL;
    }

    private String formatLargeNumber(BigDecimal value) {
        if (value == null)
            return "N/A";
        if (value.compareTo(new BigDecimal("1000000000000")) >= 0)
            return value.divide(new BigDecimal("1000000000000"), 2, RoundingMode.HALF_UP) + "조";
        if (value.compareTo(new BigDecimal("100000000")) >= 0)
            return value.divide(new BigDecimal("100000000"), 2, RoundingMode.HALF_UP) + "억";
        return value.toPlainString();
    }

    private static class BreadthResult {
        long rising, falling;
        BigDecimal index, avg5d, avg20d, avg60d;
        String date;

        BreadthResult(long r, long f, BigDecimal i, BigDecimal a5, BigDecimal a20, BigDecimal a60, String d) {
            this.rising = r;
            this.falling = f;
            this.index = i;
            this.avg5d = a5;
            this.avg20d = a20;
            this.avg60d = a60;
            this.date = d;
        }
    }

    private static class TrendScoreResult {
        BigDecimal score;
        String description;

        TrendScoreResult(BigDecimal s, String d) {
            this.score = s;
            this.description = d;
        }
    }

    private PredictionReport calculateTrendProbability(BigDecimal currentCape, BigDecimal currentYieldGap,
            BigDecimal valuationScore, BigDecimal trendScore, InvestorTrendInfo trendInfo,
            List<MarketValuationDto.TimeSeriesPoint> timeSeries, MarketType market) {

        try {
            // 1. Backtesting (Historical Similarity - Weighted KNN)
            int matches = 0;
            double weightedWinRate = 50.0;
            double weightedAvgReturn = 0.0;

            // KNN parameters
            final int K = 30;
            final int FORWARD_DAYS = 20; // 1 month approx

            if (timeSeries != null && indexDailyDataRepository != null && currentCape != null
                    && currentYieldGap != null) {
                // Fetch index history for return calculation (reuse repository)
                LocalDate end = LocalDate.now();
                LocalDate start = end.minusYears(10);
                List<IndexDailyData> indexData = indexDailyDataRepository
                        .findAllByMarketNameAndDateBetweenOrderByDateDesc(market.name(), start, end);

                if (indexData != null) {
                    Map<String, BigDecimal> priceMap = indexData.stream()
                            .filter(d -> d.getDate() != null && d.getClosingPrice() != null)
                            .collect(Collectors.toMap(d -> d.getDate().toString(), IndexDailyData::getClosingPrice,
                                    (a, b) -> a));

                    // 1-1. Filter Valid Historical Points & Calculate Stats for Normalization
                    List<MarketValuationDto.TimeSeriesPoint> validPoints = new ArrayList<>();
                    // Exclude recent points where forward return cannot be calculated
                    LocalDate latestPossibleDate = LocalDate.now().minusDays(30);

                    List<Double> capes = new ArrayList<>();
                    List<Double> yieldGaps = new ArrayList<>();

                    for (MarketValuationDto.TimeSeriesPoint p : timeSeries) {
                        if (p == null || p.getCape() == null || p.getYieldGap() == null || p.getDate() == null)
                            continue;

                        try {
                            LocalDate pDate = LocalDate.parse(p.getDate(), DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                            if (pDate.isAfter(latestPossibleDate))
                                continue; // Skip recent data

                            validPoints.add(p);
                            capes.add(p.getCape().doubleValue());
                            yieldGaps.add(p.getYieldGap().doubleValue());
                        } catch (Exception ignored) {
                        }
                    }

                    if (!validPoints.isEmpty()) {
                        // Calculate Mean & StdDev
                        double meanCape = capes.stream().mapToDouble(d -> d).average().orElse(0.0);
                        double stdCape = Math
                                .sqrt(capes.stream().mapToDouble(d -> Math.pow(d - meanCape, 2)).average().orElse(1.0));
                        if (stdCape == 0)
                            stdCape = 1.0;

                        double meanYg = yieldGaps.stream().mapToDouble(d -> d).average().orElse(0.0);
                        double stdYg = Math.sqrt(
                                yieldGaps.stream().mapToDouble(d -> Math.pow(d - meanYg, 2)).average().orElse(1.0));
                        if (stdYg == 0)
                            stdYg = 1.0;

                        // Normalize Current State
                        double zCapeCurr = (currentCape.doubleValue() - meanCape) / stdCape;
                        double zYgCurr = (currentYieldGap.doubleValue() - meanYg) / stdYg;

                        // 1-2. Calculate Distances (KNN)
                        List<KnnCandidate> candidates = new ArrayList<>();

                        for (MarketValuationDto.TimeSeriesPoint p : validPoints) {
                            double zCape = (p.getCape().doubleValue() - meanCape) / stdCape;
                            double zYg = (p.getYieldGap().doubleValue() - meanYg) / stdYg;

                            double distance = Math.sqrt(Math.pow(zCape - zCapeCurr, 2) + Math.pow(zYg - zYgCurr, 2));

                            // Calculate Future Return
                            LocalDate pDate = LocalDate.parse(p.getDate(), DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                            LocalDate targetDate = pDate.plusDays(30); // Calendar days approx

                            // Find closest future price
                            String targetDateStr = indexData.stream()
                                    .filter(d -> d.getDate() != null && !d.getDate().isBefore(targetDate))
                                    .min(Comparator.comparing(IndexDailyData::getDate))
                                    .map(d -> d.getDate().toString())
                                    .orElse(null);

                            if (targetDateStr != null && priceMap.containsKey(p.getDate())
                                    && priceMap.containsKey(targetDateStr)) {
                                double startPrice = priceMap.get(p.getDate()).doubleValue();
                                double endPrice = priceMap.get(targetDateStr).doubleValue();
                                double ret = (endPrice - startPrice) / startPrice;

                                candidates.add(new KnnCandidate(p.getDate(), distance, ret));
                            }
                        }

                        // 1-3. Select Selection & Aggregation
                        candidates.sort(Comparator.comparingDouble(c -> c.distance));
                        List<KnnCandidate> neighbors = candidates.stream().limit(K).collect(Collectors.toList());
                        matches = neighbors.size();

                        if (matches > 0) {
                            double sumWeights = neighbors.stream().mapToDouble(c -> c.weight).sum();
                            double sumWeightedPositive = neighbors.stream()
                                    .filter(c -> c.forwardReturn > 0)
                                    .mapToDouble(c -> c.weight)
                                    .sum();
                            double sumWeightedReturn = neighbors.stream()
                                    .mapToDouble(c -> c.forwardReturn * c.weight)
                                    .sum();

                            weightedWinRate = (sumWeights > 0) ? (sumWeightedPositive / sumWeights) * 100.0 : 50.0;
                            weightedAvgReturn = (sumWeights > 0) ? (sumWeightedReturn / sumWeights) * 100.0 : 0.0;
                        }
                    }
                }
            }

            HistoricalMatch historicalMatch = HistoricalMatch.builder()
                    .totalMatches(matches)
                    .positiveOutcomes((int) (matches * (weightedWinRate / 100.0))) // Approx for display
                    .negativeOutcomes(matches - (int) (matches * (weightedWinRate / 100.0)))
                    .winRate(weightedWinRate)
                    .averageReturn(weightedAvgReturn)
                    .build();

            // 2. Energy Score (Pup)
            // Feature Normalization
            double normTrend = (trendScore != null) ? trendScore.doubleValue() / 100.0 : 0.0; // 0~1
            double normForeign = 0.5;
            if (trendInfo != null) {
                Long fNet = trendInfo.getForeignNet5d();
                Long fFutNet = trendInfo.getFuturesForeignNet5d();
                long net = (fNet != null ? fNet : 0L) + (fFutNet != null ? fFutNet : 0L);
                normForeign = (net > 0) ? 0.8 : 0.2; // Simplified directional
            }

            double normBreadth = 0.5;
            if (trendInfo != null && trendInfo.getCommonMarketBreadthIndex() != null) {
                // -100 ~ 100 -> 0 ~ 1
                normBreadth = (trendInfo.getCommonMarketBreadthIndex().doubleValue() + 100.0) / 200.0;
                normBreadth = Math.max(0.0, Math.min(1.0, normBreadth));
            }

            // Weighted Sum: W1(0.4) + W2(0.3) + W3(0.3)
            double energyScore = (0.4 * normTrend) + (0.3 * normForeign) + (0.3 * normBreadth);

            // 3. V-KOSPI Adjustment
            if (trendInfo != null && trendInfo.getVkospi() != null && trendInfo.getVkospi().doubleValue() > 30) {
                if (valuationScore != null && valuationScore.doubleValue() > 60) {
                    energyScore *= 0.7; // 30% penalty
                } else if (valuationScore != null && valuationScore.doubleValue() < 30) {
                    energyScore *= 1.2; // 20% bonus (mean reversion)
                }
            }

            energyScore = Math.max(0.0, Math.min(1.0, energyScore));

            // 4. Combine for Time Horizons
            // Short-term (1w): Heavily dependent on Energy
            double pShort = energyScore * 100.0;

            // Medium-term (1m): Blend of Energy and Backtesting (Weighted KNN)
            double pMedium = (energyScore * 0.4 + (weightedWinRate / 100.0) * 0.6) * 100.0;

            // Long-term (3m): Fundamental (Valuation) dominant
            // Market-relative: use YG percentile within own market's distribution
            double pLong = 50.0; // default neutral
            if (currentYieldGap != null && timeSeries != null) {
                List<BigDecimal> historicalYGs = timeSeries.stream()
                        .map(MarketValuationDto.TimeSeriesPoint::getYieldGap)
                        .filter(Objects::nonNull)
                        .sorted()
                        .collect(Collectors.toList());
                if (!historicalYGs.isEmpty()) {
                    // Count how many historical YGs are lower (worse) than current
                    long countLower = historicalYGs.stream()
                            .filter(yg -> yg.compareTo(currentYieldGap) < 0).count();
                    // Higher current YG relative to history = more bullish
                    // Clamp to 5~95% to account for monthly sampling resolution limits
                    pLong = Math.max(5.0, Math.min(95.0,
                            (double) countLower / historicalYGs.size() * 100.0));
                }
            }

            return PredictionReport.builder()
                    .shortTerm(ProbabilityInfo.builder()
                            .upProbability(pShort).downProbability(100 - pShort)
                            .primaryReason(pShort > 50 ? "모멘텀 및 수급 양호" : "수급 악화 및 하락 에너지 우세").build())
                    .mediumTerm(ProbabilityInfo.builder()
                            .upProbability(pMedium).downProbability(100 - pMedium)
                            .primaryReason(matches > 5 ? "역사적 유사 국면 (" + matches + "회) 반영" : "밸류에이션 및 추세 혼합").build())
                    .longTerm(ProbabilityInfo.builder()
                            .upProbability(pLong).downProbability(100 - pLong)
                            .primaryReason("Yield Gap 및 펀더멘털 매력도 기반").build())
                    .historicalMatch(historicalMatch)
                    .build();

        } catch (Exception e) {
            log.error("Error calculating trend probability: {}", e.getMessage(), e);
            return PredictionReport.builder()
                    .shortTerm(ProbabilityInfo.builder().upProbability(50).downProbability(50).primaryReason("N/A")
                            .build())
                    .mediumTerm(ProbabilityInfo.builder().upProbability(50).downProbability(50).primaryReason("N/A")
                            .build())
                    .longTerm(ProbabilityInfo.builder().upProbability(50).downProbability(50).primaryReason("N/A")
                            .build())
                    .historicalMatch(HistoricalMatch.builder().build())
                    .build();
        }
    }
}
