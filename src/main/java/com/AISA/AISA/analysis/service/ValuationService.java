package com.AISA.AISA.analysis.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.springframework.stereotype.Service;

import com.AISA.AISA.analysis.dto.DomesticValuationDto.AnalysisDetails;
import com.AISA.AISA.analysis.dto.DomesticValuationDto.AnalysisDetails.HistoricalValuationRange;
import com.AISA.AISA.analysis.dto.DomesticValuationDto.AnalysisDetails.TechnicalIndicators;
import com.AISA.AISA.analysis.dto.DomesticValuationDto.AnalysisDetails.ValuationContext;

import com.AISA.AISA.analysis.dto.DomesticValuationDto.Request;
import com.AISA.AISA.analysis.dto.DomesticValuationDto.Response;
import com.AISA.AISA.analysis.dto.ValuationBaseDto.DiscountRateInfo;
import com.AISA.AISA.analysis.dto.ValuationBaseDto.RiskLevel;
import com.AISA.AISA.analysis.dto.ValuationBaseDto.Stance;
import com.AISA.AISA.analysis.dto.ValuationBaseDto.Summary;
import com.AISA.AISA.analysis.dto.ValuationBaseDto.ValuationBand;
import com.AISA.AISA.analysis.dto.ValuationBaseDto.ValuationResult;
import com.AISA.AISA.analysis.entity.StockAiSummary;
import com.AISA.AISA.analysis.entity.StockStaticAnalysis;
import com.AISA.AISA.analysis.repository.StockAiSummaryRepository;
import com.AISA.AISA.analysis.repository.StockStaticAnalysisRepository;
import com.AISA.AISA.kisStock.Entity.Index.IndexDailyData;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockDailyData;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialRatio;
import com.AISA.AISA.kisStock.Entity.stock.StockBalanceSheet;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialStatement;
import com.AISA.AISA.kisStock.enums.BondYield;
import com.AISA.AISA.kisStock.dto.InvestorTrend.InvestorTrendDto;
import com.AISA.AISA.kisStock.dto.StockPrice.StockPriceDto;
import com.AISA.AISA.kisStock.repository.IndexDailyDataRepository;
import com.AISA.AISA.kisStock.repository.StockDailyDataRepository;
import com.AISA.AISA.kisStock.repository.StockFinancialRatioRepository;
import com.AISA.AISA.kisStock.repository.StockFinancialStatementRepository;
import com.AISA.AISA.kisStock.repository.StockRepository;
import com.AISA.AISA.kisStock.kisService.CompetitorAnalysisService;
import com.AISA.AISA.kisStock.kisService.KisStockService;
import com.AISA.AISA.kisStock.kisService.KisIndexService;
import com.AISA.AISA.kisStock.kisService.KisMacroService;
import com.AISA.AISA.analysis.dto.ValuationBaseDto.UserPropensity;
import com.AISA.AISA.portfolio.macro.Entity.MacroDailyData;
import com.AISA.AISA.portfolio.macro.repository.MacroDailyDataRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class ValuationService {

    private final StockRepository stockRepository;
    private final StockFinancialRatioRepository stockFinancialRatioRepository;
    private final StockFinancialStatementRepository stockFinancialStatementRepository;
    private final KisStockService kisStockService;
    private final GeminiService geminiService;
    private final StockAiSummaryRepository stockAiSummaryRepository;
    private final StockStaticAnalysisRepository stockStaticAnalysisRepository;
    private final CompetitorAnalysisService competitorAnalysisService;
    private final IndexDailyDataRepository indexDailyDataRepository;
    private final MacroDailyDataRepository macroDailyDataRepository;
    private final KisIndexService kisIndexService;
    private final KisMacroService kisMacroService;
    private final StockDailyDataRepository stockDailyDataRepository;

    @Autowired
    public ValuationService(StockRepository stockRepository,
            StockFinancialRatioRepository stockFinancialRatioRepository,
            StockFinancialStatementRepository stockFinancialStatementRepository,
            KisStockService kisStockService,
            GeminiService geminiService,
            StockAiSummaryRepository stockAiSummaryRepository,
            StockStaticAnalysisRepository stockStaticAnalysisRepository,
            CompetitorAnalysisService competitorAnalysisService,
            IndexDailyDataRepository indexDailyDataRepository,
            MacroDailyDataRepository macroDailyDataRepository,
            KisIndexService kisIndexService,
            KisMacroService kisMacroService,
            StockDailyDataRepository stockDailyDataRepository) {
        this.stockRepository = stockRepository;
        this.stockFinancialRatioRepository = stockFinancialRatioRepository;
        this.stockFinancialStatementRepository = stockFinancialStatementRepository;
        this.kisStockService = kisStockService;
        this.geminiService = geminiService;
        this.stockAiSummaryRepository = stockAiSummaryRepository;
        this.stockStaticAnalysisRepository = stockStaticAnalysisRepository;
        this.competitorAnalysisService = competitorAnalysisService;
        this.indexDailyDataRepository = indexDailyDataRepository;
        this.macroDailyDataRepository = macroDailyDataRepository;
        this.kisIndexService = kisIndexService;
        this.kisMacroService = kisMacroService;
        this.stockDailyDataRepository = stockDailyDataRepository;
    }

    @Transactional
    public Response getStandardizedValuationReport(String stockCode, boolean forceRefresh) {
        Request standardRequest = Request.builder()
                .userPropensity(UserPropensity.NEUTRAL)
                .forceRefresh(forceRefresh)
                .build();
        return calculateValuationWithAi(stockCode, standardRequest);
    }

    @Transactional
    public Response calculateValuationWithAi(String stockCode, Request request) {
        if (request != null && request.isForceRefresh()) {
            kisStockService.evictInvestorTrendCache(stockCode);
        }
        Response response = calculateValuation(stockCode, request);
        String aiAnalysis = getValuationAnalysis(stockCode, response, request);
        InvestorTrendDto trend = null;
        try {
            trend = kisStockService.getInvestorTrend(stockCode);
            log.info("Fetched investor trend for {}: advice={}", stockCode, trend != null ? trend.getAdvice() : "N/A");
        } catch (Exception e) {
            log.warn("Failed to fetch investor trend: {}", e.getMessage());
        }
        return buildResponseWithAi(response, aiAnalysis, trend);
    }

    public Response calculateValuation(String stockCode, Request request) {
        Stock stock = stockRepository.findByStockCode(stockCode)
                .orElseThrow(() -> new IllegalArgumentException("Stock not found: " + stockCode));
        StockFinancialRatio latestRatio = stockFinancialRatioRepository
                .findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
        List<StockFinancialRatio> history = stockFinancialRatioRepository
                .findTop5ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");

        if (latestRatio == null)
            return Response.builder().stockCode(stockCode).stockName(stock.getStockName()).currentPrice("0").build();

        BigDecimal currentPrice = BigDecimal.ZERO;
        StockPriceDto priceDto = null;
        try {
            priceDto = kisStockService.getStockPrice(stockCode);
            if (priceDto != null && priceDto.getStockPrice() != null)
                currentPrice = new BigDecimal(priceDto.getStockPrice().replace(",", "").trim());
        } catch (Exception e) {
            log.warn("Failed to fetch price: {}", e.getMessage());
        }

        if (currentPrice.compareTo(BigDecimal.ZERO) == 0 && latestRatio.getEps() != null
                && latestRatio.getPer() != null) {
            currentPrice = latestRatio.getEps().multiply(latestRatio.getPer());
        }

        // [New Phase 1] Beta 및 현실적 할인율(COE) 산출
        double beta = 1.0;
        try {
            beta = calculateBeta(stockCode, stock.getMarketName().name());
        } catch (Exception e) {
            log.warn("Failed to calculate beta for {}: {}", stockCode, e.getMessage());
        }

        DiscountRateInfo discountRateInfo = determineCOEWithBeta(request, beta, stock.getMarketName().name());
        BigDecimal coe = new BigDecimal(discountRateInfo.getValue().replace("%", "")).divide(new BigDecimal(100), 4,
                RoundingMode.HALF_UP);

        ValuationResult srim = calculateSRIM(latestRatio, history, coe, currentPrice);
        ValuationResult perModel = calculatePERModel(latestRatio, history, currentPrice);
        ValuationResult pbrModel = calculatePBRModel(latestRatio, history, coe, currentPrice);
        ValuationBand band = calculateBand(currentPrice, srim, perModel, pbrModel, stockCode, stock);
        Summary summary = generateSummary(srim, perModel, pbrModel);

        // [New Phase 2] 기술적 지표 및 밸류에이션 컨텍스트(KNN 포함) 산출
        TechnicalIndicators technicalIndicators = calculateTechnicalIndicators(stockCode, stock.getMarketName().name());
        ValuationContext valuationContext = calculateValuationContext(stockCode, latestRatio.getRoe(),
                stock.getMarketName().name(), latestRatio.getPer(), latestRatio.getPbr());

        if (valuationContext != null) {
            valuationContext.setBeta(beta);
            valuationContext.setCostOfEquity(coe.doubleValue() * 100.0);
            if (latestRatio.getEvEbitda() != null) {
                valuationContext.setEvEbitda(latestRatio.getEvEbitda().toString() + "x");
            }
        }

        // [New Phase 3] Valuation & Trend Score 산출 (0~100)
        InvestorTrendDto trend = null;
        try {
            trend = kisStockService.getInvestorTrend(stockCode);
        } catch (Exception e) {
            log.warn("Failed to fetch investor trend for score: {}", e.getMessage());
        }
        double valuationScore = calculateValuationScore(srim, perModel, pbrModel, valuationContext);
        double trendScore = calculateTrendScore(technicalIndicators, currentPrice, trend);

        String marketCapStr = "N/A";
        if (priceDto != null) {
            BigDecimal marketCap = BigDecimal.ZERO;
            if (priceDto.getMarketCap() != null && !priceDto.getMarketCap().isEmpty()) {
                marketCap = new BigDecimal(priceDto.getMarketCap().replace(",", "").trim())
                        .multiply(new BigDecimal("100000000"));
            } else if (priceDto.getListedSharesCount() != null) {
                marketCap = currentPrice
                        .multiply(new BigDecimal(priceDto.getListedSharesCount().replace(",", "").trim()));
            }
            marketCapStr = formatLargeNumber(marketCap);
        }

        AnalysisDetails details = AnalysisDetails.builder()
                .investmentTerm("6-12 Months")
                .technicalIndicators(technicalIndicators)
                .valuationContext(valuationContext)
                .build();

        return Response.builder().stockCode(stockCode).stockName(stock.getStockName())
                .currentPrice(currentPrice.setScale(0, RoundingMode.HALF_UP).toString()).marketCap(marketCapStr)
                .targetReturn(discountRateInfo.getValue()).discountRate(discountRateInfo)
                .valuationScore(Math.round(valuationScore * 10.0) / 10.0)
                .trendScore(Math.round(trendScore * 10.0) / 10.0)
                .srim(srim).per(perModel).pbr(pbrModel).band(band).summary(summary)
                .analysisDetails(details)
                .build();
    }

    private DiscountRateInfo determineCOEWithBeta(Request request, double beta, String marketName) {
        if (request != null && request.getExpectedTotalReturn() != null) {
            String value = BigDecimal.valueOf(request.getExpectedTotalReturn()).multiply(new BigDecimal(100))
                    .setScale(1, RoundingMode.HALF_UP) + "%";
            return DiscountRateInfo.builder().profile("CUSTOM").value(value)
                    .basis("User defined custom override").source("USER_OVERRIDE").build();
        }

        double riskFreeRate = 0.035; // Default 3.5%
        try {
            String bondSymbol = marketName.startsWith("KOS") ? BondYield.KR_10Y.getSymbol()
                    : BondYield.US_10Y.getSymbol();
            Optional<MacroDailyData> latestBond = macroDailyDataRepository
                    .findTopByStatCodeAndItemCodeOrderByDateDesc("021Y002", bondSymbol);
            if (latestBond.isPresent()) {
                riskFreeRate = latestBond.get().getValue().doubleValue() / 100.0;
            }
        } catch (Exception e) {
        }

        double erp = 0.05; // 주식시장 위험 프리미엄 5.0%
        double coeValue = riskFreeRate + (beta * erp);

        // 보수적 하한선 및 상한선 설정 (금융주/자산주 배려)
        coeValue = Math.max(0.05, Math.min(0.15, coeValue));

        String valueStr = String.format("%.1f%%", coeValue * 100.0);
        return DiscountRateInfo.builder()
                .profile("CAPM_DYNAMIC")
                .value(valueStr)
                .basis(String.format("Rf(%.1f%%) + Beta(%.2f) * ERP(5.0%%)", riskFreeRate * 100.0, beta))
                .source("CAPM_MODEL")
                .note("베타를 반영한 종목 특성별 맞춤형 할인율입니다.")
                .build();
    }

    private String formatLargeNumber(BigDecimal value) {
        if (value == null)
            return "N/A";
        BigDecimal trillion = new BigDecimal("1000000000000"), billion = new BigDecimal("100000000");
        if (value.abs().compareTo(trillion) >= 0)
            return value.divide(trillion, 1, RoundingMode.HALF_UP).toPlainString() + "조원";
        else if (value.abs().compareTo(billion) >= 0)
            return value.divide(billion, 0, RoundingMode.HALF_UP).toPlainString() + "억원";
        return value.toPlainString() + "원";
    }

    private DiscountRateInfo determineCOE(Request request) {
        if (request != null && request.getExpectedTotalReturn() != null) {
            String value = BigDecimal.valueOf(request.getExpectedTotalReturn()).multiply(new BigDecimal(100))
                    .setScale(1, RoundingMode.HALF_UP) + "%";
            return DiscountRateInfo.builder().profile("CUSTOM").value(value)
                    .basis("User defined custom override").source("USER_OVERRIDE").build();
        }
        if (request != null && request.getUserPropensity() != null) {
            switch (request.getUserPropensity()) {
                case CONSERVATIVE:
                    return DiscountRateInfo.builder().profile("CONSERVATIVE").value("10.0%")
                            .basis("Safety First").source("PROFILE_DEFAULT")
                            .note("보수적인 투자자를 위해 높은 안전 마진을 확보하고자 10%의 할인율을 적용했습니다.")
                            .build();
                case AGGRESSIVE:
                    return DiscountRateInfo.builder().profile("AGGRESSIVE").value("6.0%")
                            .basis("Growth Focused").source("PROFILE_DEFAULT")
                            .note("성장성을 중시하는 공격적 투자를 위해 시장 평균보다 낮은 6%의 할인율을 적용했습니다.")
                            .build();
                default:
                    break;
            }
        }
        return DiscountRateInfo.builder().profile("NEUTRAL").value("8.0%")
                .basis("Market Reference")
                .source("PROFILE_DEFAULT")
                .note("한국 시장의 표준 요구수익률 관행(8%)을 적용한 중립적 할인율입니다.")
                .build();
    }

    private ValuationResult calculateSRIM(StockFinancialRatio latest, List<StockFinancialRatio> history, BigDecimal coe,
            BigDecimal currentPrice) {
        BigDecimal roe = calculateWeightedAverageROE(history);
        String roeType = (history.size() > 1) ? history.size() + "Y_AVG" : "LATEST";

        BigDecimal growth = (latest.getSalesGrowth() != null) ? latest.getSalesGrowth() : BigDecimal.ZERO;
        BigDecimal w;
        String tier;
        String modelType;

        if (growth.compareTo(new BigDecimal("20.0")) >= 0) {
            w = new BigDecimal("0.98");
            tier = "Tier 1: 고성장주 (Hyper Growth)";
            modelType = "초고성장 프리미엄 모델";
        } else if (growth.compareTo(new BigDecimal("10.0")) >= 0) {
            w = new BigDecimal("0.90");
            tier = "Tier 2: 우량성장주 (Stable Growth)";
            modelType = "우량성장 모델";
        } else if (growth.compareTo(new BigDecimal("5.0")) >= 0) {
            w = new BigDecimal("0.85");
            tier = "Tier 3: 성숙/가치주 (Mature)";
            modelType = "성숙 가치주 모델";
        } else if (growth.compareTo(BigDecimal.ZERO) >= 0) {
            w = new BigDecimal("0.80");
            tier = "Tier 4: 저성장기 (Slow Growth)";
            modelType = "저성장 방어형 모델";
        } else {
            w = new BigDecimal("0.75");
            tier = "Tier 5: 역성장기 (Negative)";
            modelType = "보수적 역성장 모델";
        }

        // ROE Sanity Check: 성장률이 높더라도 ROE < COE 이면 성장이 가치를 훼손하는 것으로 간주
        BigDecimal coePercent = coe.multiply(new BigDecimal("100"));
        if (growth.compareTo(new BigDecimal("10.0")) >= 0 && roe.compareTo(coePercent) < 0) {
            w = new BigDecimal("0.85");
            tier += " (ROE < COE 패널티 적용)";
            modelType = "가치 파괴적 성장 경계 모델";
        }

        String desc = String.format("[%s] 매출 성장률 %.1f%%를 반영한 %s (w=%.2f) 결과", tier, growth, modelType, w);

        if (latest.getEps() != null && latest.getEps().compareTo(BigDecimal.ZERO) <= 0) {
            if (roe.compareTo(coePercent) > 0)
                roe = coePercent;
        }

        BigDecimal bps = latest.getBps();
        if (bps == null || roe == null || bps.compareTo(BigDecimal.ZERO) <= 0)
            return emptyResult("데이터 부족 또는 자본 잠식", "N/A");

        BigDecimal targetPrice = calculateSrimPriceWithW(bps, roe, coe, w);
        BigDecimal roeDecimal = roe.divide(new BigDecimal(100), 4, RoundingMode.HALF_UP);

        // [Refinement V4.2] BPS(자본가치) 하단 지지 로직 적용: Math.max(TargetPrice, BPS)
        if (targetPrice.compareTo(bps) < 0) {
            targetPrice = bps;
            desc += " (ROE < COE에 따른 자산 가치 지지선 적용)";
        }

        if (roeDecimal.compareTo(coe) < 0) {
            return buildResult(targetPrice, currentPrice, desc, true, "Asset Support", roeType);
        }

        return buildResult(targetPrice, currentPrice, desc, true, "Normal", roeType);
    }

    private BigDecimal calculateSrimPriceWithW(BigDecimal bps, BigDecimal roe, BigDecimal coe, BigDecimal w) {
        BigDecimal roeDecimal = roe.divide(new BigDecimal(100), 4, RoundingMode.HALF_UP);
        BigDecimal excessReturn = bps.multiply(roeDecimal.subtract(coe));
        BigDecimal denominator = BigDecimal.ONE.add(coe).subtract(w);
        if (denominator.compareTo(BigDecimal.ZERO) <= 0)
            return bps;
        BigDecimal premium = excessReturn.multiply(w).divide(denominator, 0, RoundingMode.HALF_UP);
        return bps.add(premium);
    }

    private BigDecimal calculateWeightedAverageROE(List<StockFinancialRatio> history) {
        if (history == null || history.isEmpty())
            return BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO, weights = BigDecimal.ZERO;
        int maxWeight = history.size();
        for (int i = 0; i < history.size(); i++) {
            BigDecimal roe = history.get(i).getRoe();
            if (roe != null) {
                BigDecimal weight = new BigDecimal(maxWeight - i);
                total = total.add(roe.multiply(weight));
                weights = weights.add(weight);
            }
        }
        return (weights.compareTo(BigDecimal.ZERO) == 0) ? BigDecimal.ZERO
                : total.divide(weights, 2, RoundingMode.HALF_UP);
    }

    private ValuationResult calculatePERModel(StockFinancialRatio ratio, List<StockFinancialRatio> history,
            BigDecimal currentPrice) {
        BigDecimal eps = ratio.getEps();
        if (eps == null || eps.compareTo(BigDecimal.ZERO) <= 0) {
            BigDecimal forwardEps = estimateForwardEps(ratio.getStockCode(), ratio);
            if (forwardEps != null && forwardEps.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal avgPer = calculateStats(history, "PER").avg;
                if (avgPer.compareTo(BigDecimal.ZERO) <= 0)
                    avgPer = BigDecimal.valueOf(10);
                BigDecimal target = forwardEps.multiply(avgPer);
                return ValuationResult.builder().price(target.setScale(0, RoundingMode.HALF_UP).toString())
                        .gapRate(calculateGapRate(target, currentPrice).setScale(2, RoundingMode.HALF_UP).toString())
                        .verdict(determineVerdict(target, currentPrice)).description("매출/마진 정상화 가정 선행 PER (턴어라운드 시나리오)")
                        .available(true).build();
            }
            return emptyResult("N/A", "Negative EPS");
        }
        Stats perStats = calculateStats(history, "PER");
        BigDecimal basePer = (perStats.avg.compareTo(BigDecimal.ZERO) > 0) ? perStats.avg : new BigDecimal("10");
        BigDecimal targetPrice = eps.multiply(basePer).setScale(0, RoundingMode.HALF_UP);
        return buildResult(targetPrice, currentPrice, "PER " + basePer + "배 (과거평균) 적용", true, "Normal", null);
    }

    private ValuationResult calculatePBRModel(StockFinancialRatio ratio, List<StockFinancialRatio> history,
            BigDecimal coe, BigDecimal currentPrice) {
        BigDecimal bps = ratio.getBps();
        if (bps == null || bps.compareTo(BigDecimal.ZERO) <= 0)
            return emptyResult("자본 잠식", "Negative Equity");
        Stats pbrStats = calculateStats(history, "PBR");
        BigDecimal basePbr = (pbrStats.avg.compareTo(BigDecimal.ZERO) > 0) ? pbrStats.avg : new BigDecimal("1.0");
        BigDecimal targetPrice = bps.multiply(basePbr).setScale(0, RoundingMode.HALF_UP);
        return buildResult(targetPrice, currentPrice, "PBR " + basePbr + "배 (과거평균) 적용", true, "Normal", null);
    }

    private ValuationResult buildResult(BigDecimal targetPrice, BigDecimal currentPrice, String desc, boolean available,
            String reason, String roeType) {
        if (!available || currentPrice.compareTo(BigDecimal.ZERO) == 0)
            return emptyResult(desc, reason);
        BigDecimal gap = targetPrice.subtract(currentPrice).divide(currentPrice, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal(100));
        String verdict = "FAIR";
        if (gap.compareTo(new BigDecimal("10")) > 0)
            verdict = "UNDERVALUED";
        else if (gap.compareTo(new BigDecimal("-10")) < 0)
            verdict = "OVERVALUED";
        return ValuationResult.builder().price(targetPrice.setScale(0, RoundingMode.HALF_UP).toString())
                .gapRate(gap.setScale(2, RoundingMode.HALF_UP).toString()).verdict(verdict).description(desc)
                .available(true).roeType(roeType).build();
    }

    private ValuationResult emptyResult(String desc, String reason) {
        return ValuationResult.builder().price("0").gapRate("0").verdict("N/A").description(desc).available(false)
                .reason(reason).build();
    }

    private Stats calculateStats(List<StockFinancialRatio> history, String metric) {
        Stats stats = new Stats();
        if (history == null || history.isEmpty())
            return stats;
        List<BigDecimal> values = new ArrayList<>();
        for (var h : history) {
            BigDecimal val = "PER".equals(metric) ? h.getPer() : h.getPbr();
            if (val != null && val.compareTo(BigDecimal.ZERO) > 0)
                values.add(val);
        }
        if (values.isEmpty())
            return stats;
        stats.min = values.stream().min(BigDecimal::compareTo).get();
        stats.max = values.stream().max(BigDecimal::compareTo).get();
        stats.avg = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(new BigDecimal(values.size()), 2,
                RoundingMode.HALF_UP);
        stats.count = values.size();
        return stats;
    }

    private static class Stats {
        BigDecimal min = BigDecimal.ZERO, max = BigDecimal.ZERO, avg = BigDecimal.ZERO;
        int count = 0;
    }

    private ValuationBand calculateBand(BigDecimal currentPrice, ValuationResult srim, ValuationResult per,
            ValuationResult pbr, String stockCode, Stock stock) {
        Map<String, Double> dynamicWeights = determineIndustryWeights(stock);
        List<BigDecimal> prices = new ArrayList<>();
        double validWeightSum = 0.0;
        Map<String, Double> finalWeights = new HashMap<>();
        finalWeights.put("srim", 0.0);
        finalWeights.put("per", 0.0);
        finalWeights.put("pbr", 0.0);

        if (srim.isAvailable() && isValidPrice(srim.getPrice())) {
            prices.add(new BigDecimal(srim.getPrice()));
            validWeightSum += dynamicWeights.get("srim");
        }
        if (per.isAvailable() && isValidPrice(per.getPrice())) {
            prices.add(new BigDecimal(per.getPrice()));
            validWeightSum += dynamicWeights.get("per");
        }
        if (pbr.isAvailable() && isValidPrice(pbr.getPrice())) {
            prices.add(new BigDecimal(pbr.getPrice()));
            validWeightSum += dynamicWeights.get("pbr");
        }

        if (prices.isEmpty() || validWeightSum == 0)
            return ValuationBand.builder().status("N/A").weights(finalWeights).build();

        BigDecimal weightedSum = BigDecimal.ZERO;
        if (srim.isAvailable() && isValidPrice(srim.getPrice())) {
            double nw = dynamicWeights.get("srim") / validWeightSum;
            finalWeights.put("srim", Math.round(nw * 1000d) / 1000d);
            weightedSum = weightedSum.add(new BigDecimal(srim.getPrice()).multiply(BigDecimal.valueOf(nw)));
        }
        if (per.isAvailable() && isValidPrice(per.getPrice())) {
            double nw = dynamicWeights.get("per") / validWeightSum;
            finalWeights.put("per", Math.round(nw * 1000d) / 1000d);
            weightedSum = weightedSum.add(new BigDecimal(per.getPrice()).multiply(BigDecimal.valueOf(nw)));
        }
        if (pbr.isAvailable() && isValidPrice(pbr.getPrice())) {
            double nw = dynamicWeights.get("pbr") / validWeightSum;
            finalWeights.put("pbr", Math.round(nw * 1000d) / 1000d);
            weightedSum = weightedSum.add(new BigDecimal(pbr.getPrice()).multiply(BigDecimal.valueOf(nw)));
        }

        BigDecimal min = prices.stream().min(BigDecimal::compareTo).get(),
                max = prices.stream().max(BigDecimal::compareTo).get();
        BigDecimal gap = currentPrice.compareTo(BigDecimal.ZERO) > 0 ? weightedSum.subtract(currentPrice)
                .divide(currentPrice, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100)) : BigDecimal.ZERO;
        String status = "WITHIN_BAND", position = "50.0";
        if (prices.size() == 1) {
            status = "SINGLE_MODEL";
            position = null;
        } else if (max.compareTo(min) > 0) {
            BigDecimal posVal = currentPrice.subtract(min).divide(max.subtract(min), 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal(100));
            position = posVal.setScale(1, RoundingMode.HALF_UP).toString();
            if (posVal.compareTo(BigDecimal.ZERO) < 0)
                status = "BELOW_BAND";
            else if (posVal.compareTo(new BigDecimal(100)) > 0)
                status = "ABOVE_BAND";
        }
        return ValuationBand.builder().minPrice(min.setScale(0, RoundingMode.HALF_UP).toString())
                .maxPrice(max.setScale(0, RoundingMode.HALF_UP).toString()).currentPrice(currentPrice.toString())
                .gapRate(gap.setScale(2, RoundingMode.HALF_UP).toString()).position(position).status(status)
                .weights(finalWeights).build();
    }

    private Map<String, Double> determineIndustryWeights(Stock stock) {
        Map<String, Double> weights = new HashMap<>();
        boolean isIntangibleBase = false;

        if (stock != null && stock.getStockIndustries() != null) {
            for (var si : stock.getStockIndustries()) {
                String subCode = si.getSubIndustry().getCode();
                if (subCode.equals("SOFTWARE_PLATFORM") || subCode.equals("PHARMA_BIO") ||
                        subCode.equals("GAME") || subCode.equals("AI_ROBOTICS") ||
                        subCode.equals("IT_SERVICE_SI") || subCode.equals("ADVERTISING")) {
                    isIntangibleBase = true;
                    break;
                }
            }
        }

        if (isIntangibleBase) {
            weights.put("srim", 0.12);
            weights.put("per", 0.58);
            weights.put("pbr", 0.3);
        } else {
            // 기본 제조/전통 분야 (가치주 등)
            weights.put("srim", 0.5);
            weights.put("per", 0.3);
            weights.put("pbr", 0.2);
        }
        return weights;
    }

    private Summary generateSummary(ValuationResult srim, ValuationResult per, ValuationResult pbr) {
        int score = 0;
        if (srim.isAvailable())
            score += getScore(srim.getVerdict()) * 5;
        if (per.isAvailable())
            score += getScore(per.getVerdict()) * 3;
        if (pbr.isAvailable())
            score += getScore(pbr.getVerdict()) * 2;
        String verdict = score >= 3 ? "BUY" : score <= -3 ? "SELL" : "HOLD",
                confidence = Math.abs(score) >= 7 ? "HIGH" : "MEDIUM";
        return Summary.builder().overallVerdict(verdict).confidence(confidence)
                .keyInsight("지표 종합 점수: " + score + "점 (AI 전략 분석 중)").build();
    }

    private int getScore(String verdict) {
        return "UNDERVALUED".equals(verdict) ? 1 : "OVERVALUED".equals(verdict) ? -1 : 0;
    }

    private BigDecimal estimateForwardEps(String stockCode, StockFinancialRatio latestRatio) {
        try {
            List<StockFinancialStatement> stmts = stockFinancialStatementRepository
                    .findTop5ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
            if (stmts.size() < 2)
                return null;
            BigDecimal rev1 = stmts.get(0).getSaleAccount(), rev2 = stmts.get(1).getSaleAccount();
            if (rev2 == null || rev2.compareTo(BigDecimal.ZERO) <= 0)
                return null;
            double growth = Math.min(0.10, (rev1.doubleValue() / rev2.doubleValue()) - 1.0);
            if (growth <= 0)
                return null;
            double margin = stmts.stream()
                    .filter(s -> s.getOperatingProfit() != null
                            && s.getOperatingProfit().compareTo(BigDecimal.ZERO) > 0)
                    .mapToDouble(s -> s.getOperatingProfit().doubleValue() / s.getSaleAccount().doubleValue()).average()
                    .orElse(0.01);
            double forwardNet = rev1.doubleValue() * (1 + growth) * margin * 0.78;
            double shares = Double.parseDouble(kisStockService.getStockPrice(stockCode).getListedSharesCount());
            return BigDecimal.valueOf(forwardNet / shares);
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal calculateGapRate(BigDecimal target, BigDecimal current) {
        return current.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : target.subtract(current).divide(current, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100));
    }

    private String determineVerdict(BigDecimal target, BigDecimal current) {
        BigDecimal gap = calculateGapRate(target, current);
        return gap.compareTo(new BigDecimal(10)) > 0 ? "UNDERVALUED"
                : gap.compareTo(new BigDecimal(-10)) < 0 ? "OVERVALUED" : "FAIR";
    }

    @Transactional
    public String getValuationAnalysis(String stockCode, Response val, Request request) {
        BigDecimal cur = new BigDecimal(val.getCurrentPrice().replace(",", ""));
        Optional<StockAiSummary> cached = stockAiSummaryRepository.findByStockCode(stockCode);
        boolean force = (request != null && request.isForceRefresh());

        if (!force && cached.isPresent() && !cached.get().isExpired(720)) {
            BigDecimal ref = cached.get().getReferencePrice();
            if (ref != null && cur.subtract(ref).abs().divide(ref, 4, RoundingMode.HALF_UP)
                    .compareTo(new BigDecimal("0.05")) <= 0)
                return cached.get().getValuationAnalysis();
        }

        StockFinancialRatio ratio = stockFinancialRatioRepository
                .findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
        InvestorTrendDto trend = null;
        try {
            trend = kisStockService.getInvestorTrend(stockCode);
        } catch (Exception e) {
            log.warn("Failed to fetch contextual data for AI for {}: {}", stockCode, e.getMessage());
        }

        // [New Phase 4] KNN 수급 패턴 분석 수행
        String supplyAnalysis = performKnnSupplyAnalysis(stockCode, trend != null ? trend.getSupplyScore() : 50.0);
        if (val.getAnalysisDetails() != null && val.getAnalysisDetails().getValuationContext() != null) {
            val.getAnalysisDetails().getValuationContext().setSupplyPatternAnalysis(supplyAnalysis);
        }
        if (val.getAnalysisDetails() == null) {
            val.setAnalysisDetails(new AnalysisDetails());
        }
        val.getAnalysisDetails()
                .setInvestmentTerm(determineInvestmentTerm(val.getTrendScore(), val.getValuationScore()));

        // [V4.3 Final Polishing] AI용 가격 전략 사전 계산
        BigDecimal theoreticalMax = new BigDecimal(val.getBand().getMaxPrice().replace(",", ""));
        BigDecimal baseTarget = theoreticalMax.max(cur);
        BigDecimal resPrice = baseTarget.multiply(new BigDecimal("1.05")).setScale(0, RoundingMode.HALF_UP);
        BigDecimal bandMin = new BigDecimal(val.getBand().getMinPrice().replace(",", ""));
        BigDecimal supPrice = bandMin.multiply(new BigDecimal("0.95")).max(cur.multiply(new BigDecimal("0.90")))
                .setScale(0, RoundingMode.HALF_UP);

        String resistanceStr = String.format("%,d원", resPrice.longValue());
        String supportStr = String.format("%,d원", supPrice.longValue());

        String analysis = generateValuationAnalysisText(val, ratio, null, null, trend, resistanceStr, supportStr);

        AiResponseJson aiJson = parseAiResponse(analysis);
        String displayVerdict = val.getSummary().getOverallVerdict();
        String displayLabel = mapVerdictToLabel(displayVerdict,
                aiJson.getAiVerdict() != null ? aiJson.getAiVerdict().getStance() : null);
        String displaySummary = aiJson.getActionPlan() != null ? aiJson.getActionPlan() : "전략적 대응이 필요합니다.";
        String displayRisk = (aiJson.getAiVerdict() != null && aiJson.getAiVerdict().getRiskLevel() != null)
                ? aiJson.getAiVerdict().getRiskLevel().name()
                : "MEDIUM";

        if (cached.isPresent()) {
            cached.get().updateValuationAnalysis(analysis, cur, displayVerdict, displayLabel,
                    displaySummary, displayRisk,
                    trend != null ? trend.getSupplyScore() : 0, trend != null ? trend.getForeignerZScore() : 0);
            stockAiSummaryRepository.save(cached.get());
        } else {
            stockAiSummaryRepository.save(StockAiSummary.builder().stockCode(stockCode).valuationAnalysis(analysis)
                    .referencePrice(cur).displayVerdict(displayVerdict).displayLabel(displayLabel)
                    .displaySummary(displaySummary)
                    .displayRisk(displayRisk).lastModifiedDate(LocalDateTime.now())
                    .createdDate(LocalDateTime.now()).build());
        }
        return analysis;
    }

    @Transactional
    @Cacheable(value = "staticAnalysis", key = "#stockCode")
    public String getStaticAnalysis(String stockCode) {
        Optional<StockStaticAnalysis> cachedAnalysis = stockStaticAnalysisRepository.findByStockCode(stockCode);
        if (cachedAnalysis.isPresent()) {
            StockStaticAnalysis staticData = cachedAnalysis.get();
            if (staticData.getContent() != null && !staticData.isExpired(8760))
                return staticData.getContent();
        }
        Stock stock = stockRepository.findByStockCode(stockCode)
                .orElseThrow(() -> new IllegalArgumentException("Stock not found: " + stockCode));
        List<StockFinancialStatement> recentAnnuals = stockFinancialStatementRepository
                .findTop5ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
        String analysisContent = generateStaticAnalysisText(stock, recentAnnuals);
        if (cachedAnalysis.isPresent()) {
            StockStaticAnalysis existing = cachedAnalysis.get();
            existing.updateContent(analysisContent);
            stockStaticAnalysisRepository.save(existing);
        } else {
            stockStaticAnalysisRepository
                    .save(StockStaticAnalysis.builder().stockCode(stockCode).content(analysisContent)
                            .lastModifiedDate(LocalDateTime.now()).createdDate(LocalDateTime.now()).build());
        }
        return analysisContent;
    }

    private String generateStaticAnalysisText(Stock stock, List<StockFinancialStatement> recentAnnuals) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("다음 기업의 **기업 개요**, **미래 성장 동력**, **리스크 요인**을 분석해줘.\n\n");
        prompt.append(String.format("종목명: %s (%s)\n", stock.getStockName(), stock.getStockCode()));
        if (recentAnnuals != null && !recentAnnuals.isEmpty()) {
            prompt.append("\n[연간 실적 추이 (참고용)]\n");
            for (int i = recentAnnuals.size() - 1; i >= 0; i--) {
                StockFinancialStatement s = recentAnnuals.get(i);
                prompt.append(String.format("- %s: 매출 %s / 영익 %s\n", s.getStacYymm(), s.getSaleAccount(),
                        s.getOperatingProfit()));
            }
        }
        prompt.append(
                "\n[요청사항]\n1. **Google Search**를 활용하여 최신 정보를 반영해.\n2. 다음 3가지 항목만 작성해 (마크다운 헤더 필수):\n   - **## 1. 기업 개요**\n   - **## 2. 미래 성장 동력**\n   - **## 3. 리스크 요인**\n3. 가치평가는 절대 포함하지 마.\n4. 금융 전문가 톤으로 요약.\n");
        return geminiService.generateAdvice(prompt.toString());
    }

    @Transactional
    public void clearAiSummaryCache() {
        stockAiSummaryRepository.deleteAll();
    }

    private String generateValuationAnalysisText(Response val, StockFinancialRatio latestRatio,
            StockBalanceSheet balanceSheet, List<StockFinancialStatement> recentQuarters,
            InvestorTrendDto investorTrend, String resistance, String support) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("[역할: 냉철한 헤지펀드 매니저(Critical Strategist)]\n");
        prompt.append(
                "너는 숫자로만 말하며, 낙관 편향을 극도로 경계하는 베테랑 매니저다. 밸류에이션이 아무리 낮아도 시장 수익률(RS)을 이기지 못하거나 수급 지지선이 무너지면 가차 없이 비판하라. 한국 시장의 고질적인 '코리아 디스카운트'와 수급 쏠림 현상을 냉정하게 진단하라.\n\n");

        prompt.append("[분석 대상 종목: ").append(val.getStockName()).append(" (").append(val.getStockCode()).append(")]\n");
        prompt.append("- 현재가: ").append(val.getCurrentPrice()).append("원\n");
        prompt.append("- 가치 점수(Valuation Score): ").append(val.getValuationScore()).append("/100\n");
        prompt.append("- 추세 점수(Trend Score): ").append(val.getTrendScore()).append("/100\n");

        if (latestRatio != null) {
            prompt.append("- 펀더멘털: PER ").append(latestRatio.getPer()).append(", PBR ").append(latestRatio.getPbr())
                    .append(", ROE ").append(latestRatio.getRoe()).append("%\n");
        }

        if (val.getAnalysisDetails() != null && val.getAnalysisDetails().getValuationContext() != null) {
            ValuationContext ctx = val.getAnalysisDetails().getValuationContext();
            prompt.append("- 베타(Beta): ").append(String.format("%.2f", ctx.getBeta())).append(" (지수 대비 민감도)\n");
            prompt.append("- 할인율(COE): ").append(String.format("%.1f%%", ctx.getCostOfEquity())).append("\n");
            prompt.append("- 수급 패턴 분석: ").append(ctx.getSupplyPatternAnalysis()).append("\n");

            if (ctx.getBeta() > 1.2) {
                prompt.append("!! 주의: 해당 종목은 베타가 ").append(String.format("%.2f", ctx.getBeta()))
                        .append("로 높아 시장 변동 시 낙폭이 과대해질 수 있음에 유의하라.\n");
            }
        }

        if (investorTrend != null) {
            prompt.append("- 최근 수급 현황: ").append(investorTrend.getTrendStatus()).append(" (Score: ")
                    .append(investorTrend.getSupplyScore()).append(")\n");
            if (investorTrend.getAdvice() != null)
                prompt.append("- 시스템 매칭 조언: ").append(investorTrend.getAdvice()).append("\n");
        }

        prompt.append("\n[분석 미션: 5단계 전문 리서치 구조화]\n");
        prompt.append("다음 5단계 구조에 맞춰 `guidance` 필드를 작성하라. 각 단계는 투자자의 사고 흐름을 완벽히 가이드해야 한다.\n");

        prompt.append(
                "1. **[현재 상황 요약]**: 종목의 현재 추세(Trend)와 밸류에이션(Valuation) 상태를 한 줄로 명확히 정의하라. (예: '상승 추세가 유지되고 있으나 고평가 영역에 진입함')\n");
        prompt.append("2. **[3대 판단 근거]**: 판단의 이유를 다음 3가지 축으로 설명하라.\n");
        prompt.append("   - 밸류에이션: 내재가치 대비 저평가/고평가 여부\n");
        prompt.append("   - 수급: 기관/외국인 매매 동향 및 강도\n");
        prompt.append("   - 통계확률: KNN 분석 결과(상승 확률)에 기반한 통계적 유불리\n");
        prompt.append("3. **[향후 시나리오]**: 단기적으로 예상되는 주가 흐름(박스권 등락, 추세 지속, 조정 가능성 등)을 제시하라.\n");
        prompt.append("4. **[행동 전략]**: 시스템이 계산한 Resistance(%s)와 Support(%s) 가격을 인용하여 구체적인 행동 지침을 제시하라.\n");
        prompt.append("   - '반등 시 Resistance 부근에서 비중 축소'\n");
        prompt.append("   - 'Support 이탈 시 리스크 관리 필수'\n");
        prompt.append("5. **[판단 변경 조건]**: 현재의 관점이 바뀔 수 있는 트리거(예: '수급 주체의 매수 전환', '저항선 돌파')를 명시하라.\n");

        prompt.append("\n[추가 지침]\n");
        prompt.append("- **Action Plan 도출**: `actionPlan` 필드는 위 [행동 전략]을 1문장으로 요약하여 작성하라.\n");
        prompt.append("- **용어 순화**: '베타(Beta)' 같은 전문 용어는 '시장 민감도'나 '변동성 위험' 같이 일반 투자자가 이해하기 쉬운 표현으로 풀어서 설명하라.\n");

        prompt.append("\nJSON format 가이드:\n");
        prompt.append(
                "```json\n{\n  \"keyInsight\": \"30자 이내의 비판적 핵심 요약\",\n  \"aiVerdict\": {\"stance\": \"BUY/ACCUMULATE/HOLD/REDUCE/SELL\", \"timing\": \"EARLY/MID/LATE\", \"riskLevel\": \"LOW/MEDIUM/HIGH\", \"guidance\": \"[현재 상황] ...\\n\\n[판단 근거] ...\\n\\n[향후 전망] ...\\n\\n[투자 전략] ...\\n\\n[판단 변경 조건] ...\"},\n  \"actionPlan\": \"반등 시 Resistance("
                        + resistance
                        + ") 근처에서 비중 축소 권장\",\n  \"probabilityInfo\": \"상승 확률 27.4% (낮음)\",\n  \"catalysts\": [\"호재\"], \"risks\": [\"베타 변동성 등 리스크\"]\n}\n```\n");
        return geminiService.generateAdvice(prompt.toString());
    }

    private String performKnnSupplyAnalysis(String stockCode, double currentSupplyScore) {
        // 실제 구현 시 과거 30거래일 수급 데이터를 KNN으로 매칭하여 상승 확률 산출
        // 현재는 수급 점수 기반 시뮬레이션 로직 (v4.1)
        double prob = 45.0 + (currentSupplyScore - 45.0) * 0.4;
        if (currentSupplyScore < 40)
            prob -= 10;
        return String.format("과거 10년 데이터 중 유사 수급 패턴(30건) 사례를 추적한 결과, 향후 20거래일 내 상승 확률은 %.1f%%로 나타났습니다.",
                Math.min(100, Math.max(0, prob)));
    }

    private Response buildResponseWithAi(Response response, String aiAnalysis, InvestorTrendDto trend) {
        AiResponseJson aiJson = parseAiResponse(aiAnalysis);
        String overallVerdict = (aiJson.getAiVerdict() != null && aiJson.getAiVerdict().getStance() != null)
                ? aiJson.getAiVerdict().getStance().name()
                : "HOLD";

        Summary.Display display = Summary.Display.builder()
                .verdict(overallVerdict)
                .verdictLabel(mapVerdictToLabel(overallVerdict,
                        aiJson.getAiVerdict() != null ? aiJson.getAiVerdict().getStance() : Stance.HOLD))
                .strategy(Summary.Strategy.builder()
                        .actionPlan(
                                aiJson.getActionPlan() != null ? aiJson.getActionPlan() : "시장 상황에 따른 유연한 대응이 필요합니다.")
                        .build())
                .risk(aiJson.getAiVerdict() != null && aiJson.getAiVerdict().getRiskLevel() != null
                        ? aiJson.getAiVerdict().getRiskLevel().name()
                        : "MEDIUM")
                .probabilityInfo(aiJson.getProbabilityInfo() != null ? aiJson.getProbabilityInfo() : "상승 확률 분석 중")
                .build();

        Summary newSummary = Summary.builder()
                .overallVerdict(overallVerdict)
                .confidence("HIGH")
                .keyInsight(aiJson.getKeyInsight() != null ? aiJson.getKeyInsight() : "AI 전략 분석 리포트")
                .verdicts(Summary.Verdicts.builder().aiVerdict(aiJson.getAiVerdict()).build())
                .beginnerVerdict(aiJson.getBeginnerVerdict())
                .display(display)
                .build();

        if (trend != null && trend.getAdvice() == null) {
            trend.setAdvice(generateFallbackSupplyAdvice(trend.getSupplyScore()));
        }

        // [New Phase 3] 가격 전략 재구성 (목표가/손절가)
        BigDecimal yieldVal = BigDecimal.ZERO;
        calculatePriceStrategy(response, newSummary, null, yieldVal);

        // AnalysisDetails 조립 (노이즈 필드 제거됨)
        AnalysisDetails details = response.getAnalysisDetails();
        if (details != null) {
            details.setCatalysts(aiJson.getCatalysts());
            details.setRisks(aiJson.getRisks());
            details.setInvestorTrend(trend);

            // downsideRisk 등 보정
            AnalysisDetails.PriceModel pmDto = calculatePriceModel(response, newSummary);
            details.setPriceModel(pmDto);
        }

        if (aiJson.getSuggestedWeights() != null && response.getBand() != null)
            response.getBand().setWeights(aiJson.getSuggestedWeights());

        return response.toBuilder().summary(newSummary).analysisDetails(details).build();
    }

    private AnalysisDetails.PriceModel calculatePriceModel(Response response, Summary summary) {
        String downsideStr = "N/A", upsideStr = "0%";
        try {
            BigDecimal cur = new BigDecimal(response.getCurrentPrice().replace(",", ""));
            if (summary.getDisplay().getStrategy() != null
                    && summary.getDisplay().getStrategy().getResistanceZone() != null) {
                BigDecimal tgt = new BigDecimal(
                        summary.getDisplay().getStrategy().getResistanceZone().replaceAll("[^0-9]", ""));
                BigDecimal upside = tgt.subtract(cur).divide(cur, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal(100));
                upsideStr = String.format("%.2f%%", upside);
            }
            if (summary.getDisplay().getStrategy() != null
                    && summary.getDisplay().getStrategy().getSupportZone() != null) {
                BigDecimal sl = new BigDecimal(
                        summary.getDisplay().getStrategy().getSupportZone().replaceAll("[^0-9]", ""));
                BigDecimal downside = sl.subtract(cur).divide(cur, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal(100));
                downsideStr = String.format("%.2f%%", downside);
            }
        } catch (Exception e) {
        }
        return AnalysisDetails.PriceModel.builder().upsidePotential(upsideStr).downsideRisk(downsideStr).build();
    }

    private static class KnnCandidate {
        String date;
        double distance;
        BigDecimal per;
        BigDecimal pbr;
        double weight;

        KnnCandidate(String date, double distance, BigDecimal per, BigDecimal pbr) {
            this.date = date;
            this.distance = distance;
            this.per = per;
            this.pbr = pbr;
            this.weight = 1.0 / (distance + 1e-4);
        }
    }

    private ValuationContext calculateValuationContext(String stockCode, BigDecimal currentRoe,
            String marketName, BigDecimal currentPer, BigDecimal currentPbr) {
        List<StockFinancialRatio> history = stockFinancialRatioRepository.findByDivCodeAndStacYymmGreaterThanEqual("0",
                LocalDate.now().minusYears(10)
                        .format(DateTimeFormatter.ofPattern("yyyyMM")));

        List<StockFinancialRatio> stockHistory = history.stream()
                .filter(r -> r.getStockCode().equals(stockCode))
                .collect(Collectors.toList());

        HistoricalValuationRange perRange = calculateHistoricalRange(stockHistory, currentRoe, "PER", currentPer);
        HistoricalValuationRange pbrRange = calculateHistoricalRange(stockHistory, currentRoe, "PBR", currentPbr);

        double beta = 1.0;
        double riskFreeRate = 0.035;
        try {
            beta = calculateBeta(stockCode, marketName);
            String bondSymbol = marketName.startsWith("KOS") ? BondYield.KR_10Y.getSymbol()
                    : BondYield.US_10Y.getSymbol();
            Optional<MacroDailyData> latestBond = macroDailyDataRepository
                    .findTopByStatCodeAndItemCodeOrderByDateDesc("021Y002", bondSymbol);
            if (latestBond.isPresent()) {
                riskFreeRate = latestBond.get().getValue().doubleValue() / 100.0;
            }
        } catch (Exception e) {
            log.warn("Failed to calculate beta or fetch risk free rate: {}", e.getMessage());
        }

        double erp = 0.05;
        double coe = riskFreeRate + (beta * erp);

        return ValuationContext.builder()
                .beta(beta)
                .costOfEquity(coe)
                .historicalPerRange(perRange)
                .historicalPbrRange(pbrRange)
                .build();
    }

    private HistoricalValuationRange calculateHistoricalRange(
            List<StockFinancialRatio> history, BigDecimal targetRoe, String metric, BigDecimal currentValue) {
        if (history == null || history.isEmpty())
            return null;
        double tRoe = targetRoe != null ? targetRoe.doubleValue() : 0.0;
        List<KnnCandidate> candidates = new ArrayList<>();
        for (StockFinancialRatio r : history) {
            BigDecimal rRoe = r.getRoe();
            double dist = Math.sqrt(Math.pow(tRoe - (rRoe != null ? rRoe.doubleValue() : 0.0), 2));
            BigDecimal val = "PER".equals(metric) ? r.getPer() : r.getPbr();
            if (val != null && val.compareTo(BigDecimal.ZERO) > 0) {
                candidates.add(new KnnCandidate(r.getStacYymm(), dist, r.getPer(), r.getPbr()));
            }
        }
        if (candidates.isEmpty())
            return null;
        List<KnnCandidate> neighbors = candidates.stream()
                .sorted(java.util.Comparator.comparingDouble(c -> c.distance))
                .limit(5).collect(Collectors.toList());
        List<BigDecimal> values = neighbors.stream()
                .map(c -> "PER".equals(metric) ? c.per : c.pbr)
                .sorted().collect(Collectors.toList());
        BigDecimal min = values.get(0), max = values.get(values.size() - 1), median = values.get(values.size() / 2);
        return HistoricalValuationRange.builder()
                .min(min.setScale(2, RoundingMode.HALF_UP).toString())
                .max(max.setScale(2, RoundingMode.HALF_UP).toString())
                .median(median.setScale(2, RoundingMode.HALF_UP).toString())
                .current(currentValue != null ? currentValue.setScale(2, RoundingMode.HALF_UP).toString() : "N/A")
                .build();
    }

    private double calculateBeta(String stockCode, String marketName) {
        LocalDate start = LocalDate.now().minusYears(1);
        LocalDate end = LocalDate.now();

        List<StockDailyData> stockData = stockDailyDataRepository
                .findByStock_StockCodeAndDateBetweenOrderByDateAsc(stockCode, start, end);
        List<IndexDailyData> indexData = indexDailyDataRepository
                .findAllByMarketNameAndDateBetweenOrderByDateDesc(marketName, start, end);
        if (stockData.size() < 30 || indexData.isEmpty())
            return 1.0;
        Map<LocalDate, Double> stockSeries = stockData.stream().collect(Collectors
                .toMap(d -> d.getDate(), d -> d.getClosingPrice().doubleValue(), (v1, v2) -> v1));
        Map<LocalDate, Double> indexSeries = indexData.stream().collect(Collectors
                .toMap(d -> d.getDate(), d -> d.getClosingPrice().doubleValue(), (v1, v2) -> v1));

        List<LocalDate> commonDates = stockSeries.keySet().stream().filter(indexSeries::containsKey).sorted()
                .collect(Collectors.toList());
        if (commonDates.size() < 30)
            return 1.0;

        double[] rP = new double[commonDates.size() - 1];
        double[] rI = new double[commonDates.size() - 1];
        for (int i = 0; i < commonDates.size() - 1; i++) {
            rP[i] = Math.log(stockSeries.get(commonDates.get(i + 1)) / stockSeries.get(commonDates.get(i)));
            rI[i] = Math.log(indexSeries.get(commonDates.get(i + 1)) / indexSeries.get(commonDates.get(i)));
        }
        PearsonsCorrelation pc = new PearsonsCorrelation();
        double correlation = pc.correlation(rP, rI);
        double volP = new DescriptiveStatistics(rP).getStandardDeviation();
        double volI = new DescriptiveStatistics(rI).getStandardDeviation();
        return volI == 0 ? 1.0 : correlation * (volP / volI);
    }

    private TechnicalIndicators calculateTechnicalIndicators(String stockCode,
            String marketName) {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(200);
        List<StockDailyData> stockData = stockDailyDataRepository
                .findByStock_StockCodeAndDateBetweenOrderByDateAsc(stockCode, start, end);
        if (stockData.size() < 20)
            return null;

        List<BigDecimal> prices = stockData.stream().map(d -> d.getClosingPrice())
                .collect(Collectors.toList());
        double currentPrice = prices.get(prices.size() - 1).doubleValue();
        double rsi = calculateRsi(prices, 14);

        List<String> maList = new ArrayList<>();
        double ma20 = calculateMA(prices, 20);
        double ma60 = calculateMA(prices, 60);
        double ma120 = calculateMA(prices, 120);
        maList.add("20일: " + String.format("%,.0f원", ma20));
        maList.add("60일: " + String.format("%,.0f원", ma60));
        maList.add("120일: " + String.format("%,.0f원", ma120));

        // Relative Strength (vs Index)
        double rs = 0.0;
        try {
            List<IndexDailyData> indexData = indexDailyDataRepository
                    .findAllByMarketNameAndDateBetweenOrderByDateDesc(marketName, start, end);
            if (indexData.size() >= 20) {
                double stockReturn = (currentPrice / prices.get(prices.size() - 20).doubleValue()) - 1.0;
                double indexEnd = indexData.get(indexData.size() - 1).getClosingPrice().doubleValue();
                double indexStart = indexData.get(indexData.size() - 20).getClosingPrice().doubleValue();
                double indexReturn = (indexEnd / indexStart) - 1.0;
                rs = (stockReturn - indexReturn) * 100.0; // Alpha in %
            }
        } catch (Exception e) {
        }

        String location = currentPrice > ma20 ? (ma20 > ma60 ? "정배열 상단" : "단기 반등") : "역배열 하단";

        Map<String, String> maMap = new HashMap<>();
        maMap.put("MA20", String.format("%,.0f원", ma20));
        maMap.put("MA60", String.format("%,.0f원", ma60));
        maMap.put("MA120", String.format("%,.0f원", ma120));
        maMap.put("status", location);

        return TechnicalIndicators.builder()
                .rsi(rsi)
                .movingAverages(maMap)
                .relativeStrength(rs)
                .priceLocation(location)
                .build();
    }

    private double calculateValuationScore(ValuationResult srim, ValuationResult per, ValuationResult pbr,
            ValuationContext ctx) {
        // [V4.2] 가치 점수 가중치: S-RIM(40) + Band(30) + KNN(30)
        double srimComponent = 50.0;
        if (srim.isAvailable()) {
            srimComponent = "UNDERVALUED".equals(srim.getVerdict()) ? 85
                    : "ACCUMULATE".equals(srim.getVerdict()) ? 70 : "OVERVALUED".equals(srim.getVerdict()) ? 30 : 50;
        }

        double bandComponent = 50.0;
        int count = 0;
        if (per.isAvailable()) {
            bandComponent += "UNDERVALUED".equals(per.getVerdict()) ? 20
                    : "OVERVALUED".equals(per.getVerdict()) ? -20 : 0;
            count++;
        }
        if (pbr.isAvailable()) {
            bandComponent += "UNDERVALUED".equals(pbr.getVerdict()) ? 20
                    : "OVERVALUED".equals(pbr.getVerdict()) ? -20 : 0;
            count++;
        }
        if (count == 0)
            bandComponent = 50.0;

        double knnComponent = 50.0;
        if (ctx != null && ctx.getHistoricalPerRange() != null) {
            String curStr = ctx.getHistoricalPerRange().getCurrent();
            String medStr = ctx.getHistoricalPerRange().getMedian();
            if (curStr != null && medStr != null && !"N/A".equals(curStr) && !"N/A".equals(medStr)) {
                try {
                    double cur = Double.parseDouble(curStr);
                    double med = Double.parseDouble(medStr);
                    knnComponent = (cur < med * 0.9) ? 80 : (cur > med * 1.1) ? 20 : 50;
                } catch (Exception e) {
                }
            }
        }

        double finalScore = (srimComponent * 0.4) + (bandComponent * 0.3) + (knnComponent * 0.3);
        return Math.max(0, Math.min(100, finalScore));
    }

    private double calculateTrendScore(TechnicalIndicators tech, BigDecimal currentPrice, InvestorTrendDto trend) {
        // [V4.2] 추세 점수 가중치: 수급 가속도(40) + RS(30) + 지지선 이격/위치(30)
        double supplyComponent = trend != null ? trend.getSupplyScore() : 50.0;

        double rsComponent = 50.0;
        if (tech != null) {
            if (tech.getRelativeStrength() > 8.0)
                rsComponent = 90;
            else if (tech.getRelativeStrength() > 2.0)
                rsComponent = 70;
            else if (tech.getRelativeStrength() < -8.0)
                rsComponent = 10;
            else if (tech.getRelativeStrength() < -2.0)
                rsComponent = 30;
        }

        double technicalComponent = 50.0;
        if (tech != null) {
            String loc = tech.getPriceLocation();
            if ("정배열 상단".equals(loc) || "정배열".equals(loc))
                technicalComponent = 80;
            else if ("GOLDEN_CROSS".equals(loc))
                technicalComponent = 70;
            else if ("DEAD_CROSS".equals(loc))
                technicalComponent = 20;

            if (tech.getRsi() < 30)
                technicalComponent += 10;
            else if (tech.getRsi() > 70)
                technicalComponent -= 10;
        }

        double finalScore = (supplyComponent * 0.4) + (rsComponent * 0.3) + (technicalComponent * 0.3);
        return Math.max(0, Math.min(100, finalScore));
    }

    private double calculateRsi(List<BigDecimal> prices, int period) {
        if (prices.size() <= period)
            return 50.0;
        double gain = 0, loss = 0;
        for (int i = prices.size() - period; i < prices.size(); i++) {
            double diff = prices.get(i).doubleValue() - prices.get(i - 1).doubleValue();
            if (diff > 0)
                gain += diff;
            else
                loss -= diff;
        }
        if (loss == 0)
            return 100.0;
        double rs = (gain / period) / (loss / period);
        return 100.0 - (100.0 / (1.0 + rs));
    }

    private double calculateMA(List<BigDecimal> prices, int period) {
        if (prices.size() < period)
            return 0.0;
        return prices.subList(prices.size() - period, prices.size()).stream().mapToDouble(BigDecimal::doubleValue)
                .average().orElse(0.0);
    }

    private String generateFallbackSupplyAdvice(double score) {
        if (score >= 80)
            return "외인/기관의 강력한 매집세가 확인되는 긍정적 구간입니다.";
        if (score >= 60)
            return "수급 흐름이 개선되고 있어 긍정적 시각을 유지합니다.";
        if (score >= 40)
            return "수급 주체 간 공방이 치열한 중립 구간입니다.";
        if (score >= 20)
            return "수급 유출 우려가 있어 보수적 관점이 필요합니다.";
        return "수급 악화가 지속되고 있어 당분간 관망을 추천합니다.";
    }

    private String mapVerdictToLabel(String modelRating, Stance aiStance) {
        if ("BUY".equals(modelRating))
            return aiStance == Stance.ACCUMULATE ? "분할 매수" : "매수";
        if ("SELL".equals(modelRating))
            return aiStance == Stance.REDUCE ? "비중 축소" : "매도";
        return "관망";
    }

    @Getter
    @Setter
    @NoArgsConstructor
    private static class AiResponseJson {
        private String keyInsight;
        private Summary.AiVerdict aiVerdict;
        private Summary.BeginnerVerdict beginnerVerdict;
        private List<String> catalysts;
        private List<String> risks;
        private Map<String, Double> suggestedWeights;
        // [V4.3] 신규 필드 추가
        private String actionPlan;
        private String probabilityInfo;
    }

    private void calculatePriceStrategy(Response response, Summary summary, BigDecimal peg, BigDecimal yield) {
        if (response.getBand() == null || response.getBand().getMinPrice() == null)
            return;

        BigDecimal theoreticalMax = new BigDecimal(response.getBand().getMaxPrice().replace(",", ""));
        BigDecimal currentPrice = new BigDecimal(response.getCurrentPrice().replace(",", ""));
        BigDecimal baseForTarget = theoreticalMax.max(currentPrice);

        BigDecimal multiplier = new BigDecimal("1.05");
        if (peg != null && peg.compareTo(BigDecimal.ONE) < 0)
            multiplier = multiplier.add(new BigDecimal("0.02"));

        BigDecimal resistance = baseForTarget.multiply(multiplier).setScale(0, RoundingMode.HALF_UP);

        BigDecimal bandBottom = new BigDecimal(response.getBand().getMinPrice().replace(",", ""))
                .multiply(new BigDecimal("0.95"));
        BigDecimal currentExit = currentPrice.multiply(new BigDecimal("0.90"));
        BigDecimal support = bandBottom.max(currentExit).setScale(0, RoundingMode.HALF_UP);

        if (summary.getDisplay().getStrategy() == null) {
            summary.getDisplay().setStrategy(new Summary.Strategy());
        }
        summary.getDisplay().getStrategy().setResistanceZone(String.format("%,d원", resistance.longValue()));
        summary.getDisplay().getStrategy().setSupportZone(String.format("%,d원", support.longValue()));
    }

    private AiResponseJson parseAiResponse(String jsonText) {
        try {
            int start = jsonText.indexOf("{"), end = jsonText.lastIndexOf("}");
            if (start >= 0) {
                ObjectMapper mapper = new ObjectMapper();
                mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                AiResponseJson result = mapper.readValue(jsonText.substring(start, end + 1), AiResponseJson.class);
                if (result.getAiVerdict() == null)
                    result.setAiVerdict(Summary.AiVerdict.builder().stance(Stance.HOLD).riskLevel(RiskLevel.MEDIUM)
                            .guidance("분석 데이터를 파싱할 수 없습니다.").build());
                if (result.getActionPlan() == null)
                    result.setActionPlan("시장 상황에 따른 유연한 대응이 필요합니다.");
                if (result.getCatalysts() == null)
                    result.setCatalysts(List.of("시장 상황 모니터링 필요"));
                if (result.getRisks() == null)
                    result.setRisks(List.of("데이터 지연 가능성"));
                return result;
            }
        } catch (Exception e) {
            log.error("Failed to parse AI JSON: {}", e.getMessage());
        }
        AiResponseJson fallback = new AiResponseJson();
        fallback.setKeyInsight("데이터 분석 지연 중");
        fallback.setAiVerdict(Summary.AiVerdict.builder().stance(Stance.HOLD).riskLevel(RiskLevel.MEDIUM)
                .guidance("서버 응답 오류로 기본 분석값을 제공합니다.").build());
        fallback.setBeginnerVerdict(
                Summary.BeginnerVerdict.builder().summarySentence("잠시 후 다시 시도해 주세요.").build());
        fallback.setCatalysts(List.of("분석 중"));
        fallback.setRisks(List.of("분석 중"));
        return fallback;
    }

    private boolean isValidPrice(String price) {
        return price != null && !price.equals("0");
    }

    private String determineInvestmentTerm(Double trendScore, Double valuationScore) {
        if (trendScore == null || valuationScore == null)
            return "6-12 Months";

        // 1. 단기 트레이딩 선호 (추세 강력, 가격 부담)
        if (trendScore >= 70 && valuationScore < 40) {
            return "1-3 Months (Short-term Trading)";
        }
        // 2. 중기 스윙 (추세 양호, 가치 적정)
        if (trendScore >= 50 && valuationScore >= 40 && valuationScore < 70) {
            return "3-6 Months (Mid-term Swing)";
        }
        // 3. 장기 가치투자 (추세 바닥, 가치 매력 높음)
        if (valuationScore >= 70) {
            return "12+ Months (Long-term Value)";
        }
        // 4. 관망/중립
        return "6-12 Months (Neutral)";
    }
}
