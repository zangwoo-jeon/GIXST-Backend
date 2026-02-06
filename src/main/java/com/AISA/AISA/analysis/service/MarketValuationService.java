package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.dto.MarketValuationDto;
import com.AISA.AISA.kisStock.Entity.stock.FuturesInvestorDaily;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockBalanceSheet;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialStatement;
import com.AISA.AISA.kisStock.Entity.stock.StockMarketCap;
import com.AISA.AISA.kisStock.enums.MarketType;
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
                    if (year >= currentYear - 10 && year < currentYear) {
                        BigDecimal cpiPast = cpiMap.getOrDefault(year, currentCpi);
                        BigDecimal adj = s.getNetIncome().multiply(new BigDecimal("100000000"))
                                .multiply(currentCpi).divide(cpiPast, 0, RoundingMode.HALF_UP);
                        annualAdjSums.merge(year, adj, BigDecimal::add);
                        stockYears.computeIfAbsent(s.getStockCode(), k -> new HashSet<>()).add(year);
                    }
                } catch (Exception e) {
                }
            }

            for (String code : stockCodeSet) {
                if (stockYears.getOrDefault(code, Collections.emptySet()).size() >= 8)
                    stocksWithHistory.add(code);
            }

            BigDecimal avgEarnings10Y = annualAdjSums.isEmpty() ? BigDecimal.ZERO
                    : annualAdjSums.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(new BigDecimal(annualAdjSums.size()), 0, RoundingMode.HALF_UP);

            BigDecimal currentCape = BigDecimal.ZERO;
            if (avgEarnings10Y.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal mCapForCape = marketCaps.stream()
                        .filter(mc -> stocksWithHistory.contains(mc.getStock().getStockCode()))
                        .map(mc -> mc.getMarketCap() != null ? mc.getMarketCap() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .multiply(new BigDecimal("100000000"));
                currentCape = mCapForCape.divide(avgEarnings10Y, 2, RoundingMode.HALF_UP);
            }

            BigDecimal yieldGap = (currentCape.compareTo(BigDecimal.ZERO) > 0 && bondYield != null)
                    ? BigDecimal.ONE.divide(currentCape, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal(100)).subtract(bondYield).setScale(2, RoundingMode.HALF_UP)
                    : null;

            // 7. Scoring and Time Series
            List<MarketValuationDto.TimeSeriesPoint> timeSeries = generateTimeSeries(market, currentCape, bondYield);
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
            BigDecimal valuationScore = scoreDetails.getYieldGapScore().add(scoreDetails.getDeviationScore());
            BigDecimal bonus = scoreDetails.getCapeRangePosition().subtract(new BigDecimal("50"))
                    .multiply(new BigDecimal("0.2")).max(BigDecimal.ZERO);
            valuationScore = valuationScore.add(bonus);
            if (Boolean.TRUE.equals(scoreDetails.getYieldGapInversion()))
                valuationScore = valuationScore.add(new BigDecimal("25"));
            valuationScore = valuationScore.min(new BigDecimal("100.0")).setScale(1, RoundingMode.HALF_UP);

            InvestorTrendInfo trend = calculateInvestorTrend(market);
            SentimentSignal sentiment = (trend != null) ? determineHysteresisSentiment(market, valuationScore, trend)
                    : SentimentSignal.NEUTRAL;

            scoreDetails = scoreDetails.toBuilder()
                    .sentimentSignal(sentiment)
                    .build();

            TrendScoreResult trendResult = (trend != null) ? calculateTrendScore(market, trend)
                    : new TrendScoreResult(BigDecimal.ZERO, "수급 분석 불가");

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
                    .metadata(MarketValuationDto.MetadataInfo.builder()
                            .stockCount(validStockCodes.size())
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
                    + (sentiment != SentimentSignal.NEUTRAL ? " " + getSentimentContext(sentiment, trend) : "");

            return dto.toBuilder()
                    .valuationStrategy(aiRes.valuationStrategy() != null ? aiRes.valuationStrategy() : fallbackV)
                    .trendStrategy(aiRes.trendStrategy())
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
        LocalDate start = end.minusYears(11);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd");

        try {
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
        } catch (Exception e) {
            log.warn("Failed to fetch CPI map: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private List<MarketValuationDto.TimeSeriesPoint> generateTimeSeries(MarketType market, BigDecimal currentCape,
            BigDecimal currentBondYield) {
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

        Map<String, IndexDailyData> monthlySamples = new TreeMap<>();
        for (IndexDailyData d : indexData) {
            String key = d.getDate().format(monthFmt);
            monthlySamples.putIfAbsent(key, d);
        }

        for (Map.Entry<String, IndexDailyData> entry : monthlySamples.entrySet()) {
            BigDecimal indexPrice = entry.getValue().getClosingPrice();
            BigDecimal historicalCape = indexPrice.multiply(currentCape).divide(currentIndexPrice, 2,
                    RoundingMode.HALF_UP);
            BigDecimal historicalYieldGap = null;

            if (historicalCape.compareTo(BigDecimal.ZERO) > 0) {
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

    private MarketValuationDto.ScoreDetails calculateScoresRefined(BigDecimal currentCape, BigDecimal yieldGap,
            List<BigDecimal> sortedCapes, List<BigDecimal> chronologicalCapes, BigDecimal avgCape10Y,
            BigDecimal medianCape10Y, List<MarketValuationDto.TimeSeriesPoint> timeSeries) {

        BigDecimal ygScoreValue = BigDecimal.ZERO;
        boolean inversion = false;

        if (yieldGap != null) {
            inversion = yieldGap.compareTo(BigDecimal.ZERO) < 0;
            List<BigDecimal> historicalYGs = timeSeries.stream()
                    .map(MarketValuationDto.TimeSeriesPoint::getYieldGap)
                    .filter(Objects::nonNull)
                    .sorted()
                    .collect(Collectors.toList());

            if (!historicalYGs.isEmpty()) {
                BigDecimal minYg = historicalYGs.get(0);
                BigDecimal maxYg = historicalYGs.get(historicalYGs.size() - 1);
                BigDecimal range = maxYg.subtract(minYg);
                if (range.compareTo(BigDecimal.ZERO) > 0) {
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

        return MarketValuationDto.ScoreDetails.builder()
                .capeRangePosition(rangePosition.setScale(1, RoundingMode.HALF_UP))
                .distributionPercentile(percentile)
                .yieldGapScore(ygScoreValue.setScale(1, RoundingMode.HALF_UP))
                .deviationScore(devScoreValue.setScale(1, RoundingMode.HALF_UP))
                .yieldGapInversion(inversion)
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

    private String getSentimentContext(SentimentSignal signal, InvestorTrendInfo trend) {
        if (signal == SentimentSignal.NEUTRAL)
            return "";
        String individualFlow = trend != null
                ? formatLargeNumber(new BigDecimal(trend.getIndividualNet5d()).multiply(new BigDecimal("1000000")))
                : "N/A";
        switch (signal) {
            case INDIVIDUAL_FOMO:
                return "개인 투자자의 추격 매수세(" + individualFlow + ")가 강해 변동성에 유의하십시오.";
            case SMART_MONEY_INFLOW:
                return "저평가 구간에서 기관/외국인의 선제적 자금 유입이 포착됩니다.";
            case HEALTHY_BULL:
                return "실적 성장을 바탕으로 한 건강한 상승 추세가 유지되고 있습니다.";
            default:
                return "";
        }
    }

    private InvestorTrendInfo calculateInvestorTrend(MarketType market) {
        String marketCode = market == MarketType.KOSPI ? "0001" : "1001";
        List<MarketInvestorDaily> history = marketInvestorDailyRepository
                .findTop30ByMarketCodeOrderByDateDesc(marketCode);
        if (history.size() < 22)
            return null;
        return processTrend(market, history.subList(0, 20));
    }

    private InvestorTrendInfo processTrend(MarketType market, List<MarketInvestorDaily> subHistory) {
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

        BreadthResult breadth = calculateMarketBreadth(market, subHistory.get(0).getDate());
        BigDecimal vkospi = indexDailyDataRepository.findFirstByMarketNameOrderByDateDesc("VKOSPI")
                .map(IndexDailyData::getClosingPrice).orElse(null);

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

    private SentimentSignal determineHysteresisSentiment(MarketType market, BigDecimal score,
            InvestorTrendInfo currentTrend) {
        SentimentSignal sig0 = determineRawSentiment(score, currentTrend);
        String marketCode = market == MarketType.KOSPI ? "0001" : "1001";
        List<MarketInvestorDaily> history = marketInvestorDailyRepository
                .findTop30ByMarketCodeOrderByDateDesc(marketCode);
        if (history.size() < 22)
            return sig0;

        SentimentSignal sig1 = determineRawSentiment(score, processTrend(market, history.subList(1, 21)));
        SentimentSignal sig2 = determineRawSentiment(score, processTrend(market, history.subList(2, 22)));

        if (sig0 == sig1 || sig0 == sig2 || (sig0 == SentimentSignal.INDIVIDUAL_FOMO && score.doubleValue() >= 90))
            return sig0;
        return sig1;
    }

    private SentimentSignal determineRawSentiment(BigDecimal totalScore, InvestorTrendInfo trend) {
        double score = totalScore.doubleValue();
        if (score > 80 && trend.getIndividualTrend() == TrendDirection.BUYING_ACCELERATED
                && trend.getForeignNet5d() < 0)
            return SentimentSignal.INDIVIDUAL_FOMO;
        if (score < 40 && (trend.getForeignNet5d() > 0 || trend.getInstitutionalNet5d() > 0))
            return SentimentSignal.SMART_MONEY_INFLOW;
        if (score > 60 && trend.getForeignRelativeStrength() > 1.2)
            return SentimentSignal.HEALTHY_BULL;
        return SentimentSignal.NEUTRAL;
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
}
