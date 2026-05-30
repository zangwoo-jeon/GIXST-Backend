package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.dto.MarketValuationDto;
import com.AISA.AISA.kisStock.Entity.stock.FuturesInvestorDaily;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockBalanceSheet;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialStatement;
import com.AISA.AISA.kisStock.Entity.stock.StockMarketCap;
import com.AISA.AISA.kisStock.dto.Index.IndexChartInfoDto;
import com.AISA.AISA.kisStock.dto.StockPrice.StockPriceDto;
import com.AISA.AISA.kisStock.enums.MarketType;
import com.AISA.AISA.kisStock.kisService.KisIndexService;
import com.AISA.AISA.kisStock.kisService.KisStockService;
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
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final com.AISA.AISA.portfolio.macro.service.EcosService ecosService;
    private final StockDailyDataRepository stockDailyDataRepository;
    private final GeminiService geminiService;
    private final KisIndexService kisIndexService;
    private final KisStockService kisStockService;

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
                    // Expand range to support rolling 10-year average for historical CAPE time
                    // series
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

            // Use only the recent 10 years for current CAPE (annualAdjSums has up to 20
            // years for historical rolling)
            BigDecimal avgEarnings10Y = BigDecimal.ZERO;
            {
                List<BigDecimal> recent10YEarnings = new ArrayList<>();
                for (int y = currentYear - 10; y < currentYear; y++) {
                    BigDecimal earnings = annualAdjSums.get(y);
                    if (earnings != null)
                        recent10YEarnings.add(earnings);
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
            // 월별 CPI YoY 맵을 한 번 빌드해서 generateTimeSeries와 current 값 양쪽에 재사용
            Map<YearMonth, BigDecimal> cpiYoyMonthlyMap = buildCpiYoyMonthlyMap(getMonthlyCpiMap());
            List<MarketValuationDto.TimeSeriesPoint> timeSeries = generateTimeSeries(market, currentCape, bondYield,
                    annualAdjSums, marketCaps, stocksWithHistory, cpiMap, currentCpi, cpiYoyMonthlyMap);
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
            // YG inversion penalty: ygScore에 이미 분포 위치가 반영되므로 추가 보정은 축소
            // 극단적 inversion (상위 75% 이상)일 때만 최대 10점 추가
            if (Boolean.TRUE.equals(scoreDetails.getYieldGapInversion())
                    && scoreDetails.getYieldGapPercentile() != null) {
                double ygPct = scoreDetails.getYieldGapPercentile().doubleValue();
                double inversionPenalty = Math.max(0, (ygPct - 75.0) / 25.0) * 10.0;
                valuationScore = valuationScore.add(new BigDecimal(inversionPenalty));
            }
            valuationScore = valuationScore.min(new BigDecimal("100.0")).setScale(1, RoundingMode.HALF_UP);

            InvestorTrendInfo trend = calculateInvestorTrend(market, includeBreadth);
            TrendScoreResult trendResult = (trend != null) ? calculateTrendScore(market, trend)
                    : new TrendScoreResult(BigDecimal.ZERO, "수급 분석 불가");

            ValuationSignal vSignal = determineValuationSignal(valuationScore);
            TrendSignal tSignal = (trend != null) ? determineTrendSignal(trendResult.score, trend)
                    : TrendSignal.NEUTRAL;

            ValuationAnalysis vAnalysis = ValuationAnalysis.builder()
                    .score(valuationScore)
                    .state(determineGrade(valuationScore))
                    .actionSignal(vSignal)
                    .build();

            TrendAnalysis tAnalysis = TrendAnalysis.builder()
                    .score(trendResult.score)
                    .state(trendResult.description)
                    .actionSignal(tSignal)
                    .build();

            CombinedSignal cSignal = determineCombinedSignal(vSignal, tSignal);
            InvestmentStrategy invStrategy = InvestmentStrategy.builder()
                    .finalActionSignal(cSignal)
                    .combinedStrategyText("")
                    .build();

            scoreDetails = scoreDetails.toBuilder()
                    .build();

            BigDecimal coverage = rawTotalMarketCapUnits.compareTo(BigDecimal.ZERO) > 0
                    ? marketCaps.stream().filter(mc -> stocksWithHistory.contains(mc.getStock().getStockCode()))
                            .map(mc -> mc.getMarketCap()).reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(rawTotalMarketCapUnits, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100))
                    : BigDecimal.ZERO;

            MarketValuationDto dto = MarketValuationDto.builder()
                    .market(market)
                    .marketDescription(market.getDescription())
                    .valuationAnalysis(vAnalysis)
                    .trendAnalysis(tAnalysis)
                    .investmentStrategy(invStrategy)
                    .valuation(MarketValuationDto.ValuationInfo.builder()
                            .per(per).pbr(pbr).cape(currentCape).yieldGap(yieldGap).bondYield(bondYield).build())
                    .scoreDetails(scoreDetails)
                    .investorTrend(trend)
                    .predictionReport(
                            calculateTrendProbability(currentCape, yieldGap,
                                    computeCurrentCpiYoyMonthly(cpiYoyMonthlyMap),
                                    valuationScore, trendResult.score,
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
                    .theoreticalAnchor(calculateTheoreticalAnchor(currentCape))
                    .domesticEconomy(calculateDomesticEconomy())
                    .build();

            // 8-1. KNN 결과 분리 처리:
            //  - outcomeDistribution이 매트릭스를 명확히 반박할 때만 신호 강등 (rebuttalReasons)
            //  - OOD/winRateCI 불확실성은 강등 없이 flag로만 분리 표시 (현재 진단은 추세대로 유지)
            SignalAdjustment adjustment = applyKnnAdjustment(cSignal, dto.getPredictionReport());
            boolean signalDegraded = adjustment.adjustedSignal != adjustment.originalSignal;
            if (signalDegraded || adjustment.oodFlag || adjustment.uncertaintyFlag) {
                InvestmentStrategy.InvestmentStrategyBuilder invBuilder = dto.getInvestmentStrategy()
                        .toBuilder();
                if (signalDegraded) {
                    invBuilder.finalActionSignal(adjustment.adjustedSignal)
                            .preKnnAdjustmentSignal(adjustment.originalSignal)
                            .knnAdjustmentReasons(adjustment.rebuttalReasons);
                    cSignal = adjustment.adjustedSignal;
                }
                if (adjustment.oodFlag) {
                    invBuilder.oodFlag(true);
                }
                if (adjustment.uncertaintyFlag) {
                    invBuilder.uncertaintyFlag(true);
                }
                if (!adjustment.uncertaintyReasons.isEmpty()) {
                    invBuilder.uncertaintyReasons(adjustment.uncertaintyReasons);
                }
                dto = dto.toBuilder().investmentStrategy(invBuilder.build()).build();
            }

            // 9. AI Strategy (Split)
            // KOSDAQ 등 자체 VKOSPI가 없는 시장도 KOSPI VKOSPI 차용하여 일관성 유지
            BigDecimal effectiveVkospi = getEffectiveVkospi(dto.getInvestorTrend());
            GeminiService.StrategyResult aiRes = geminiService.generateMarketStrategy(dto, effectiveVkospi);
            String fallbackV = determineStrategy(valuationScore)
                    + " " + getValuationSentimentContext(vSignal)
                    + " " + getTrendSentimentContext(tSignal);

            String finalValuationStrategy = aiRes != null && aiRes.valuationStrategy() != null
                    ? aiRes.valuationStrategy()
                    : fallbackV;
            String finalTrendStrategy = aiRes != null ? aiRes.trendStrategy() : null;
            String finalCombinedStrategy = aiRes != null && aiRes.combinedStrategy() != null
                    ? aiRes.combinedStrategy()
                    : "데이터를 종합 분석 중입니다.";

            ValuationAnalysis finalVAnalysis = dto.getValuationAnalysis().toBuilder()
                    .strategyText(finalValuationStrategy)
                    .build();

            TrendAnalysis finalTAnalysis = dto.getTrendAnalysis().toBuilder()
                    .strategyText(finalTrendStrategy)
                    .build();

            InvestmentStrategy finalInvStrategy = dto.getInvestmentStrategy().toBuilder()
                    .combinedStrategyText(finalCombinedStrategy)
                    .build();

            MarketValuationDto finalDto = dto.toBuilder()
                    .valuationAnalysis(finalVAnalysis)
                    .trendAnalysis(finalTAnalysis)
                    .investmentStrategy(finalInvStrategy)
                    .build();

            // theoreticalAnchor.explanation을 한국 시장 컨텍스트 기반 Gemini explanation으로 enrich.
            // 실패 시 calculateTheoreticalAnchor의 정적 fallback explanation 유지.
            if (finalDto.getTheoreticalAnchor() != null) {
                String geminiExp = geminiService.generateTheoreticalAnchorExplanation(
                        market, currentCape, finalDto.getTheoreticalAnchor(),
                        finalDto.getMetadata() != null ? finalDto.getMetadata().getHistoricalStats() : null,
                        finalDto.getScoreDetails() != null ? finalDto.getScoreDetails().getDistributionPercentile() : null);
                if (geminiExp != null && !geminiExp.isBlank()) {
                    MarketValuationDto.TheoreticalAnchor enrichedAnchor = finalDto.getTheoreticalAnchor()
                            .toBuilder().explanation(geminiExp).build();
                    finalDto = finalDto.toBuilder().theoreticalAnchor(enrichedAnchor).build();
                }
            }

            // VERY_LOW (OOD) 상황에서만 시나리오 추천 생성. 캐시에 함께 저장됨.
            // effectiveVkospi는 위 generateMarketStrategy 호출 시 이미 계산했으므로 재사용
            if (finalDto.getPredictionReport() != null
                    && finalDto.getPredictionReport().getKnnStats() != null
                    && finalDto.getPredictionReport().getKnnStats()
                            .getConfidence() == MarketValuationDto.KnnConfidence.VERY_LOW
                    && finalDto.getPredictionReport().getOutcomeDistribution() != null) {
                MarketValuationDto.ScenarioRecommendation scenarioRec = geminiService
                        .generateScenarioRecommendation(finalDto, effectiveVkospi);
                if (scenarioRec != null) {
                    finalDto = finalDto.toBuilder().scenarioRecommendation(scenarioRec).build();
                }
            }

            // 종합 분석 narrative 생성 — 모든 dto 데이터가 채워진 후 마지막 단계에서 호출.
            // 4축(밸류에이션·추세·리스크·내수) + 종합 전략 + 한 줄 요약 + 행동 지침을 구조화된 객체로 반환.
            MarketValuationDto.ComprehensiveAnalysis comprehensive = geminiService
                    .generateComprehensiveAnalysis(finalDto, effectiveVkospi);
            if (comprehensive != null) {
                finalDto = finalDto.toBuilder().comprehensiveAnalysis(comprehensive).build();
            }

            return finalDto;

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
            // ecosService.fetchCPI는 DB 적재된 ECOS CPI를 반환 (kisMacroService 경로는 빈 결과 반환)
            List<MacroIndicatorDto> cpiList = ecosService.fetchCPI(start.format(fmt), end.format(fmt));

            if (cpiList == null || cpiList.isEmpty()) {
                log.warn("CPI fetch returned empty from ecosService.fetchCPI ({} ~ {})",
                        start.format(fmt), end.format(fmt));
                return Collections.emptyMap();
            }

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
            log.info("CPI annual map loaded: {} years ({} ~ {})",
                    resultMap.size(),
                    resultMap.keySet().stream().mapToInt(Integer::intValue).min().orElse(-1),
                    resultMap.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1));
            return resultMap;
        } catch (Exception e) {
            log.warn("Failed to fetch CPI map: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private List<MarketValuationDto.TimeSeriesPoint> generateTimeSeries(MarketType market, BigDecimal currentCape,
            BigDecimal currentBondYield, Map<Integer, BigDecimal> annualAdjSums,
            List<StockMarketCap> marketCaps, Set<String> stocksWithHistory,
            Map<Integer, BigDecimal> cpiMap, BigDecimal currentCpi,
            Map<YearMonth, BigDecimal> cpiYoyMonthlyMap) {
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

        // KNN 특성용 시계열 데이터 로드 — 일별 데이터를 한 번에 끌어와서 5일 윈도우 미리 계산
        TreeMap<LocalDate, Long> foreignNet5dByDate = buildForeignNet5dByDate(market, start, end);
        TreeMap<LocalDate, BigDecimal> vkospiByDate = buildVkospiByDate(start, end);
        TreeMap<LocalDate, Double> breadth5dByDate = buildBreadth5dByDate(market);
        // CPI YoY는 외부에서 월별 trailing 12개월 YoY 맵으로 전달받음 (인플레이션 trend 정밀 추적)

        Map<String, IndexDailyData> monthlySamples = new TreeMap<>();
        for (IndexDailyData d : indexData) {
            DateTimeFormatter yyyyMM = DateTimeFormatter.ofPattern("yyyy-MM");
            String key = d.getDate().format(yyyyMM);
            monthlySamples.putIfAbsent(key, d);
        }

        for (Entry<String, IndexDailyData> entry : monthlySamples.entrySet()) {
            IndexDailyData d = entry.getValue();
            String fullDate = d.getDate().toString();
            String monthKey = entry.getKey();
            int pointYear = d.getDate().getYear();
            LocalDate sampleDate = d.getDate();

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

            // KNN 특성 매칭: 샘플 시점 이전 가장 가까운 영업일의 5일 누적/평균값 사용
            Long sampleForeignNet5d = null;
            Entry<LocalDate, Long> fE = foreignNet5dByDate.floorEntry(sampleDate);
            if (fE != null)
                sampleForeignNet5d = fE.getValue();

            BigDecimal sampleVkospi = null;
            Entry<LocalDate, BigDecimal> vE = vkospiByDate.floorEntry(sampleDate);
            if (vE != null)
                sampleVkospi = vE.getValue();

            BigDecimal sampleBreadth5d = null;
            Entry<LocalDate, Double> bE = breadth5dByDate.floorEntry(sampleDate);
            if (bE != null)
                sampleBreadth5d = BigDecimal.valueOf(bE.getValue()).setScale(2, RoundingMode.HALF_UP);

            // 월별 trailing 12M YoY로 정밀 조회 (같은 연도 내에서도 월별로 다른 값)
            YearMonth sampleYm = YearMonth.from(sampleDate);
            BigDecimal sampleCpiYoy = (cpiYoyMonthlyMap != null)
                    ? cpiYoyMonthlyMap.get(sampleYm)
                    : null;

            points.add(MarketValuationDto.TimeSeriesPoint.builder()
                    .date(fullDate)
                    .cape(historicalCape)
                    .yieldGap(historicalYieldGap)
                    .vkospi(sampleVkospi)
                    .foreignNet5d(sampleForeignNet5d)
                    .breadth5d(sampleBreadth5d)
                    .cpiYoy(sampleCpiYoy)
                    .build());
        }
        return points;
    }

    /**
     * 외국인 5일 누적 순매수 시계열 구축.
     * 일별 marketInvestorDaily에서 직전 5영업일 합을 미리 계산해 TreeMap으로 반환.
     */
    private TreeMap<LocalDate, Long> buildForeignNet5dByDate(MarketType market, LocalDate start, LocalDate end) {
        TreeMap<LocalDate, Long> result = new TreeMap<>();
        try {
            String marketCode = (market == MarketType.KOSPI) ? "0001" : "1001";
            List<MarketInvestorDaily> investorData = marketInvestorDailyRepository
                    .findAllByMarketCodeAndDateBetweenOrderByDateAsc(marketCode, start, end);
            for (int i = 4; i < investorData.size(); i++) {
                long sum = 0;
                for (int j = i - 4; j <= i; j++) {
                    BigDecimal v = investorData.get(j).getForeignerNetBuy();
                    if (v != null)
                        sum += v.longValue();
                }
                result.put(investorData.get(i).getDate(), sum);
            }
        } catch (Exception e) {
            log.warn("Failed to build foreignNet5d series for {}: {}", market, e.getMessage());
        }
        return result;
    }

    /**
     * VKOSPI 일별 종가 시계열 구축.
     * KOSDAQ도 KOSPI VKOSPI를 차용 (한국 시장 전체 위험 지표로 간주).
     */
    private TreeMap<LocalDate, BigDecimal> buildVkospiByDate(LocalDate start, LocalDate end) {
        TreeMap<LocalDate, BigDecimal> result = new TreeMap<>();
        try {
            List<IndexDailyData> vkospiData = indexDailyDataRepository
                    .findAllByMarketNameAndDateBetweenOrderByDateDesc("VKOSPI", start, end);
            for (IndexDailyData v : vkospiData) {
                if (v.getDate() != null && v.getClosingPrice() != null) {
                    result.put(v.getDate(), v.getClosingPrice());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to build VKOSPI series: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Breadth 5일 평균 시계열 구축.
     * StockDailyData에서 모든 영업일의 (상승종목-하락종목)/전체 비율을 집계 후 5일 평균.
     */
    private TreeMap<LocalDate, Double> buildBreadth5dByDate(MarketType market) {
        TreeMap<LocalDate, Double> result = new TreeMap<>();
        try {
            List<LocalDate> allDates = stockDailyDataRepository.findDistinctDatesByMarketName(market,
                    PageRequest.of(0, 5000));
            if (allDates.isEmpty())
                return result;
            List<BreadthHistoryDto> history = stockDailyDataRepository.findBreadthHistoryByDates(market, allDates);
            // findBreadthHistoryByDates는 date desc 정렬 — 5일 윈도우 계산을 위해 asc로 뒤집음
            history.sort(Comparator.comparing(BreadthHistoryDto::getDate));
            for (int i = 4; i < history.size(); i++) {
                double sum = 0.0;
                for (int j = i - 4; j <= i; j++) {
                    sum += history.get(j).getBreadthIndex();
                }
                result.put(history.get(i).getDate(), sum / 5.0);
            }
        } catch (Exception e) {
            log.warn("Failed to build breadth5d series for {}: {}", market, e.getMessage());
        }
        return result;
    }

    /**
     * 월별 CPI 맵 조회 (YearMonth → CPI 값).
     * ecosService.fetchCPI 응답(yyyyMMdd 형식)을 YearMonth 키로 매핑.
     */
    private Map<YearMonth, BigDecimal> getMonthlyCpiMap() {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusYears(21);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd");

        try {
            List<MacroIndicatorDto> cpiList = ecosService.fetchCPI(start.format(fmt), end.format(fmt));
            if (cpiList == null || cpiList.isEmpty()) {
                log.warn("Monthly CPI fetch returned empty");
                return Collections.emptyMap();
            }
            Map<YearMonth, BigDecimal> map = new HashMap<>();
            for (MacroIndicatorDto dto : cpiList) {
                try {
                    int year = Integer.parseInt(dto.getDate().substring(0, 4));
                    int month = Integer.parseInt(dto.getDate().substring(4, 6));
                    map.put(YearMonth.of(year, month), new BigDecimal(dto.getValue()));
                } catch (Exception ignored) {
                }
            }
            log.info("Monthly CPI map loaded: {} months", map.size());
            return map;
        } catch (Exception e) {
            log.warn("Failed to fetch monthly CPI: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 월별 YoY 맵에서 가장 최근 월의 trailing YoY 반환 (currentCpiYoy로 사용).
     */
    private BigDecimal computeCurrentCpiYoyMonthly(Map<YearMonth, BigDecimal> monthlyYoyMap) {
        if (monthlyYoyMap == null || monthlyYoyMap.isEmpty())
            return null;
        YearMonth latest = monthlyYoyMap.keySet().stream()
                .max(YearMonth::compareTo).orElse(null);
        return latest != null ? monthlyYoyMap.get(latest) : null;
    }

    /**
     * 월별 trailing 12개월 YoY 맵 빌드.
     * yoy[YYYY-MM] = (cpi[YYYY-MM] - cpi[YYYY-1-MM]) / cpi[YYYY-1-MM] * 100
     * 같은 연도 내에서도 월별로 값이 변함 (인플레 trend 정밀 추적).
     */
    private Map<YearMonth, BigDecimal> buildCpiYoyMonthlyMap(
            Map<YearMonth, BigDecimal> monthlyCpi) {
        Map<YearMonth, BigDecimal> yoyMap = new HashMap<>();
        if (monthlyCpi == null || monthlyCpi.isEmpty())
            return yoyMap;
        for (Entry<YearMonth, BigDecimal> e : monthlyCpi.entrySet()) {
            YearMonth ym = e.getKey();
            BigDecimal curr = e.getValue();
            BigDecimal prev = monthlyCpi.get(ym.minusYears(1));
            if (curr == null || prev == null || prev.compareTo(BigDecimal.ZERO) == 0)
                continue;
            BigDecimal yoy = curr.subtract(prev)
                    .divide(prev, 6, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal(100))
                    .setScale(2, RoundingMode.HALF_UP);
            yoyMap.put(ym, yoy);
        }
        return yoyMap;
    }

    /**
     * 현재 시점의 CPI YoY 추정. cpiMap에서 가장 최근 가용 연도의 YoY를 반환.
     * 현재 연도의 CPI가 부분 데이터로 들어와 있다면 그것의 YoY를 우선 사용.
     */
    private BigDecimal computeCurrentCpiYoy(Map<Integer, BigDecimal> cpiMap) {
        if (cpiMap == null || cpiMap.isEmpty())
            return null;
        Map<Integer, BigDecimal> yoyMap = buildCpiYoyMap(cpiMap);
        if (yoyMap.isEmpty())
            return null;
        int latest = yoyMap.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
        return latest >= 0 ? yoyMap.get(latest) : null;
    }

    /**
     * 연간 CPI YoY 변화율 맵 빌드. yoy[year] = (cpi[year] - cpi[year-1]) / cpi[year-1] * 100
     * cpiMap이 비어있거나 데이터 부족 시 빈 Map 반환.
     */
    private Map<Integer, BigDecimal> buildCpiYoyMap(Map<Integer, BigDecimal> cpiMap) {
        Map<Integer, BigDecimal> yoyMap = new HashMap<>();
        if (cpiMap == null || cpiMap.isEmpty())
            return yoyMap;
        for (Entry<Integer, BigDecimal> e : cpiMap.entrySet()) {
            int year = e.getKey();
            BigDecimal curr = e.getValue();
            BigDecimal prev = cpiMap.get(year - 1);
            if (curr == null || prev == null || prev.compareTo(BigDecimal.ZERO) == 0)
                continue;
            BigDecimal yoy = curr.subtract(prev)
                    .divide(prev, 6, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal(100))
                    .setScale(2, RoundingMode.HALF_UP);
            yoyMap.put(year, yoy);
        }
        return yoyMap;
    }

    /**
     * 효과적 VKOSPI 조회. trendInfo.vkospi가 null이면 (KOSDAQ 등) KOSPI VKOSPI를 차용.
     * 실시간 API → DB fallback 순서.
     */
    private BigDecimal getEffectiveVkospi(MarketValuationDto.InvestorTrendInfo trendInfo) {
        BigDecimal vkospi = (trendInfo != null) ? trendInfo.getVkospi() : null;
        if (vkospi != null)
            return vkospi;
        try {
            IndexChartInfoDto vkospiStatus = kisIndexService.getIndexStatus("VKOSPI");
            if (vkospiStatus != null && vkospiStatus.getCurrentIndices() != null) {
                return new BigDecimal(vkospiStatus.getCurrentIndices());
            }
        } catch (Exception e) {
            log.warn("Failed to fetch real-time VKOSPI, falling back to DB: {}", e.getMessage());
        }
        return indexDailyDataRepository
                .findFirstByMarketNameOrderByDateDesc("VKOSPI")
                .map(IndexDailyData::getClosingPrice).orElse(null);
    }

    /**
     * 선형 보간 기반 백분위수 계산. sortedValues는 오름차순 정렬 가정.
     * percentile은 0-100 범위. R-7 방식 (numpy.percentile 기본값과 동일).
     */
    private double percentileOf(double[] sortedValues, double percentile) {
        if (sortedValues.length == 0)
            return 0.0;
        if (sortedValues.length == 1)
            return sortedValues[0];
        double rank = (percentile / 100.0) * (sortedValues.length - 1);
        int lowerIdx = (int) Math.floor(rank);
        int upperIdx = (int) Math.ceil(rank);
        if (lowerIdx == upperIdx)
            return sortedValues[lowerIdx];
        double weight = rank - lowerIdx;
        return sortedValues[lowerIdx] * (1.0 - weight) + sortedValues[upperIdx] * weight;
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
        // Higher percentile = current YG is lower than most historical points = more
        // overvalued
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
        if (score >= 90)
            return "EXTREME_GREED";
        if (score >= 75)
            return "STRONG_OVERHEATED";
        if (score >= 60)
            return "OVERHEATED";
        if (score >= 40)
            return "FAIR";
        if (score >= 20)
            return "UNDERVALUED";
        return "EXTREME_FEAR";
    }

    /**
     * Shiller CAPE 기반 10년 연환산 기대 실질 수익률 추정.
     * 학계 표준: E[10Y annualized real return] ≈ 1/CAPE × 100 (earnings yield).
     * S&P 500 백테스트(1881~) R²≈0.40. ±1σ 추정치는 약 ±2.5%p.
     * KNN과 독립적이라 OOD 상황에서도 valid한 anchor — 단기 KNN이 침묵해도 장기 답 제공.
     */
    private MarketValuationDto.TheoreticalAnchor calculateTheoreticalAnchor(BigDecimal cape) {
        if (cape == null || cape.compareTo(BigDecimal.ZERO) <= 0)
            return null;

        BigDecimal earningsYield = BigDecimal.ONE
                .divide(cape, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal sigma = new BigDecimal("2.5"); // %p, 학술 회귀 표준오차 근사
        BigDecimal lowerBound = earningsYield.subtract(sigma).setScale(2, RoundingMode.HALF_UP);
        BigDecimal upperBound = earningsYield.add(sigma).setScale(2, RoundingMode.HALF_UP);

        // CAPE regime 분류 — Shiller 자신의 1881~ 분포 기반
        String regime;
        if (cape.compareTo(new BigDecimal("12")) <= 0)
            regime = "UNDERVALUED"; // 역사적 저점 (1932, 1982, 2008 같은 시기)
        else if (cape.compareTo(new BigDecimal("20")) <= 0)
            regime = "FAIR"; // 역사 평균 약 16~17 부근
        else if (cape.compareTo(new BigDecimal("30")) <= 0)
            regime = "EXPENSIVE"; // 거품 진입 영역
        else
            regime = "EXTREME"; // 1929, 2000, 2021 같은 역사적 극단

        String interpretation;
        // 정적 explanation은 Gemini 호출 실패 시 fallback. 해외 시장 사례 인용 없는 안전 버전.
        String explanation;
        if (regime.equals("EXTREME")) {
            interpretation = String.format(
                    "CAPE %.2f는 역사적 상위 5%% 영역. 향후 10년 연환산 실질 수익률은 약 %.1f%% (±%.1f%%p)로 매우 낮은 수준이 기대됨.",
                    cape.doubleValue(), earningsYield.doubleValue(), sigma.doubleValue());
            explanation = String.format(
                    "현재 시장은 우리 데이터 10년 분포에서 최상위 고평가 영역에 진입했습니다. "
                            + "Shiller CAPE 모델은 향후 10년 연환산 실질 수익률을 약 %.1f%%로 추정하며, "
                            + "이 수준에서 시작한 장기 투자는 평균 이하의 수익률에 그칠 가능성이 시사됩니다. "
                            + "다만 이 모델은 미국 시장 100년 데이터 기반이라 한국 시장 적용은 방향성 참고 수준에 그칩니다.",
                    earningsYield.doubleValue());
        } else if (regime.equals("EXPENSIVE")) {
            interpretation = String.format(
                    "CAPE %.2f는 고평가 영역. 향후 10년 연환산 실질 수익률은 약 %.1f%% (±%.1f%%p)로 역사 평균(~6%%) 하회 가능성 높음.",
                    cape.doubleValue(), earningsYield.doubleValue(), sigma.doubleValue());
            explanation = String.format(
                    "현재 시장은 우리 데이터 10년 분포에서 상위 영역의 고평가 구간에 위치합니다. "
                            + "Shiller CAPE 모델은 향후 10년 연환산 실질 수익률을 약 %.1f%%로 추정하며, "
                            + "역사 평균을 하회할 가능성이 시사됩니다. "
                            + "이 모델은 미국 시장 100년 데이터 기반이라 한국 시장 적용은 방향성 참고 수준에 그칩니다.",
                    earningsYield.doubleValue());
        } else if (regime.equals("FAIR")) {
            interpretation = String.format(
                    "CAPE %.2f는 적정 영역. 향후 10년 연환산 실질 수익률은 약 %.1f%% (±%.1f%%p)로 역사 평균 수준 기대.",
                    cape.doubleValue(), earningsYield.doubleValue(), sigma.doubleValue());
            explanation = String.format(
                    "현재 시장은 우리 데이터 10년 분포의 중간 영역에서 거래되고 있습니다. "
                            + "Shiller CAPE 모델은 향후 10년 연환산 실질 수익률을 약 %.1f%%로 추정하며, "
                            + "장기적으로는 평균 수준의 수익률이 기대됩니다. "
                            + "이 모델은 미국 시장 100년 데이터 기반이라 한국 시장 적용은 방향성 참고 수준에 그칩니다.",
                    earningsYield.doubleValue());
        } else {
            interpretation = String.format(
                    "CAPE %.2f는 저평가 영역. 향후 10년 연환산 실질 수익률은 약 %.1f%% (±%.1f%%p)로 역사 평균 상회 기대.",
                    cape.doubleValue(), earningsYield.doubleValue(), sigma.doubleValue());
            explanation = String.format(
                    "현재 시장은 우리 데이터 10년 분포에서 하위 저평가 영역에 위치합니다. "
                            + "Shiller CAPE 모델은 향후 10년 연환산 실질 수익률을 약 %.1f%%로 추정하며, "
                            + "역사 평균을 상회할 가능성이 시사됩니다. "
                            + "이 모델은 미국 시장 100년 데이터 기반이라 한국 시장 적용은 방향성 참고 수준에 그칩니다.",
                    earningsYield.doubleValue());
        }

        String basis = "Shiller(1996, 2000) earnings yield 모델: 장기 연환산 수익률 ≈ 1/CAPE. "
                + "S&P 500 1881~ 백테스트 R²≈0.40. 한국 시장 적용은 방향성 valid, 절대치는 약한 외삽.";

        return MarketValuationDto.TheoreticalAnchor.builder()
                .expectedReturn10YAnnual(earningsYield)
                .lowerBound(lowerBound)
                .upperBound(upperBound)
                .capeRegime(regime)
                .interpretation(interpretation)
                .basis(basis)
                .explanation(explanation)
                .build();
    }

    /**
     * 한국 내수 펀더멘털 종합 — 연체율 4종 + 소비자심리지수.
     * 한국은행 ECOS 데이터(2019-12~)의 최신 시점값을 학계/한국은행 공식 평시 임계값과 비교.
     * 우리 6년 데이터로 백분위 뽑지 않고 *학계 임계값*과 비교 → 정직성·해석성 모두 확보.
     */
    private MarketValuationDto.DomesticEconomy calculateDomesticEconomy() {
        try {
            LocalDate end = LocalDate.now();
            LocalDate start = end.minusYears(1);
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd");
            String startStr = start.format(fmt);
            String endStr = end.format(fmt);

            // 학계/한국은행 공식 평시 임계값 (NORMAL → BORDERLINE → RISK 분기점)
            MarketValuationDto.DelinquencyMetric household = buildDelinquencyMetric(
                    ecosService.fetchDelinquencyHousehold(startStr, endStr),
                    "0.2~0.4%", new double[] { 0.4, 0.7 });
            MarketValuationDto.DelinquencyMetric corporate = buildDelinquencyMetric(
                    ecosService.fetchDelinquencyCorporate(startStr, endStr),
                    "0.4~0.7%", new double[] { 0.7, 1.0 });
            MarketValuationDto.DelinquencyMetric large = buildDelinquencyMetric(
                    ecosService.fetchDelinquencyLarge(startStr, endStr),
                    "0.05~0.15%", new double[] { 0.15, 0.3 });
            MarketValuationDto.DelinquencyMetric small = buildDelinquencyMetric(
                    ecosService.fetchDelinquencySmall(startStr, endStr),
                    "0.6~0.9%", new double[] { 0.9, 1.3 });

            MarketValuationDto.ConsumerSentimentMetric csi = buildConsumerSentimentMetric(
                    ecosService.fetchCSI(startStr, endStr));

            String interpretation = buildDomesticEconomyInterpretation(
                    household, corporate, large, small, csi);

            return MarketValuationDto.DomesticEconomy.builder()
                    .delinquencyHousehold(household)
                    .delinquencyCorporate(corporate)
                    .delinquencyLarge(large)
                    .delinquencySmall(small)
                    .consumerSentiment(csi)
                    .interpretation(interpretation)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to calculate domesticEconomy: {}", e.getMessage());
            return null;
        }
    }

    /**
     * MacroIndicatorDto 리스트에서 최신 시점값을 추출해 DelinquencyMetric으로 변환.
     * thresholds[0] 미만 = NORMAL, thresholds[1] 미만 = BORDERLINE, 그 이상 = RISK.
     */
    private MarketValuationDto.DelinquencyMetric buildDelinquencyMetric(
            List<com.AISA.AISA.portfolio.macro.dto.MacroIndicatorDto> data,
            String normalRange, double[] thresholds) {
        if (data == null || data.isEmpty())
            return null;
        com.AISA.AISA.portfolio.macro.dto.MacroIndicatorDto latest = data.stream()
                .max(Comparator.comparing(com.AISA.AISA.portfolio.macro.dto.MacroIndicatorDto::getDate))
                .orElse(null);
        if (latest == null || latest.getValue() == null)
            return null;
        try {
            BigDecimal value = new BigDecimal(latest.getValue()).setScale(2, RoundingMode.HALF_UP);
            double v = value.doubleValue();
            String regime;
            if (v < thresholds[0])
                regime = "NORMAL";
            else if (v < thresholds[1])
                regime = "BORDERLINE";
            else
                regime = "RISK";
            return MarketValuationDto.DelinquencyMetric.builder()
                    .value(value)
                    .asOfDate(formatEcosDate(latest.getDate()))
                    .regime(regime)
                    .normalRange(normalRange)
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    private MarketValuationDto.ConsumerSentimentMetric buildConsumerSentimentMetric(
            List<com.AISA.AISA.portfolio.macro.dto.MacroIndicatorDto> data) {
        if (data == null || data.isEmpty())
            return null;
        com.AISA.AISA.portfolio.macro.dto.MacroIndicatorDto latest = data.stream()
                .max(Comparator.comparing(com.AISA.AISA.portfolio.macro.dto.MacroIndicatorDto::getDate))
                .orElse(null);
        if (latest == null || latest.getValue() == null)
            return null;
        try {
            BigDecimal value = new BigDecimal(latest.getValue()).setScale(1, RoundingMode.HALF_UP);
            double v = value.doubleValue();
            String regime;
            if (v < 90.0)
                regime = "PESSIMISTIC";
            else if (v <= 110.0)
                regime = "NEUTRAL";
            else
                regime = "OPTIMISTIC";
            return MarketValuationDto.ConsumerSentimentMetric.builder()
                    .value(value)
                    .asOfDate(formatEcosDate(latest.getDate()))
                    .regime(regime)
                    .baseline("100 기준 (한국은행 소비자동향조사)")
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    private String formatEcosDate(String yyyymmdd) {
        if (yyyymmdd == null || yyyymmdd.length() < 8)
            return yyyymmdd;
        return yyyymmdd.substring(0, 4) + "-" + yyyymmdd.substring(4, 6) + "-" + yyyymmdd.substring(6, 8);
    }

    /**
     * 4개 연체율 + CSI를 종합해 평이한 진단 텍스트 생성.
     * 가장 위험한 regime을 기준으로 첫 문장 결정 → 각 지표 요약 → CSI 요약.
     */
    private String buildDomesticEconomyInterpretation(
            MarketValuationDto.DelinquencyMetric household,
            MarketValuationDto.DelinquencyMetric corporate,
            MarketValuationDto.DelinquencyMetric large,
            MarketValuationDto.DelinquencyMetric small,
            MarketValuationDto.ConsumerSentimentMetric csi) {
        boolean anyRisk = isRegime(household, "RISK") || isRegime(corporate, "RISK")
                || isRegime(large, "RISK") || isRegime(small, "RISK");
        boolean anyBorderline = isRegime(household, "BORDERLINE") || isRegime(corporate, "BORDERLINE")
                || isRegime(large, "BORDERLINE") || isRegime(small, "BORDERLINE");

        StringBuilder sb = new StringBuilder();
        if (anyRisk) {
            sb.append("내수 펀더멘털에 시스템 리스크 신호가 감지됩니다.");
        } else if (anyBorderline) {
            sb.append("내수 펀더멘털은 평시 정상 상단 경계에 위치합니다.");
        } else {
            sb.append("내수 펀더멘털은 전반적으로 평시 정상 수준입니다.");
        }
        sb.append(" 연체율 현황 — ");
        if (small != null)
            sb.append("중소기업 ").append(small.getValue()).append("%(").append(small.getRegime()).append("), ");
        if (corporate != null)
            sb.append("기업 전체 ").append(corporate.getValue()).append("%(").append(corporate.getRegime()).append("), ");
        if (large != null)
            sb.append("대기업 ").append(large.getValue()).append("%(").append(large.getRegime()).append("), ");
        if (household != null)
            sb.append("가계 ").append(household.getValue()).append("%(").append(household.getRegime()).append(")");
        if (sb.charAt(sb.length() - 2) == ',')
            sb.setLength(sb.length() - 2);
        sb.append(".");
        if (csi != null) {
            sb.append(" 소비자심리지수 ").append(csi.getValue());
            if ("PESSIMISTIC".equals(csi.getRegime()))
                sb.append("로 비관 우세, 소비 위축 신호.");
            else if ("OPTIMISTIC".equals(csi.getRegime()))
                sb.append("로 낙관 우세.");
            else
                sb.append("로 중립 영역.");
        }
        return sb.toString();
    }

    private boolean isRegime(MarketValuationDto.DelinquencyMetric m, String regime) {
        return m != null && regime.equals(m.getRegime());
    }

    private String determineStrategy(BigDecimal valuationScore) {
        double score = valuationScore.doubleValue();
        if (score >= 90)
            return "투자 심리 과열, 고평가 수준 극심";
        if (score >= 75)
            return "명확히 고평가, 단기 조정 가능성 높음";
        if (score >= 60)
            return "과열 구간 진입, 주의 필요";
        if (score >= 40)
            return "내재가치 대비 적정, 특별한 행동 불필요";
        if (score >= 20)
            return "매수 기회 가능, 다만 모멘텀/금리 고려 필요";
        return "매우 저평가, 위험과 기회 공존";
    }

    private String getValuationSentimentContext(ValuationSignal signal) {
        if (signal == null)
            return "";
        switch (signal) {
            case EXTREME_FEAR:
                return "[극도의 공포] 역사적 저평가 매력 부각";
            case UNDERVALUED:
                return "[저평가] 밸류에이션 매력 부각";
            case FAIR_VALUE:
                return "[적정 가치] 시장 균형 상태";
            case OVERHEATED:
                return "[과열] 밸류에이션 부담 확대";
            case STRONG_OVERHEATED:
                return "[극심한 과열] 명확한 고평가로 단기 조정 주의";
            case EXTREME_GREED:
                return "[극도의 탐욕] 역사적 고평가 부담 경계";
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

    private InvestorTrendInfo processTrend(MarketType market, List<MarketInvestorDaily> subHistory,
            boolean includeBreadth) {
        long individual5d = 0, foreign5d = 0, institutional5d = 0;
        long individual3d = 0, foreign3d = 0, institutional3d = 0;
        long foreign20d = 0, institutional20d = 0;

        for (int i = 0; i < 5; i++) {
            individual5d += subHistory.get(i).getPersonalNetBuy().longValue();
            foreign5d += subHistory.get(i).getForeignerNetBuy().longValue();
            institutional5d += subHistory.get(i).getInstitutionNetBuy().longValue();
            if (i < 3) {
                individual3d += subHistory.get(i).getPersonalNetBuy().longValue();
                foreign3d += subHistory.get(i).getForeignerNetBuy().longValue();
                institutional3d += subHistory.get(i).getInstitutionNetBuy().longValue();
            }
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
                : calculateIntradayMarketBreadth(market, subHistory.get(0).getDate());

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
                .individualNet3d(individual3d)
                .foreignNet3d(foreign3d)
                .institutionalNet3d(institutional3d)
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

    private BreadthResult calculateIntradayMarketBreadth(MarketType market, LocalDate date) {
        try {
            List<Stock> domesticCommonStocks = stockRepository.findDomesticCommonStocksByMarket(market);
            if (domesticCommonStocks.isEmpty()) {
                return new BreadthResult(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        "장중 - 대상 종목 없음");
            }

            long risingCount = 0;
            long fallingCount = 0;
            long totalCount = domesticCommonStocks.size();

            // Fetch intraday prices in parallel with a basic delay to respect rate limits
            // (approx 20 req/sec)
            ExecutorService executor = Executors.newFixedThreadPool(10);
            List<CompletableFuture<Double>> futures = new ArrayList<>();

            for (Stock stock : domesticCommonStocks) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        StockPriceDto priceDto = kisStockService
                                .getStockPrice(stock.getStockCode());
                        if (priceDto != null && priceDto.getChangeRate() != null) {
                            return Double.parseDouble(priceDto.getChangeRate());
                        }
                    } catch (Exception e) {
                        // ignore errors
                    }
                    return null;
                }, executor));

                try {
                    Thread.sleep(80); // Rate limit spacer
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            for (CompletableFuture<Double> f : futures) {
                try {
                    Double changeRate = f.get();
                    if (changeRate != null) {
                        if (changeRate > 0) {
                            risingCount++;
                        } else if (changeRate < 0) {
                            fallingCount++;
                        }
                    }
                } catch (Exception e) {
                    // Ignore future get errors
                }
            }
            executor.shutdown();

            double breadthIndex = totalCount > 0 ? (double) (risingCount - fallingCount) / totalCount * 100.0 : 0.0;

            // To calculate 5d, 20d, 60d avg we need to aggregate this with the historical
            // breadth data
            List<LocalDate> recentDates = stockDailyDataRepository.findDistinctDatesByMarketName(market,
                    PageRequest.of(0, 60));
            List<BreadthHistoryDto> history = new ArrayList<>();
            // Add today's estimated breadth
            history.add(new BreadthHistoryDto(date, risingCount, fallingCount, totalCount));
            // Add historical
            history.addAll(stockDailyDataRepository.findBreadthHistoryByDates(market, recentDates));

            return new BreadthResult(
                    risingCount,
                    fallingCount,
                    BigDecimal.valueOf(breadthIndex).setScale(2, RoundingMode.HALF_UP),
                    calculateAvgBreadth(history, 5),
                    calculateAvgBreadth(history, 20),
                    calculateAvgBreadth(history, 60),
                    date.toString() + " (장중 실시간)");

        } catch (Exception e) {
            log.error("Failed to calculate intraday market breadth for {}: {}", market, e.getMessage());
            return new BreadthResult(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    "장중 - 에러");
        }
    }

    /**
     * 실현 변동성 기반 안정성 점수 (VKOSPI 없는 시장용)
     * 20일 실현 변동성을 연환산하여 VKOSPI와 동일 척도로 변환
     * 낮은 변동성 = 높은 점수 (안정적), 높은 변동성 = 낮은 점수 (불안정)
     */
    private double calculateRealizedVolScore(MarketType market) {
        try {
            LocalDate end = LocalDate.now();
            LocalDate start = end.minusDays(30);
            List<IndexDailyData> indexData = indexDailyDataRepository
                    .findAllByMarketNameAndDateBetweenOrderByDateDesc(market.name(), start, end);
            if (indexData.size() < 21)
                return 5.0;

            // 최근 20일 일별 수익률의 표준편차 → 연환산
            List<Double> returns = new ArrayList<>();
            for (int i = 0; i < 20 && i + 1 < indexData.size(); i++) {
                double close = indexData.get(i).getClosingPrice().doubleValue();
                double prevClose = indexData.get(i + 1).getClosingPrice().doubleValue();
                if (prevClose > 0)
                    returns.add((close - prevClose) / prevClose);
            }
            if (returns.isEmpty())
                return 5.0;

            double mean = returns.stream().mapToDouble(r -> r).average().orElse(0);
            double variance = returns.stream().mapToDouble(r -> Math.pow(r - mean, 2)).average().orElse(0);
            double annualizedVol = Math.sqrt(variance) * Math.sqrt(252) * 100; // %로 변환

            // VKOSPI와 동일 척도: 15% 이하 = 10점, 25% 이상 = 0점
            if (annualizedVol <= 15.0)
                return 10.0;
            if (annualizedVol >= 25.0)
                return 0.0;
            return 10.0 - (annualizedVol - 15.0);
        } catch (Exception e) {
            log.warn("Failed to calculate realized vol for {}: {}", market, e.getMessage());
            return 5.0;
        }
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

        // 5. Volatility Stability Score (10 pts)
        // KOSPI: VKOSPI 사용, KOSDAQ: 20일 실현 변동성으로 대체
        double volScoreRaw = 5.0;
        if (trend.getVkospi() != null) {
            // VKOSPI 기반 (KOSPI)
            double v = trend.getVkospi().doubleValue();
            if (v <= 15.0)
                volScoreRaw = 10.0;
            else if (v >= 25.0)
                volScoreRaw = 0.0;
            else
                volScoreRaw = 10.0 - (v - 15.0);
        } else {
            // 실현 변동성 기반 (KOSDAQ 등 VKOSPI 없는 시장)
            volScoreRaw = calculateRealizedVolScore(market);
        }
        score = score.add(new BigDecimal(volScoreRaw));

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
        if (values.size() < 6)
            return TrendDirection.NEUTRAL;
        // 최근 3일 vs 이전 3일 (overlap 없이 분리)
        long currentAvg = (values.get(0) + values.get(1) + values.get(2)) / 3;
        long prevAvg = (values.get(3) + values.get(4) + values.get(5)) / 3;
        if (currentAvg > 0)
            return currentAvg > prevAvg ? TrendDirection.BUYING_ACCELERATED : TrendDirection.BUYING_SLOWED;
        if (currentAvg < 0)
            return currentAvg < prevAvg ? TrendDirection.SELLING_ACCELERATED : TrendDirection.SELLING_SLOWED;
        return TrendDirection.NEUTRAL;
    }

    private ValuationSignal determineValuationSignal(BigDecimal valuationScore) {
        double score = valuationScore.doubleValue();
        if (score >= 90) {
            return ValuationSignal.EXTREME_GREED;
        } else if (score >= 75) {
            return ValuationSignal.STRONG_OVERHEATED;
        } else if (score >= 60) {
            return ValuationSignal.OVERHEATED;
        } else if (score >= 40) {
            return ValuationSignal.FAIR_VALUE;
        } else if (score >= 20) {
            return ValuationSignal.UNDERVALUED;
        }
        return ValuationSignal.EXTREME_FEAR;
    }

    private CombinedSignal determineCombinedSignal(ValuationSignal valuation, TrendSignal trend) {
        if (valuation == null || trend == null)
            return CombinedSignal.HOLD;

        if (valuation == ValuationSignal.UNDERVALUED || valuation == ValuationSignal.EXTREME_FEAR) {
            if (trend == TrendSignal.HEALTHY_BULL || trend == TrendSignal.OVERSOLD_REBOUND)
                return CombinedSignal.STRONG_BUY;
            if (trend == TrendSignal.PANIC_SELLING)
                return CombinedSignal.HOLD;
            return CombinedSignal.ACCUMULATE;
        }

        if (valuation == ValuationSignal.OVERHEATED || valuation == ValuationSignal.STRONG_OVERHEATED
                || valuation == ValuationSignal.EXTREME_GREED) {
            if (trend == TrendSignal.PANIC_SELLING || trend == TrendSignal.BULL_TRAP)
                return CombinedSignal.AGGRESSIVE_SELL;
            if (trend == TrendSignal.HEALTHY_BULL)
                return CombinedSignal.HOLD;
            if (valuation == ValuationSignal.EXTREME_GREED)
                return CombinedSignal.CAUTION;
            return CombinedSignal.HOLD;
        }

        if (trend == TrendSignal.HEALTHY_BULL)
            return CombinedSignal.ACCUMULATE;
        if (trend == TrendSignal.PANIC_SELLING || trend == TrendSignal.BULL_TRAP)
            return CombinedSignal.CAUTION;

        return CombinedSignal.HOLD;
    }

    private static class SignalAdjustment {
        final CombinedSignal originalSignal;
        final CombinedSignal adjustedSignal;
        final List<String> rebuttalReasons; // KNN이 매트릭스를 명확히 반박해 강등한 사유 (강등 발생 시에만)
        final boolean oodFlag;
        final boolean uncertaintyFlag;
        final List<String> uncertaintyReasons; // OOD/통계 유의성 부족 등 미래 예측 불확실성 caveat

        SignalAdjustment(CombinedSignal original, CombinedSignal adjusted,
                List<String> rebuttalReasons, boolean oodFlag, boolean uncertaintyFlag,
                List<String> uncertaintyReasons) {
            this.originalSignal = original;
            this.adjustedSignal = adjusted;
            this.rebuttalReasons = rebuttalReasons;
            this.oodFlag = oodFlag;
            this.uncertaintyFlag = uncertaintyFlag;
            this.uncertaintyReasons = uncertaintyReasons;
        }
    }

    /**
     * KNN 통계 결과 → 신호 보정 분리 처리.
     *
     * - 현재 진단(추세/valuation)이 명확하면 매트릭스 결과 유지.
     * - KNN outcomeDistribution이 매트릭스 결과를 *명확히 반박*할 때만 신호 강등.
     *   (예: 매도 신호인데 bullCase가 |bearCase|의 2배 이상)
     * - VERY_LOW(OOD), winRateCI 50% 포함 등은 강등이 아닌 flag로만 분리 표시
     *   → 사용자는 "현재 진단은 매도지만 미래는 모름" 둘 다 확인 가능.
     */
    private SignalAdjustment applyKnnAdjustment(CombinedSignal base, MarketValuationDto.PredictionReport pr) {
        if (pr == null) {
            return new SignalAdjustment(base, base, Collections.emptyList(), false, false,
                    Collections.emptyList());
        }

        // 1. 미래 예측 불확실성 flag (강등과 무관)
        boolean oodFlag = false;
        boolean uncertaintyFlag = false;
        List<String> uncertaintyReasons = new ArrayList<>();

        if (pr.getKnnStats() != null
                && pr.getKnnStats().getConfidence() == MarketValuationDto.KnnConfidence.VERY_LOW) {
            oodFlag = true;
            uncertaintyReasons.add("KNN 신뢰도 VERY_LOW (OOD) — 유사 과거 사례 부족, 미래 예측 신뢰도 낮음");
        }

        List<BigDecimal> ci = (pr.getMediumTerm() != null) ? pr.getMediumTerm().getWinRateCI() : null;
        if (ci != null && ci.size() == 2 && ci.get(0) != null && ci.get(1) != null) {
            BigDecimal lower = ci.get(0);
            BigDecimal upper = ci.get(1);
            BigDecimal fifty = new BigDecimal("50");
            if (lower.compareTo(fifty) <= 0 && upper.compareTo(fifty) >= 0) {
                uncertaintyFlag = true;
                uncertaintyReasons.add("Bootstrap winRate 95% CI [" + lower + "%, " + upper
                        + "%]가 50% 포함 — 30일 후 방향 통계적 유의성 부족");
            }
        }

        // 2. 진짜 반박 트리거: outcomeDistribution이 매트릭스 결과와 명확히 반대 방향
        int rebuttalSteps = 0;
        List<String> rebuttalReasons = new ArrayList<>();
        MarketValuationDto.OutcomeDistribution od = pr.getOutcomeDistribution();
        if (od != null && od.getBearCase() != null && od.getBullCase() != null
                && od.getBaseCase() != null) {
            BigDecimal bear = od.getBearCase().getReturnValue();
            BigDecimal base_ = od.getBaseCase().getReturnValue();
            BigDecimal bull = od.getBullCase().getReturnValue();

            boolean isSellSignal = base == CombinedSignal.AGGRESSIVE_SELL
                    || base == CombinedSignal.CAUTION;
            boolean isBuySignal = base == CombinedSignal.STRONG_BUY
                    || base == CombinedSignal.ACCUMULATE;

            if (isSellSignal && bull != null && bear != null
                    && bull.compareTo(BigDecimal.ZERO) > 0
                    && base_ != null && base_.compareTo(BigDecimal.ZERO) >= 0
                    && bull.abs().compareTo(bear.abs().multiply(new BigDecimal("2"))) > 0) {
                rebuttalSteps++;
                rebuttalReasons.add("매도 신호이나 KNN bullCase(" + bull + "%) > 2·|bearCase|("
                        + bear + "%) 이고 baseCase(" + base_ + "%)도 비음수 — 상승 모멘텀이 우세");
            }
            if (isBuySignal && bear != null && bull != null
                    && bear.compareTo(BigDecimal.ZERO) < 0
                    && base_ != null && base_.compareTo(BigDecimal.ZERO) <= 0
                    && bear.abs().compareTo(bull.abs().multiply(new BigDecimal("2"))) > 0) {
                rebuttalSteps++;
                rebuttalReasons.add("매수 신호이나 KNN |bearCase|(" + bear + "%) > 2·bullCase("
                        + bull + "%) 이고 baseCase(" + base_ + "%)도 비양수 — 하락 리스크가 우세");
            }
        }

        CombinedSignal adjusted = base;
        for (int i = 0; i < Math.min(rebuttalSteps, 2); i++) {
            adjusted = degradeCombinedSignal(adjusted);
        }

        return new SignalAdjustment(base, adjusted, rebuttalReasons, oodFlag, uncertaintyFlag,
                uncertaintyReasons);
    }

    private CombinedSignal degradeCombinedSignal(CombinedSignal s) {
        switch (s) {
            case AGGRESSIVE_SELL:
                return CombinedSignal.CAUTION;
            case CAUTION:
                return CombinedSignal.HOLD;
            case STRONG_BUY:
                return CombinedSignal.ACCUMULATE;
            case ACCUMULATE:
                return CombinedSignal.HOLD;
            case HOLD:
            default:
                return CombinedSignal.HOLD;
        }
    }

    private TrendSignal determineTrendSignal(BigDecimal trendScore, InvestorTrendInfo trend) {
        double score = trendScore.doubleValue();
        double breadth = trend.getCommonMarketBreadthIndex().doubleValue();
        double avg5d = trend.getBreadth5dAvg().doubleValue();
        double avg20d = trend.getBreadth20dAvg().doubleValue();
        double avg60d = trend.getBreadth60dAvg().doubleValue();

        // 상대 기준: breadth의 Z-score (20d 평균 대비 편차를 5d-20d 갭으로 정규화)
        double breadthSpread = Math.max(Math.abs(avg5d - avg60d), 3.0); // 최소 3으로 0-div 방지
        double breadthZ = (breadth - avg20d) / breadthSpread;

        // 외국인 상대 강도: relativeStrength가 이미 (5d*4/20d) → 1.0이 중립
        double foreignRS = trend.getForeignRelativeStrength();

        // 1. HEALTHY_BULL: 추세 점수 통과 + 수급/breadth 중 2개 이상 긍정
        boolean strongScore = score >= 55;
        boolean foreignAccum = foreignRS > 1.05;
        boolean breadthPositive = breadthZ > 0.2;
        int healthyCount = (strongScore ? 1 : 0) + (foreignAccum ? 1 : 0) + (breadthPositive ? 1 : 0);
        if (score >= 60 && healthyCount >= 2) {
            return TrendSignal.HEALTHY_BULL;
        }

        // 2. PANIC_SELLING: 추세 약세 + breadth 급락 + 외국인 가속 매도
        if (score < 35 && breadthZ < -1.2 && foreignRS < 0.6) {
            return TrendSignal.PANIC_SELLING;
        }

        // 3. OVERSOLD_REBOUND: breadth가 60d 대비 극단적 저점 + 반등 조짐
        if (score < 40 && breadth < avg60d && breadthZ < -0.8 && breadth > avg5d) {
            return TrendSignal.OVERSOLD_REBOUND;
        }

        // 4. BULL_TRAP: 점수 우위이나 breadth 하락 다이버전스
        if (score > 50 && breadthZ < -0.5 && breadth < avg5d) {
            return TrendSignal.BULL_TRAP;
        }

        // 5. STAGNANT: breadth 변동폭이 매우 낮음
        if (Math.abs(breadthZ) < 0.3 && Math.abs(avg5d - avg20d) < 3.0) {
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
            BigDecimal currentCpiYoy,
            BigDecimal valuationScore, BigDecimal trendScore, InvestorTrendInfo trendInfo,
            List<MarketValuationDto.TimeSeriesPoint> timeSeries, MarketType market) {

        try {
            // 1. Backtesting (Historical Similarity - Weighted KNN)
            int matches = 0;
            double weightedWinRate = 50.0;
            double weightedAvgReturn = 0.0;

            // KNN 신뢰도/OOD 감지용 — 매칭 실패 시 기본값은 VERY_LOW + OOD 경고
            Double avgDistance = null;
            Double minDistance = null;
            MarketValuationDto.KnnConfidence confidence = MarketValuationDto.KnnConfidence.VERY_LOW;
            boolean oodWarning = true;

            // Block Bootstrap 결과 — 매칭 실패 시 null
            MarketValuationDto.BootstrapDistribution bootstrapDistribution = null;
            List<BigDecimal> winRateCI = null;
            MarketValuationDto.ReturnStats returnStats = null;
            MarketValuationDto.OutcomeDistribution outcomeDistribution = null;
            List<MarketValuationDto.HistoricalMatchCase> topMatches = null;

            // KNN parameters
            final int K = 30;

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
                    // 90일 cooldown — 30일 포워드 리턴 계산 가능성 + 현재 regime과의 자기상관 누수 차단.
                    // 현재가 OOD 국면이면 최근 1~3개월이 KNN top을 점유해 "유사 과거"가 아니라 본인 연속이 되는 문제 회피.
                    LocalDate latestPossibleDate = LocalDate.now().minusDays(90);

                    // CPI YoY 가용성 사전 판정 — 데이터 부족 시 해당 차원 제외 fallback
                    long pointsWithCpiYoy = timeSeries.stream()
                            .filter(p -> p != null && p.getCpiYoy() != null).count();
                    boolean useCpiYoy = currentCpiYoy != null && pointsWithCpiYoy >= 30;
                    if (!useCpiYoy) {
                        log.warn("CPI YoY 데이터 부족 (timeSeries={}개, current={}), 해당 차원 제외",
                                pointsWithCpiYoy, currentCpiYoy);
                    }

                    List<Double> capes = new ArrayList<>();
                    List<Double> yieldGaps = new ArrayList<>();
                    List<Double> vkospis = new ArrayList<>();
                    List<Double> foreigns = new ArrayList<>();
                    List<Double> breadths = new ArrayList<>();
                    List<Double> cpiYoys = new ArrayList<>();

                    for (MarketValuationDto.TimeSeriesPoint p : timeSeries) {
                        if (p == null || p.getCape() == null || p.getYieldGap() == null
                                || p.getVkospi() == null || p.getForeignNet5d() == null || p.getBreadth5d() == null
                                || (useCpiYoy && p.getCpiYoy() == null)
                                || p.getDate() == null)
                            continue;

                        try {
                            LocalDate pDate = LocalDate.parse(p.getDate(), DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                            if (pDate.isAfter(latestPossibleDate))
                                continue; // Skip recent data

                            validPoints.add(p);
                            capes.add(p.getCape().doubleValue());
                            yieldGaps.add(p.getYieldGap().doubleValue());
                            vkospis.add(p.getVkospi().doubleValue());
                            foreigns.add(p.getForeignNet5d().doubleValue());
                            breadths.add(p.getBreadth5d().doubleValue());
                            if (useCpiYoy)
                                cpiYoys.add(p.getCpiYoy().doubleValue());
                        } catch (Exception ignored) {
                        }
                    }

                    // 현재 상태 변수 — KOSDAQ도 KOSPI VKOSPI를 차용 (trendInfo.vkospi가 null이면 별도 조회)
                    BigDecimal currentVkospi = getEffectiveVkospi(trendInfo);
                    Long currentForeignNet5d = (trendInfo != null) ? trendInfo.getForeignNet5d() : null;
                    BigDecimal currentBreadth5d = (trendInfo != null) ? trendInfo.getBreadth5dAvg() : null;

                    boolean canRunKnn = !validPoints.isEmpty()
                            && currentVkospi != null
                            && currentForeignNet5d != null
                            && currentBreadth5d != null;
                    // currentCpiYoy는 useCpiYoy=true일 때만 필수 (위 사전 판정에서 확인됨)

                    if (canRunKnn) {
                        // Calculate Mean & StdDev (5 features)
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

                        double meanVkospi = vkospis.stream().mapToDouble(d -> d).average().orElse(0.0);
                        double stdVkospi = Math.sqrt(
                                vkospis.stream().mapToDouble(d -> Math.pow(d - meanVkospi, 2)).average().orElse(1.0));
                        if (stdVkospi == 0)
                            stdVkospi = 1.0;

                        double meanForeign = foreigns.stream().mapToDouble(d -> d).average().orElse(0.0);
                        double stdForeign = Math.sqrt(
                                foreigns.stream().mapToDouble(d -> Math.pow(d - meanForeign, 2)).average().orElse(1.0));
                        if (stdForeign == 0)
                            stdForeign = 1.0;

                        double meanBreadth = breadths.stream().mapToDouble(d -> d).average().orElse(0.0);
                        double stdBreadth = Math.sqrt(
                                breadths.stream().mapToDouble(d -> Math.pow(d - meanBreadth, 2)).average().orElse(1.0));
                        if (stdBreadth == 0)
                            stdBreadth = 1.0;

                        // CPI YoY 차원 (useCpiYoy=true일 때만 계산)
                        // lambda 캡처 위해 중간 final 변수 사용
                        double meanCpiYoyTmp = 0.0, stdCpiYoyTmp = 1.0, zCpiYoyCurrTmp = 0.0;
                        if (useCpiYoy) {
                            final double meanCpiYoyFinal = cpiYoys.stream().mapToDouble(d -> d).average().orElse(0.0);
                            meanCpiYoyTmp = meanCpiYoyFinal;
                            stdCpiYoyTmp = Math.sqrt(cpiYoys.stream()
                                    .mapToDouble(d -> Math.pow(d - meanCpiYoyFinal, 2)).average().orElse(1.0));
                            if (stdCpiYoyTmp == 0)
                                stdCpiYoyTmp = 1.0;
                            zCpiYoyCurrTmp = (currentCpiYoy.doubleValue() - meanCpiYoyTmp) / stdCpiYoyTmp;
                        }
                        final double meanCpiYoy = meanCpiYoyTmp;
                        final double stdCpiYoy = stdCpiYoyTmp;
                        final double zCpiYoyCurr = zCpiYoyCurrTmp;

                        // Normalize Current State (5 or 6 features)
                        double zCapeCurr = (currentCape.doubleValue() - meanCape) / stdCape;
                        double zYgCurr = (currentYieldGap.doubleValue() - meanYg) / stdYg;
                        double zVkospiCurr = (currentVkospi.doubleValue() - meanVkospi) / stdVkospi;
                        double zForeignCurr = (currentForeignNet5d.doubleValue() - meanForeign) / stdForeign;
                        double zBreadthCurr = (currentBreadth5d.doubleValue() - meanBreadth) / stdBreadth;

                        // 1-2. Calculate Distances (KNN, conditionally 5 or 6-dimensional)
                        // CPI YoY 사용 가능: 6D / 불가능 시: 5D fallback (KNN 자체는 유지)
                        // CPI 차원 final 변수 (lambda 내부에서 안전하게 사용하기 위해)
                        final double fMeanCpiYoy = meanCpiYoy;
                        final double fStdCpiYoy = stdCpiYoy;
                        final double fZCpiYoyCurr = zCpiYoyCurr;
                        List<KnnCandidate> candidates = new ArrayList<>();

                        for (MarketValuationDto.TimeSeriesPoint p : validPoints) {
                            double zCape = (p.getCape().doubleValue() - meanCape) / stdCape;
                            double zYg = (p.getYieldGap().doubleValue() - meanYg) / stdYg;
                            double zVkospi = (p.getVkospi().doubleValue() - meanVkospi) / stdVkospi;
                            double zForeign = (p.getForeignNet5d().doubleValue() - meanForeign) / stdForeign;
                            double zBreadth = (p.getBreadth5d().doubleValue() - meanBreadth) / stdBreadth;

                            double cpiYoyTerm = 0.0;
                            if (useCpiYoy && p.getCpiYoy() != null) {
                                double zCpiYoy = (p.getCpiYoy().doubleValue() - fMeanCpiYoy) / fStdCpiYoy;
                                cpiYoyTerm = Math.pow(zCpiYoy - fZCpiYoyCurr, 2);
                            }

                            double distance = Math.sqrt(
                                    Math.pow(zCape - zCapeCurr, 2)
                                            + Math.pow(zYg - zYgCurr, 2)
                                            + Math.pow(zVkospi - zVkospiCurr, 2)
                                            + Math.pow(zForeign - zForeignCurr, 2)
                                            + Math.pow(zBreadth - zBreadthCurr, 2)
                                            + cpiYoyTerm);

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
                        // 거리 오름차순 정렬 후 분기당 최대 1개만 채택 → 동일 regime의 연속 데이터가 top을 점유하는 것 방지.
                        // K=30개에 도달할 때까지 진행 (분기가 부족하면 K 미달).
                        candidates.sort(Comparator.comparingDouble(c -> c.distance));
                        Set<String> usedQuarters = new HashSet<>();
                        List<KnnCandidate> neighbors = new ArrayList<>();
                        for (KnnCandidate c : candidates) {
                            try {
                                LocalDate cDate = LocalDate.parse(c.date,
                                        DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                                String quarterKey = cDate.getYear() + "-Q"
                                        + ((cDate.getMonthValue() - 1) / 3 + 1);
                                if (!usedQuarters.add(quarterKey))
                                    continue;
                                neighbors.add(c);
                                if (neighbors.size() >= K)
                                    break;
                            } catch (Exception ignored) {
                            }
                        }
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

                            // topMatches: 거리 가장 가까운 상위 5개 사례 — Gemini narrative에 사용
                            Map<String, MarketValuationDto.TimeSeriesPoint> validPointByDate = validPoints.stream()
                                    .collect(Collectors.toMap(MarketValuationDto.TimeSeriesPoint::getDate,
                                            p -> p, (a, b) -> a));
                            topMatches = neighbors.stream()
                                    .limit(5)
                                    .map(c -> {
                                        MarketValuationDto.TimeSeriesPoint p = validPointByDate.get(c.date);
                                        return MarketValuationDto.HistoricalMatchCase.builder()
                                                .date(c.date)
                                                .cape(p != null ? p.getCape() : null)
                                                .vkospi(p != null ? p.getVkospi() : null)
                                                .yieldGap(p != null ? p.getYieldGap() : null)
                                                .foreignNet5d(p != null ? p.getForeignNet5d() : null)
                                                .breadth5d(p != null ? p.getBreadth5d() : null)
                                                .forwardReturn(BigDecimal.valueOf(c.forwardReturn * 100.0)
                                                        .setScale(2, RoundingMode.HALF_UP))
                                                .distance(BigDecimal.valueOf(c.distance)
                                                        .setScale(3, RoundingMode.HALF_UP))
                                                .build();
                                    })
                                    .collect(Collectors.toList());

                            // 거리 임계값 기반 신뢰도 판정
                            avgDistance = neighbors.stream().mapToDouble(c -> c.distance).average().orElse(0.0);
                            minDistance = neighbors.stream().mapToDouble(c -> c.distance).min().orElse(0.0);

                            if (avgDistance < 1.0) {
                                confidence = MarketValuationDto.KnnConfidence.HIGH;
                                oodWarning = false;
                            } else if (avgDistance < 1.5) {
                                confidence = MarketValuationDto.KnnConfidence.MEDIUM;
                                oodWarning = false;
                            } else if (avgDistance < 2.5) {
                                confidence = MarketValuationDto.KnnConfidence.LOW;
                                oodWarning = false;
                            } else {
                                confidence = MarketValuationDto.KnnConfidence.VERY_LOW;
                                oodWarning = true;
                            }

                            // Raw forwardReturn 분포 통계 (검증용)
                            double[] rawReturns = neighbors.stream()
                                    .mapToDouble(c -> c.forwardReturn).toArray();
                            double rawMin = Arrays.stream(rawReturns).min().orElse(0.0);
                            double rawMax = Arrays.stream(rawReturns).max().orElse(0.0);
                            double rawMean = Arrays.stream(rawReturns).average().orElse(0.0);
                            double rawVarSum = 0.0;
                            for (double r : rawReturns) {
                                rawVarSum += Math.pow(r - rawMean, 2);
                            }
                            double rawStd = Math.sqrt(rawVarSum / rawReturns.length);

                            returnStats = MarketValuationDto.ReturnStats.builder()
                                    .min(BigDecimal.valueOf(rawMin * 100).setScale(2, RoundingMode.HALF_UP))
                                    .max(BigDecimal.valueOf(rawMax * 100).setScale(2, RoundingMode.HALF_UP))
                                    .mean(BigDecimal.valueOf(rawMean * 100).setScale(2, RoundingMode.HALF_UP))
                                    .std(BigDecimal.valueOf(rawStd * 100).setScale(2, RoundingMode.HALF_UP))
                                    .build();

                            // outcomeDistribution: 30개 raw return의 p10/p50/p90을 직접 추출
                            // "실제 발생 가능한 수익률 범위"를 표현 (mean 분포가 아닌 개별 분포)
                            double[] sortedReturns = rawReturns.clone();
                            Arrays.sort(sortedReturns);
                            int n = sortedReturns.length;
                            double bearReturn = percentileOf(sortedReturns, 10) * 100.0;
                            double baseReturn = percentileOf(sortedReturns, 50) * 100.0;
                            double bullReturn = percentileOf(sortedReturns, 90) * 100.0;

                            outcomeDistribution = MarketValuationDto.OutcomeDistribution.builder()
                                    .bearCase(MarketValuationDto.ScenarioCase.builder()
                                            .returnValue(BigDecimal.valueOf(bearReturn).setScale(2, RoundingMode.HALF_UP))
                                            .label("비관")
                                            .build())
                                    .baseCase(MarketValuationDto.ScenarioCase.builder()
                                            .returnValue(BigDecimal.valueOf(baseReturn).setScale(2, RoundingMode.HALF_UP))
                                            .label("중간")
                                            .build())
                                    .bullCase(MarketValuationDto.ScenarioCase.builder()
                                            .returnValue(BigDecimal.valueOf(bullReturn).setScale(2, RoundingMode.HALF_UP))
                                            .label("낙관")
                                            .build())
                                    .build();
                            log.info("outcomeDistribution (n={}): bear={}%, base={}%, bull={}%",
                                    n, String.format("%.2f", bearReturn),
                                    String.format("%.2f", baseReturn),
                                    String.format("%.2f", bullReturn));

                            // Block Bootstrap (블록 길이 3, 1000회 재샘플링)
                            // 자기상관 보존을 위해 날짜순 정렬 후 블록 단위로 추출
                            if (neighbors.size() >= 3) {
                                List<KnnCandidate> sortedByDate = neighbors.stream()
                                        .sorted(Comparator.comparing(c -> c.date))
                                        .collect(Collectors.toList());

                                final int N = sortedByDate.size();
                                final int blockSize = 3;
                                final int iterations = 1000;
                                Random random = new Random();

                                double[] bootstrapMeans = new double[iterations];
                                double[] bootstrapWinRates = new double[iterations];

                                for (int it = 0; it < iterations; it++) {
                                    double sum = 0.0;
                                    int positive = 0;
                                    int count = 0;
                                    while (count < N) {
                                        int startIdx = random.nextInt(N - blockSize + 1);
                                        for (int j = 0; j < blockSize && count < N; j++) {
                                            double r = sortedByDate.get(startIdx + j).forwardReturn;
                                            sum += r;
                                            if (r > 0)
                                                positive++;
                                            count++;
                                        }
                                    }
                                    bootstrapMeans[it] = sum / N;
                                    bootstrapWinRates[it] = (double) positive / N;
                                }

                                Arrays.sort(bootstrapMeans);
                                Arrays.sort(bootstrapWinRates);

                                // p10/p50/p90 mean return (% 단위)
                                double p10 = bootstrapMeans[100] * 100.0;
                                double p50 = bootstrapMeans[500] * 100.0;
                                double p90 = bootstrapMeans[900] * 100.0;

                                // 95% CI on winRate (% 단위) — [2.5%, 97.5%]
                                double ciLower = bootstrapWinRates[25] * 100.0;
                                double ciUpper = bootstrapWinRates[975] * 100.0;

                                bootstrapDistribution = MarketValuationDto.BootstrapDistribution.builder()
                                        .p10(BigDecimal.valueOf(p10).setScale(2, RoundingMode.HALF_UP))
                                        .p50(BigDecimal.valueOf(p50).setScale(2, RoundingMode.HALF_UP))
                                        .p90(BigDecimal.valueOf(p90).setScale(2, RoundingMode.HALF_UP))
                                        .build();

                                winRateCI = List.of(
                                        BigDecimal.valueOf(ciLower).setScale(2, RoundingMode.HALF_UP),
                                        BigDecimal.valueOf(ciUpper).setScale(2, RoundingMode.HALF_UP));
                            }
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
            // normForeign: 시장별 기준값 대비 sigmoid 정규화 (0~1 연속값)
            double normForeign = 0.5;
            if (trendInfo != null) {
                Long fNet = trendInfo.getForeignNet5d();
                Long fFutNet = trendInfo.getFuturesForeignNet5d();
                long net = (fNet != null ? fNet : 0L) + (fFutNet != null ? fFutNet : 0L);
                // 시장별 기준값으로 정규화 후 sigmoid
                long foreignTarget = (market == MarketType.KOSPI) ? 500000L : 100000L;
                double ratio = (double) net / foreignTarget; // -1 ~ +1 범위 (대부분)
                normForeign = 1.0 / (1.0 + Math.exp(-2.5 * ratio)); // sigmoid: 0~1 연속값
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
            // Short-term (1w): Energy 주도 + KNN 보조 (유사 국면이 있으면 반영)
            double pShort = (matches > 5)
                    ? (energyScore * 0.7 + (weightedWinRate / 100.0) * 0.3) * 100.0
                    : energyScore * 100.0;

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

            MarketValuationDto.KnnStats knnStats = MarketValuationDto.KnnStats.builder()
                    .avgDistance(avgDistance != null
                            ? BigDecimal.valueOf(avgDistance).setScale(3, RoundingMode.HALF_UP)
                            : null)
                    .minDistance(minDistance != null
                            ? BigDecimal.valueOf(minDistance).setScale(3, RoundingMode.HALF_UP)
                            : null)
                    .confidence(confidence)
                    .oodWarning(oodWarning)
                    .returnStats(returnStats)
                    .build();

            return PredictionReport.builder()
                    .shortTerm(ProbabilityInfo.builder()
                            .upProbability(pShort).downProbability(100 - pShort)
                            .primaryReason(pShort > 50 ? "모멘텀 및 수급 양호" : "수급 악화 및 하락 에너지 우세")
                            .distribution(bootstrapDistribution)
                            .winRateCI(winRateCI)
                            .build())
                    .mediumTerm(ProbabilityInfo.builder()
                            .upProbability(pMedium).downProbability(100 - pMedium)
                            .primaryReason(matches > 5 ? "역사적 유사 국면 (" + matches + "회) 반영" : "밸류에이션 및 추세 혼합")
                            .distribution(bootstrapDistribution)
                            .winRateCI(winRateCI)
                            .build())
                    .longTerm(ProbabilityInfo.builder()
                            .upProbability(pLong).downProbability(100 - pLong)
                            .primaryReason("Yield Gap 및 펀더멘털 매력도 기반").build())
                    .historicalMatch(historicalMatch)
                    .knnStats(knnStats)
                    .outcomeDistribution(outcomeDistribution)
                    .topMatches(topMatches)
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
                    .knnStats(MarketValuationDto.KnnStats.builder()
                            .confidence(MarketValuationDto.KnnConfidence.VERY_LOW)
                            .oodWarning(true)
                            .build())
                    .build();
        }
    }
}
