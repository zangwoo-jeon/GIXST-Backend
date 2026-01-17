package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.dto.ValuationDto;
import com.AISA.AISA.analysis.dto.ValuationDto.*;
import com.AISA.AISA.analysis.dto.ValuationDto.Summary.AiVerdict;
import com.AISA.AISA.analysis.dto.ValuationDto.Summary.ModelVerdict;
import com.AISA.AISA.analysis.dto.ValuationDto.Summary.Verdicts;
import com.AISA.AISA.analysis.entity.StockAiSummary;
import com.AISA.AISA.analysis.entity.StockStaticAnalysis; // NEW
import com.AISA.AISA.analysis.repository.StockAiSummaryRepository;
import com.AISA.AISA.analysis.repository.StockStaticAnalysisRepository; // NEW
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialRatio;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialStatement;
import com.AISA.AISA.kisStock.Entity.stock.Stock; // Added for Competitor
import com.AISA.AISA.kisStock.Entity.stock.StockBalanceSheet;
import com.AISA.AISA.kisStock.kisService.CompetitorAnalysisService; // Added
import com.AISA.AISA.kisStock.kisService.DividendService;
import com.AISA.AISA.kisStock.kisService.KisStockService;
import com.AISA.AISA.kisStock.dto.StockPrice.StockPriceDto;
import com.AISA.AISA.kisStock.dto.InvestorTrend.InvestorTrendDto;
import com.AISA.AISA.kisStock.repository.StockFinancialRatioRepository;
import com.AISA.AISA.kisStock.repository.StockFinancialStatementRepository;
import com.AISA.AISA.kisStock.repository.StockBalanceSheetRepository;
import com.AISA.AISA.kisStock.repository.StockRepository;
import com.AISA.AISA.kisStock.kisService.KisMacroService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class ValuationService {

    private final StockRepository stockRepository;
    private final StockFinancialRatioRepository stockFinancialRatioRepository;
    private final StockFinancialStatementRepository stockFinancialStatementRepository;
    private final StockBalanceSheetRepository stockBalanceSheetRepository;
    private final KisStockService kisStockService;
    private final GeminiService geminiService;
    private final StockAiSummaryRepository stockAiSummaryRepository;
    private final KisMacroService kisMacroService;
    private final StockStaticAnalysisRepository stockStaticAnalysisRepository;
    private final DividendService dividendService;
    private final CompetitorAnalysisService competitorAnalysisService;

    @Autowired
    public ValuationService(StockRepository stockRepository,
            StockFinancialRatioRepository stockFinancialRatioRepository,
            StockBalanceSheetRepository stockBalanceSheetRepository,
            StockFinancialStatementRepository stockFinancialStatementRepository,
            KisStockService kisStockService,
            GeminiService geminiService,
            StockAiSummaryRepository stockAiSummaryRepository,
            KisMacroService kisMacroService,
            StockStaticAnalysisRepository stockStaticAnalysisRepository,
            DividendService dividendService,
            CompetitorAnalysisService competitorAnalysisService) {
        this.stockRepository = stockRepository;
        this.stockFinancialRatioRepository = stockFinancialRatioRepository;
        this.stockBalanceSheetRepository = stockBalanceSheetRepository;
        this.stockFinancialStatementRepository = stockFinancialStatementRepository;
        this.kisStockService = kisStockService;
        this.geminiService = geminiService;
        this.stockAiSummaryRepository = stockAiSummaryRepository;
        this.kisMacroService = kisMacroService;
        this.stockStaticAnalysisRepository = stockStaticAnalysisRepository;
        this.dividendService = dividendService;
        this.competitorAnalysisService = competitorAnalysisService;
    }

    // 기본 요구 수익률 (Fallback)

    @Transactional
    public Response getStandardizedValuationReport(String stockCode) {
        // 1. Calculate Standard Valuation (Neutral)
        Request standardRequest = Request.builder()
                .userPropensity(UserPropensity.NEUTRAL)
                .build();
        Response response = calculateValuation(stockCode, standardRequest);

        // 2. Fetch Cached AI Analysis (Valuation Only)
        // Static analysis is now served via separate API (/static-report)
        String aiAnalysis = getValuationAnalysis(stockCode, response);

        // 3. Fetch InvestorTrend for merging
        InvestorTrendDto trend = null;
        try {
            trend = kisStockService.getInvestorTrend(stockCode);
        } catch (Exception e) {
            log.warn("Failed to fetch investor trend for standardized report: {}", e.getMessage());
        }

        // 4. Build Final Response
        return buildResponseWithAi(response, aiAnalysis, trend);
    }

    public Response calculateValuation(String stockCode, Request request) {
        Stock stock = stockRepository.findByStockCode(stockCode)
                .orElseThrow(() -> new IllegalArgumentException("Stock not found: " + stockCode));

        // 1. Get Latest Financial Data
        StockFinancialRatio latestRatio = stockFinancialRatioRepository
                .findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");

        // Phase 1.5: Fetch Historical Data for Average ROE
        List<StockFinancialRatio> history = stockFinancialRatioRepository
                .findTop5ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");

        if (latestRatio == null) {
            // Try to fetch or return empty
            return Response.builder()
                    .stockCode(stockCode)
                    .stockName(stock.getStockName())
                    .currentPrice("0")
                    .build();
        }

        // 2. Prepare Data
        // 2. Prepare Data
        StockPriceDto priceDto = null;
        BigDecimal currentPrice = BigDecimal.ZERO;
        try {
            priceDto = kisStockService.getStockPrice(stockCode);
            if (priceDto != null && priceDto.getStockPrice() != null) {
                currentPrice = new BigDecimal(priceDto.getStockPrice().replace(",", "").trim());
            }
        } catch (Exception e) {
            log.warn("Failed to fetch real-time price using KisStockService for {}: {}", stockCode, e.getMessage());
        }

        // If inference failed, fallback to stored ratio (ONLY if real-time failed)
        if (currentPrice.compareTo(BigDecimal.ZERO) == 0) {
            if (latestRatio.getEps() != null && latestRatio.getPer() != null) {
                currentPrice = latestRatio.getEps().multiply(latestRatio.getPer());
                log.warn("Real-time price fetch failed for {}. Fallback to EPS * PER calculation: {}", stockCode,
                        currentPrice);
            }
        }

        // Determine COE based on Request
        ValuationDto.DiscountRateInfo discountRateInfo = determineCOE(request);
        BigDecimal coe = new BigDecimal(discountRateInfo.getValue().replace("%", "")).divide(new BigDecimal(100));

        // 3. Calculate Models
        ValuationResult srim = calculateSRIM(latestRatio, history, coe, currentPrice);
        ValuationResult perModel = calculatePERModel(latestRatio, history, currentPrice);
        ValuationResult pbrModel = calculatePBRModel(latestRatio, history, coe, currentPrice);

        // 4. Calculate Band (Weighted)
        ValuationBand band = calculateBand(currentPrice, srim, perModel, pbrModel);

        // 5. Generate Summary (Interpretation)
        Summary summary = generateSummary(srim, perModel, pbrModel);

        // Calculate Market Cap
        String marketCapStr = "N/A";
        if (priceDto != null) {
            BigDecimal marketCap = BigDecimal.ZERO;
            // 1. Try to use KIS Provided Market Cap (hts_avls is usually in 100 Million Won
            // units)
            if (priceDto.getMarketCap() != null && !priceDto.getMarketCap().isEmpty()) {
                BigDecimal rawMarketCap = new BigDecimal(priceDto.getMarketCap().replace(",", "").trim());
                // Convert 100 Million Won -> Won
                marketCap = rawMarketCap.multiply(new BigDecimal("100000000"));
            }
            // 2. Fallback to calculation (Price * Shares)
            else if (priceDto.getListedSharesCount() != null) {
                BigDecimal shares = new BigDecimal(priceDto.getListedSharesCount().replace(",", "").trim());
                marketCap = currentPrice.multiply(shares);
            }
            marketCapStr = formatLargeNumber(marketCap);
        }

        return Response.builder().stockCode(stockCode).stockName(stock.getStockName())
                .currentPrice(currentPrice.setScale(0, RoundingMode.HALF_UP).toString())
                .marketCap(marketCapStr) // Set Market Cap
                .targetReturn(discountRateInfo.getValue()) // Display
                                                           // String
                .discountRate(discountRateInfo).srim(srim).per(perModel).pbr(pbrModel).band(band).summary(summary)
                .build();
    }

    private String formatLargeNumber(BigDecimal value) {
        if (value == null)
            return "N/A";

        BigDecimal absValue = value.abs();
        BigDecimal trillion = new BigDecimal("1000000000000");
        BigDecimal billion = new BigDecimal("100000000");

        if (absValue.compareTo(trillion) >= 0) {
            return value.divide(trillion, 1, RoundingMode.HALF_UP).toPlainString() + "조원";
        } else if (absValue.compareTo(billion) >= 0) {
            return value.divide(billion, 0, RoundingMode.HALF_UP).toPlainString() + "억원";
        }
        return value.toPlainString() + "원";
    }

    private ValuationDto.DiscountRateInfo determineCOE(Request request) {
        // 1. Advanced Mode (Custom Input)
        if (request != null && request.getExpectedTotalReturn() != null) {
            String value = BigDecimal.valueOf(request.getExpectedTotalReturn()).multiply(new BigDecimal(100))
                    .setScale(1, RoundingMode.HALF_UP) + "%";
            return ValuationDto.DiscountRateInfo.builder()
                    .profile("CUSTOM")
                    .value(value)
                    .basis("User defined custom override")
                    .source("USER_OVERRIDE")
                    .note("User-defined hurdle rate overrides profile-based default")
                    .build();
        }

        // 2. Propensity Mode
        if (request != null && request.getUserPropensity() != null) {
            switch (request.getUserPropensity()) {
                case CONSERVATIVE:
                    return ValuationDto.DiscountRateInfo.builder()
                            .profile("CONSERVATIVE")
                            .value("10.0%")
                            .basis("Safety First (Market Risk + Premium)")
                            .source("PROFILE_DEFAULT")
                            .build();
                case AGGRESSIVE:
                    return ValuationDto.DiscountRateInfo.builder()
                            .profile("AGGRESSIVE")
                            .value("6.0%")
                            .basis("Growth Focused (Lower Hurdle Rate)")
                            .source("PROFILE_DEFAULT")
                            .build();
                case NEUTRAL:
                default:
                    return ValuationDto.DiscountRateInfo.builder()
                            .profile("NEUTRAL")
                            .value("8.0%")
                            .basis("Market Reference (Approx. BBB- Bond Yield)")
                            .source("PROFILE_DEFAULT")
                            .build();
            }
        }

        // 3. Default (If nothing provided) -> Neutral
        return ValuationDto.DiscountRateInfo.builder()
                .profile("NEUTRAL (Default)")
                .value("8.0%")
                .basis("Market Reference (Approx. BBB- Bond Yield)")
                .source("PROFILE_DEFAULT")
                .note("Default setting used as no propensity was provided.")
                .build();
    }

    // S-RIM: BPS + (Review ROE - COE) * BPS / COE
    private ValuationResult calculateSRIM(StockFinancialRatio latest, List<StockFinancialRatio> history,
            BigDecimal coe,
            BigDecimal currentPrice) {

        // Calculate Weighted Average ROE
        BigDecimal roe = calculateWeightedAverageROE(history);
        String roeType = (history.size() > 1) ? history.size() + "Y_AVG" : "LATEST";

        // Safety Net: If EPS is negative (Loss-making), Cap ROE at COE to prevent
        // over-optimism
        if (latest.getEps() != null && latest.getEps().compareTo(BigDecimal.ZERO) <= 0) {
            BigDecimal coePercent = coe.multiply(new BigDecimal("100"));
            if (roe.compareTo(coePercent) > 0) {
                roe = coePercent; // Cap at COE
                roeType += " (Capped at COE due to Loss)";
            }
        }

        BigDecimal bps = latest.getBps();

        if (bps == null || roe == null || bps.compareTo(BigDecimal.ZERO) <= 0)
            return emptyResult("데이터 부족 또는 자본 잠식", "N/A");

        // S-RIM Formula: Value = BPS + (BPS * (ROE - COE)) / (COE - g)
        BigDecimal targetPrice = calculateSrimPrice(bps, roe, coe, BigDecimal.ZERO); // Default g=0

        // Strict S-RIM Logic: Disable if ROE < COE (Excess Return is negative)
        // Even if Price > 0 (due to BPS), growth scenarios will destroy value, leading
        // to confusing negative scenarios.
        BigDecimal roeDecimal = roe.divide(new BigDecimal(100), 4, RoundingMode.HALF_UP);
        boolean isSrimNegative = targetPrice.compareTo(BigDecimal.ZERO) <= 0 || roeDecimal.compareTo(coe) < 0;

        if (isSrimNegative) {
            return ValuationResult.builder()
                    .price("0")
                    .gapRate("0")
                    .verdict("N/A")
                    .description("S-RIM (ROE < COE)")
                    .available(false)
                    .reason("ROE below cost of equity (Value Destruction)")
                    .roeType(roeType)
                    .build();
        }

        // Scenario Analysis (Growth Rates)
        Map<String, ValuationResult> scenarios = new HashMap<>();

        // Conservative: g = 0%
        scenarios.put("LOW_GROWTH", buildScenarioResult(targetPrice, currentPrice, "S-RIM (g=0%)"));

        // Base: g = 2% (Constraint: g < COE)
        BigDecimal gBase = new BigDecimal("0.02");
        if (coe.subtract(gBase).compareTo(new BigDecimal("0.01")) > 0) {
            BigDecimal priceBase = calculateSrimPrice(bps, roe, coe, gBase);
            scenarios.put("BASE_GROWTH", buildScenarioResult(priceBase, currentPrice, "S-RIM (g=2%)"));
        } else {
            // Fallback if COE is too low
            scenarios.put("BASE_GROWTH", buildScenarioResult(targetPrice, currentPrice, "S-RIM (g=0% due to low COE)"));
        }

        // Optimistic: g = 4%
        BigDecimal gOpt = new BigDecimal("0.04");
        if (coe.subtract(gOpt).compareTo(new BigDecimal("0.01")) > 0) {
            BigDecimal priceOpt = calculateSrimPrice(bps, roe, coe, gOpt);
            scenarios.put("HIGH_GROWTH", buildScenarioResult(priceOpt, currentPrice, "S-RIM (g=4%)"));
        } else {
            scenarios.put("HIGH_GROWTH", scenarios.get("BASE_GROWTH"));
        }

        ValuationResult result = buildResult(targetPrice, currentPrice, "S-RIM (초과이익모델, g=0%)", true, "Normal",
                roeType);
        result.setScenarios(scenarios);
        return result;
    }

    private BigDecimal calculateSrimPrice(BigDecimal bps, BigDecimal roe, BigDecimal coe, BigDecimal g) {
        // ROE is in percentage (e.g. 15.0), convert to decimal
        BigDecimal roeDecimal = roe.divide(new BigDecimal(100), 4, RoundingMode.HALF_UP);

        // Excess Return = BPS * (ROE - COE)
        BigDecimal excessReturn = bps.multiply(roeDecimal.subtract(coe));

        // Terminal Value of Excess Return = Excess Return / (COE - g)
        BigDecimal denominator = coe.subtract(g);
        if (denominator.compareTo(BigDecimal.ZERO) <= 0)
            return bps; // Invalid constraint

        BigDecimal value = bps.add(excessReturn.divide(denominator, 0, RoundingMode.HALF_UP));
        return value;
    }

    private BigDecimal calculateWeightedAverageROE(List<StockFinancialRatio> history) {
        if (history == null || history.isEmpty())
            return BigDecimal.ZERO;

        BigDecimal totalWeightedRoe = BigDecimal.ZERO;
        BigDecimal totalWeights = BigDecimal.ZERO;

        // Weights: 5, 4, 3, 2, 1 (Recent -> Old)
        int maxWeight = history.size();
        for (int i = 0; i < history.size(); i++) {
            BigDecimal roe = history.get(i).getRoe();
            if (roe != null) {
                BigDecimal weight = new BigDecimal(maxWeight - i);
                totalWeightedRoe = totalWeightedRoe.add(roe.multiply(weight));
                totalWeights = totalWeights.add(weight);
            }
        }

        if (totalWeights.compareTo(BigDecimal.ZERO) == 0)
            return BigDecimal.ZERO;
        return totalWeightedRoe.divide(totalWeights, 2, RoundingMode.HALF_UP);
    }

    // PER Model: EPS * Target PER (Industry Avg or Historical Avg)
    // Phase 1: Use Fixed Target PER explicitly or derived from ROE/COE?
    // Let's use Historical Avg PER (e.g., 10 or Sector Avg).
    // Since we don't have Sector Avg, let's use a heuristic: Target PER = 1 / COE
    // (No-growth perp).
    // Or better, let's use a fixed reasonable multiple for now, or just omitted if
    // data not ready.
    // Let's use "10" as a placeholder benchmark or the stock's current PER as
    // "Fair" if we assume market is efficient? No useful.
    // Let's use 10.0 (generic) or calculated PEG?
    // Let's try to find Historical PER from DB?
    // Simplify: Target PER = 10 (Market Avg)
    // PER Model: EPS * Target PER (Historical Avg)
    private ValuationResult calculatePERModel(StockFinancialRatio ratio, List<StockFinancialRatio> history,
            BigDecimal currentPrice) {
        BigDecimal eps = ratio.getEps();
        if (eps == null || eps.compareTo(BigDecimal.ZERO) <= 0) {
            // Try Forward EPS for Turnaround Companies
            BigDecimal forwardEps = estimateForwardEps(ratio.getStockCode(), ratio);

            if (forwardEps != null && forwardEps.compareTo(BigDecimal.ZERO) > 0) {
                // Use Forward EPS
                eps = forwardEps;
                // Proceed with Valuation using Forward EPS
                Map<String, ValuationResult> scenarios = new HashMap<>();

                // Use Historical Average PER for Target Price
                BigDecimal avgPer = calculateStats(history, "PER").avg;
                if (avgPer.compareTo(BigDecimal.ZERO) <= 0)
                    avgPer = BigDecimal.valueOf(10); // Fallback if historical PER is also negative

                BigDecimal targetPrice = eps.multiply(avgPer);

                scenarios.put("TURNAROUND", buildScenarioResult(targetPrice, currentPrice,
                        "Turnaround based on Forward EPS " + eps.setScale(0, RoundingMode.HALF_UP)));

                return ValuationResult.builder()
                        .price(targetPrice.setScale(0, RoundingMode.HALF_UP).toString())
                        .gapRate(calculateGapRate(targetPrice, currentPrice).setScale(2, RoundingMode.HALF_UP)
                                .toString())
                        .verdict(determineVerdict(targetPrice, currentPrice))
                        .description("매출/마진 정상화 가정 선행 PER (턴어라운드 시나리오)")
                        .scenarios(scenarios)
                        .available(true)
                        .reason("Turnaround Expectation")
                        .build();
            } else {
                return ValuationResult.builder()
                        .price("0")
                        .gapRate("0")
                        .verdict("N/A")
                        .description("N/A")
                        .available(false)
                        .reason("Forward EPS ≤ 0 (turnaround not yet realized)")
                        .scenarios(null)
                        .build();
            }

        }

        // 1. Calculate Historical PER Stats
        Stats perStats = calculateStats(history, "PER");

        // Default to 10 if no history
        BigDecimal basePer = (perStats.avg.compareTo(BigDecimal.ZERO) > 0) ? perStats.avg : new BigDecimal("10");

        // 2. Scenario Analysis
        Map<String, ValuationResult> scenarios = new HashMap<>();

        // Conservative: Min PER (or 0.8 * Avg)
        BigDecimal consPer = (perStats.min.compareTo(BigDecimal.ZERO) > 0) ? perStats.min
                : basePer.multiply(new BigDecimal("0.8"));
        scenarios.put("BEAR",
                buildScenarioResult(eps.multiply(consPer), currentPrice, "Historical Min PER " + consPer));

        // Base: Avg PER
        scenarios.put("BASE",
                buildScenarioResult(eps.multiply(basePer), currentPrice, "Historical Avg PER " + basePer));

        // Optimistic: Max PER (or 1.2 * Avg)
        BigDecimal optPer = (perStats.max.compareTo(BigDecimal.ZERO) > 0) ? perStats.max
                : basePer.multiply(new BigDecimal("1.2"));
        scenarios.put("BULL",
                buildScenarioResult(eps.multiply(optPer), currentPrice, "Historical Max PER " + optPer));

        BigDecimal targetPrice = eps.multiply(basePer).setScale(0, RoundingMode.HALF_UP);
        ValuationResult result = buildResult(targetPrice, currentPrice,
                "PER " + basePer + "배 (과거 " + perStats.count + "년 평균) 적용", true, "Normal", null);
        result.setScenarios(scenarios);
        return result;
    }

    private ValuationResult buildScenarioResult(BigDecimal targetPrice, BigDecimal currentPrice, String desc) {
        return buildResult(targetPrice.setScale(0, RoundingMode.HALF_UP), currentPrice, desc, true, "Normal", null);
    }

    private static class Stats {
        BigDecimal min = BigDecimal.ZERO;
        BigDecimal max = BigDecimal.ZERO;
        BigDecimal avg = BigDecimal.ZERO;
        int count = 0;
    }

    private Stats calculateStats(List<StockFinancialRatio> history, String metric) {
        Stats stats = new Stats();
        if (history == null || history.isEmpty())
            return stats;

        List<BigDecimal> values = new java.util.ArrayList<>();
        for (StockFinancialRatio ratio : history) {
            BigDecimal val = "PER".equals(metric) ? ratio.getPer() : ratio.getPbr();
            if (val != null && val.compareTo(BigDecimal.ZERO) > 0) {
                // Outlier Filter (Statistically Insignificant)
                // Filter out likely data errors or extreme outliers (Below historical 5th
                // percentile approx)
                if ("PER".equals(metric) && val.compareTo(new BigDecimal("3.0")) < 0)
                    continue; // PER < 3.0 excluded
                if ("PBR".equals(metric) && val.compareTo(new BigDecimal("0.3")) < 0)
                    continue; // PBR < 0.3 excluded

                values.add(val);
            }
        }

        if (values.isEmpty())
            return stats;

        stats.min = values.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        stats.max = values.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);

        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values)
            sum = sum.add(v);
        stats.avg = sum.divide(new BigDecimal(values.size()), 2, RoundingMode.HALF_UP);
        stats.count = values.size();

        return stats;
    }

    // PBR Model: BPS * Target PBR (Historical Avg)
    private ValuationResult calculatePBRModel(StockFinancialRatio ratio, List<StockFinancialRatio> history,
            BigDecimal coe,
            BigDecimal currentPrice) {
        BigDecimal bps = ratio.getBps();
        if (bps == null || bps.compareTo(BigDecimal.ZERO) <= 0) {
            return ValuationResult.builder()
                    .price("0")
                    .gapRate("0")
                    .verdict("N/A")
                    .description("자본 잠식 상태")
                    .available(false)
                    .reason("Negative Equity")
                    .build();
        }

        // 1. Calculate Historical PBR Stats
        Stats pbrStats = calculateStats(history, "PBR");

        // Default to 1.0 (Liquidation Value) or 0.6 if very low? Let's use 1.0 default
        BigDecimal basePbr = (pbrStats.avg.compareTo(BigDecimal.ZERO) > 0) ? pbrStats.avg : new BigDecimal("1.0");

        // 2. Scenario Analysis
        Map<String, ValuationResult> scenarios = new HashMap<>();

        // Conservative: Min PBR
        BigDecimal consPbr = (pbrStats.min.compareTo(BigDecimal.ZERO) > 0) ? pbrStats.min
                : basePbr.multiply(new BigDecimal("0.8"));
        scenarios.put("BEAR",
                buildScenarioResult(bps.multiply(consPbr), currentPrice, "Historical Min PBR " + consPbr));

        // Base: Avg PBR
        scenarios.put("BASE",
                buildScenarioResult(bps.multiply(basePbr), currentPrice, "Historical Avg PBR " + basePbr));

        // Optimistic: Max PBR
        BigDecimal optPbr = (pbrStats.max.compareTo(BigDecimal.ZERO) > 0) ? pbrStats.max
                : basePbr.multiply(new BigDecimal("1.2"));
        scenarios.put("BULL",
                buildScenarioResult(bps.multiply(optPbr), currentPrice, "Historical Max PBR " + optPbr));

        BigDecimal targetPrice = bps.multiply(basePbr).setScale(0, RoundingMode.HALF_UP);
        ValuationResult result = buildResult(targetPrice, currentPrice, "PBR " + basePbr + "배 (청산가치/과거평균) 적용", true,
                "Normal", null);
        result.setScenarios(scenarios);
        return result;
    }

    private ValuationResult buildResult(BigDecimal targetPrice, BigDecimal currentPrice, String desc,
            boolean available, String reason, String roeType) {
        if (!available) {
            return ValuationResult.builder()
                    .price("0")
                    .gapRate("0")
                    .verdict("N/A")
                    .description(desc)
                    .available(false)
                    .reason(reason)
                    .build();
        }

        if (currentPrice.compareTo(BigDecimal.ZERO) == 0)
            return emptyResult(desc, "Current Price Zero");

        BigDecimal gap = targetPrice.subtract(currentPrice).divide(currentPrice, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal(100));

        String verdict = "FAIR";
        if (gap.compareTo(new BigDecimal("10")) > 0)
            verdict = "UNDERVALUED"; // 10% Upside
        else if (gap.compareTo(new BigDecimal("-10")) < 0)
            verdict = "OVERVALUED"; // 10% Downside

        return ValuationResult.builder()
                .price(targetPrice.toString())
                .gapRate(gap.setScale(2, RoundingMode.HALF_UP).toString())
                .verdict(verdict)
                .description(desc)
                .available(true)
                .roeType(roeType)
                .build();
    }

    private ValuationResult emptyResult(String desc, String reason) {
        return ValuationResult.builder().price("0").gapRate("0").verdict("N/A").description(desc)
                .available(false).reason(reason).build();
    }

    private ValuationBand calculateBand(BigDecimal currentPrice,
            ValuationResult srim,
            ValuationResult per,
            ValuationResult pbr) {

        java.util.Map<String, Double> baseWeights = new HashMap<>();
        // Default weights (will be normalized later)
        baseWeights.put("srim", 0.5);
        baseWeights.put("per", 0.3);
        baseWeights.put("pbr", 0.2);

        // Collect valid prices and corresponding weights
        List<BigDecimal> prices = new java.util.ArrayList<>();
        BigDecimal weightedSum = BigDecimal.ZERO;
        double validWeightSum = 0.0;

        // Final Normalized Weights map for response
        java.util.Map<String, Double> finalWeights = new HashMap<>();
        finalWeights.put("srim", 0.0);
        finalWeights.put("per", 0.0);
        finalWeights.put("pbr", 0.0);

        // Helper to check validity
        if (srim.isAvailable() && isValidPrice(srim.getPrice())
                && new BigDecimal(srim.getPrice()).compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal p = new BigDecimal(srim.getPrice());
            prices.add(p);
            validWeightSum += baseWeights.get("srim");
        }
        if (per.isAvailable() && isValidPrice(per.getPrice())
                && new BigDecimal(per.getPrice()).compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal p = new BigDecimal(per.getPrice());
            prices.add(p);
            validWeightSum += baseWeights.get("per");
        }
        if (pbr.isAvailable() && isValidPrice(pbr.getPrice())
                && new BigDecimal(pbr.getPrice()).compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal p = new BigDecimal(pbr.getPrice());
            prices.add(p);
            validWeightSum += baseWeights.get("pbr");
        }

        if (prices.isEmpty() || validWeightSum == 0) {
            return ValuationBand.builder()
                    .status("N/A")
                    .weights(finalWeights)
                    .build();
        }

        // Re-calculate Weighted Sum with Normalization
        // Normalization Factor = 1 / validWeightSum
        // e.g. Valid: PBR(0.2). Sum=0.2. Factor=5. New Weight = 0.2 * 5 = 1.0.
        // e.g. Valid: SRIM(0.5)+PBR(0.2). Sum=0.7. Factor=1.428. New SRIM=0.714, New
        // PBR=0.285.

        // We need to re-iterate or store pairs to calculate Weighted Sum correctly.
        // Let's do it simpler:

        weightedSum = BigDecimal.ZERO;

        if (srim.isAvailable() && isValidPrice(srim.getPrice())
                && new BigDecimal(srim.getPrice()).compareTo(BigDecimal.ZERO) > 0) {
            double normWeight = baseWeights.get("srim") / validWeightSum;
            finalWeights.put("srim", (double) Math.round(normWeight * 1000d) / 1000d);
            weightedSum = weightedSum.add(new BigDecimal(srim.getPrice()).multiply(BigDecimal.valueOf(normWeight)));
        }
        if (per.isAvailable() && isValidPrice(per.getPrice())
                && new BigDecimal(per.getPrice()).compareTo(BigDecimal.ZERO) > 0) {
            double normWeight = baseWeights.get("per") / validWeightSum;
            finalWeights.put("per", (double) Math.round(normWeight * 1000d) / 1000d);
            weightedSum = weightedSum.add(new BigDecimal(per.getPrice()).multiply(BigDecimal.valueOf(normWeight)));
        }
        if (pbr.isAvailable() && isValidPrice(pbr.getPrice())
                && new BigDecimal(pbr.getPrice()).compareTo(BigDecimal.ZERO) > 0) {
            double normWeight = baseWeights.get("pbr") / validWeightSum;
            finalWeights.put("pbr", (double) Math.round(normWeight * 1000d) / 1000d);
            weightedSum = weightedSum.add(new BigDecimal(pbr.getPrice()).multiply(BigDecimal.valueOf(normWeight)));
        }

        // Weighted Average
        // Since weights are normalized to sum to 1, we just sum (price * weight)
        BigDecimal avg = weightedSum; // Already divided effectively by normalization

        BigDecimal min = prices.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal max = prices.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);

        BigDecimal gap = BigDecimal.ZERO;
        if (currentPrice.compareTo(BigDecimal.ZERO) > 0) {
            gap = avg.subtract(currentPrice).divide(currentPrice, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal(100));
        }

        // Position % & Status
        String position = "0.0";
        String status = "WITHIN_BAND";

        // Single Model Case
        if (prices.size() == 1) {
            status = "SINGLE_MODEL";
            // Optional: Append model name, e.g. "SINGLE_MODEL (PBR)"
            if (finalWeights.get("pbr") > 0.9)
                status += " (PBR)";
            else if (finalWeights.get("per") > 0.9)
                status += " (PER)";
            else if (finalWeights.get("srim") > 0.9)
                status += " (S-RIM)";

            position = null; // As requested, null for single model
        } else if (max.compareTo(min) > 0) {
            BigDecimal range = max.subtract(min);
            // Calculate position freely (can be negative or > 100)
            BigDecimal posVal = currentPrice.subtract(min).divide(range, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal(100));
            position = posVal.setScale(1, RoundingMode.HALF_UP).toString();

            if (posVal.compareTo(BigDecimal.ZERO) < 0) {
                status = "BELOW_BAND";
            } else if (posVal.compareTo(new BigDecimal(100)) > 0) {
                status = "ABOVE_BAND";
            }
        } else {
            // Min == Max but count > 1 (Rare, exactly same price models)
            position = "50.0";
        }

        return ValuationBand.builder()
                .minPrice(min.toString())
                .maxPrice(max.toString())
                .currentPrice(currentPrice.toString())
                .gapRate(gap.setScale(2, RoundingMode.HALF_UP).toString())
                .position(position)
                .status(status)
                .weights(finalWeights)
                .build();
    }

    private Summary generateSummary(ValuationResult srim,
            ValuationResult per,
            ValuationResult pbr) {
        int undervaluedCount = 0;
        int overvaluedCount = 0;
        int fairCount = 0;

        if (srim.isAvailable())
            countVerdict(srim.getVerdict(), 5, new int[] { undervaluedCount, overvaluedCount, fairCount }); // Weight 5
        if (per.isAvailable())
            countVerdict(per.getVerdict(), 3, new int[] { undervaluedCount, overvaluedCount, fairCount }); // Weight 3
        if (pbr.isAvailable())
            countVerdict(pbr.getVerdict(), 2, new int[] { undervaluedCount, overvaluedCount, fairCount }); // Weight 2

        // Count just logic for now (Need mutable container or logic refactor)
        // Let's use simple logic:
        int score = 0; // +1 Undervalued, -1 Overvalued, 0 Fair. With weights.
        if (srim.isAvailable())
            score += getScore(srim.getVerdict()) * 5;
        if (per.isAvailable())
            score += getScore(per.getVerdict()) * 3;
        if (pbr.isAvailable())
            score += getScore(pbr.getVerdict()) * 2;

        String overallVerdict = "HOLD"; // MIXED
        String confidence = "MEDIUM";
        String insight = "모델 간 의견이 엇갈립니다. 투자 시 주의가 필요합니다.";

        if (score >= 3) {
            overallVerdict = "BUY";
            insight = "S-RIM 포함 주요 모델이 저평가를 시사합니다.";
            confidence = (score >= 7) ? "HIGH" : "MEDIUM";
        } else if (score <= -3) {
            overallVerdict = "SELL"; // or OVERVALUED
            insight = "대다수 모델이 고평가 상태임을 나타냅니다.";
            confidence = (score <= -7) ? "HIGH" : "MEDIUM";
        } else {
            overallVerdict = "HOLD";
            insight = "현재 주가는 적정 가치 범위 내에 있습니다.";
        }

        // 4. All Fail Case
        if (!srim.isAvailable() && !per.isAvailable() && !pbr.isAvailable()) {
            overallVerdict = "N/A";
            insight = "데이터 부족 또는 적자/자본잠식 심화로 인해 가치평가가 불가능합니다.";
            confidence = "LOW";
        }

        // Special insight for S-RIM Disable (Negative)
        if (!srim.isAvailable() && "ROE below cost of equity (no excess return)".equals(srim.getReason())) {
            insight = "해당 기업은 최근 수익성이 자본비용을 하회하여, S-RIM 모델에서는 장기적 초과이익 창출이 어렵다고 판단되었습니다.";
        }

        // Special insight for PBR Only Case
        if (!srim.isAvailable() && !per.isAvailable() && pbr.isAvailable()) {
            overallVerdict = pbr.getVerdict(); // Follow PBR
            insight = "현재 기업은 수익성 기반 가치평가(S-RIM, PER)가 모두 적용 불가한 상태이며, 자산가치(PBR) 기준으로만 판단 가능한 국면입니다. 실적 턴어라운드가 확인되기 전까지는 보수적 접근이 필요합니다.";
        }

        // Special insight for Negative EPS
        if (!per.isAvailable() && "Negative EPS (PER not meaningful)".equals(per.getReason())) {
            insight = "본 평가는 적자 상황을 고려한 보수적 추정치이며, 실적 정상화 이전에는 변동성이 매우 클 수 있습니다.";
        }

        String logic = "S-RIM은 최근 5년 가중 평균 ROE를 사용하였으며, PER/PBR 모델은 과거 5년 역사적 밴드(Min/Avg/Max)를 기준으로 시나리오를 분석했습니다. (낙관 시나리오는 높은 성장률과 낮은 요구 수익률 가정이 결합된 결과입니다.)";

        if (!per.isAvailable() && "Negative EPS (PER not meaningful)".equals(per.getReason())) {
            logic += " 적자 상태로 인해 자본 가치(PBR)와 보수적 S-RIM 모델의 가중치를 높여 평가했습니다.";
        }

        String decisionRule = "At least 2 core models indicate undervaluation (Score >= 3)";
        if ("SELL".equals(overallVerdict)) {
            decisionRule = "Majority of models indicate overvaluation (Score <= -3)";
        } else if ("HOLD".equals(overallVerdict)) {
            decisionRule = "Mixed signals or Fair value range (-3 < Score < 3)";
        }

        // PBR Only Case Special Rule Text
        if (!srim.isAvailable() && !per.isAvailable() && pbr.isAvailable()) {
            decisionRule = "Solely based on PBR (Asset Value Only) due to negative profitability";
        }

        return Summary.builder()
                .overallVerdict(overallVerdict)
                .confidence(confidence)
                .keyInsight(insight)
                .valuationLogic(logic)
                .decisionRule(decisionRule)
                .build();
    }

    private int getScore(String verdict) {
        if ("UNDERVALUED".equals(verdict))
            return 1;
        if ("OVERVALUED".equals(verdict))
            return -1;
        return 0;
    }

    private void countVerdict(String verdict, int weight, int[] counts) {
        // Placeholder
    }

    private boolean isValidPrice(String price) {
        return price != null && !price.equals("0");
    }

    // Forward EPS Calculation Logic
    private BigDecimal estimateForwardEps(String stockCode, StockFinancialRatio latestRatio) {
        // 1. Get Financial Statements (Revenue & Operating Profit)
        List<StockFinancialStatement> statements = stockFinancialStatementRepository
                .findTop5ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");

        if (statements.size() < 3)
            return null; // Need at least 3 years for CAGR

        StockFinancialStatement latest = statements.get(0);
        StockFinancialStatement past3y = statements.get(statements.size() - 1); // 3-4 years ago

        // 2. Calculate Revenue CAGR (3 Year)
        BigDecimal latestRevenue = latest.getSaleAccount();
        BigDecimal pastRevenue = past3y.getSaleAccount();

        if (latestRevenue == null || pastRevenue == null || pastRevenue.compareTo(BigDecimal.ZERO) <= 0)
            return null;

        // CAGR = (End/Start)^(1/n) - 1
        // Simplified: Growth Rate over 3 years
        double revenueGrowthTotal = latestRevenue.doubleValue() / pastRevenue.doubleValue();
        double cagr = Math.pow(revenueGrowthTotal, 1.0 / (statements.size() - 1)) - 1.0;

        // Guard Rail 1: Reverse Growth (Revenue Declining) -> No Turnaround
        if (cagr <= 0)
            return null;

        // Cap Growth at 10% (Conservative) or CAGR
        double appliedGrowth = Math.min(cagr, 0.10);

        // 3. Estimate Target Margin
        double targetMargin = 0.0;
        double maxMargin = 0.0;
        double recentProfitableMarginAvg = 0.0;
        int profitableCount = 0;

        for (StockFinancialStatement stmt : statements) {
            if (stmt.getSaleAccount() == null || stmt.getOperatingProfit() == null
                    || stmt.getSaleAccount().compareTo(BigDecimal.ZERO) == 0)
                continue;

            double margin = stmt.getOperatingProfit().doubleValue() / stmt.getSaleAccount().doubleValue();
            if (margin > maxMargin)
                maxMargin = margin;

            if (margin > 0) {
                recentProfitableMarginAvg += margin;
                profitableCount++;
            }
        }

        // Guard Rail 2: No profit in last 5 years -> Structural Loss
        if (profitableCount == 0)
            return null;

        recentProfitableMarginAvg /= profitableCount;

        // Target Margin = min(Max, Recent Profitable)
        targetMargin = Math.min(maxMargin, recentProfitableMarginAvg);

        // Guard Rail 3: Target Margin too low (Structural low margin)
        if (targetMargin < 0.01)
            return null; // < 1% margin is too risky for valuation

        // 4. Calculate Forward Operating Profit
        double forwardRevenue = latestRevenue.doubleValue() * (1 + appliedGrowth);
        double forwardOp = forwardRevenue * targetMargin;

        // 5. Get Shares Outstanding from KIS API
        double shares = 0.0;
        try {
            StockPriceDto priceDto = kisStockService.getStockPrice(stockCode);
            if (priceDto != null && priceDto.getListedSharesCount() != null) {
                shares = Double.parseDouble(priceDto.getListedSharesCount());
            }
        } catch (Exception e) {
            log.warn("Failed to fetch listed shares for {}: {}", stockCode, e.getMessage());
        }

        // Fallback: TotalCapital / BPS
        if (shares <= 0 && latestRatio.getBps() != null && latestRatio.getBps().compareTo(BigDecimal.ZERO) > 0) {
            StockBalanceSheet balanceSheet = stockBalanceSheetRepository
                    .findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
            if (balanceSheet != null && balanceSheet.getTotalCapital() != null) {
                shares = balanceSheet.getTotalCapital().doubleValue() / latestRatio.getBps().doubleValue();
            }
        }

        if (shares <= 0)
            return null; // Cannot calculate EPS

        // 6. Calculate Forward EPS (Tax Rate 22% assumption)
        double taxRate = 0.22;
        double forwardNetIncome = forwardOp * (1 - taxRate);
        double forwardEps = forwardNetIncome / shares;

        return BigDecimal.valueOf(forwardEps);
    }

    private BigDecimal calculateGapRate(BigDecimal targetPrice, BigDecimal currentPrice) {
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return targetPrice.subtract(currentPrice)
                .divide(currentPrice, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal(100));
    }

    private String determineVerdict(BigDecimal targetPrice, BigDecimal currentPrice) {
        BigDecimal gap = calculateGapRate(targetPrice, currentPrice);
        if (gap.compareTo(new BigDecimal("10")) > 0)
            return "UNDERVALUED";
        if (gap.compareTo(new BigDecimal("-10")) < 0)
            return "OVERVALUED";
        return "FAIR";
    }

    public Response calculateValuationWithAi(String stockCode, Request request) {
        // 1. Calculate Base Valuation
        Response response = calculateValuation(stockCode, request);

        // Fetch latest ratio for AI context
        StockFinancialRatio latestRatio = stockFinancialRatioRepository
                .findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");

        // Fetch latest Balance Sheet
        StockBalanceSheet latestBalanceSheet = stockBalanceSheetRepository
                .findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");

        // Fetch Recent 5 Years (Annual) for Long-term Trend
        List<StockFinancialStatement> recentAnnuals = stockFinancialStatementRepository
                .findTop5ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");

        // Fetch Recent 5 Quarters for Trend Analysis
        List<StockFinancialStatement> recentQuarters = stockFinancialStatementRepository
                .findTop5ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "1");

        // 2. Generate or Fetch AI Analysis (Caching + Standardization)
        // 2. Personal AI Analysis (No Caching, uses User's specific valuation context)
        String aiAnalysis = generateAiDiagnosis(response, latestRatio, latestBalanceSheet, recentAnnuals,
                recentQuarters);

        // Fetch InvestorTrend for merging
        InvestorTrendDto trend = null;
        try {
            trend = kisStockService.getInvestorTrend(stockCode);
        } catch (Exception e) {
            log.warn("Failed to fetch investor trend for calculation: {}", e.getMessage());
        }

        // 3. Update Summary with AI Analysis
        return buildResponseWithAi(response, aiAnalysis, trend);
    }

    private Response buildResponseWithAi(Response response, String aiAnalysis, InvestorTrendDto originalTrend) {
        if (response.getSummary() != null) {
            Summary oldSummary = response.getSummary();

            // Parse AI Response (Container containing both AiVerdict and BeginnerVerdict)
            AiResponseJson aiResponse = parseAiResponse(aiAnalysis);

            // Merge Logic: Enhance AI's partial investorTrend with original rich data
            if (originalTrend != null && aiResponse.getAnalysisDetails() != null
                    && aiResponse.getAnalysisDetails().getInvestorTrend() != null) {
                InvestorTrendDto aiTrend = aiResponse.getAnalysisDetails().getInvestorTrend();
                // Overwrite ONLY AI's interpretive fields onto the rich original data
                originalTrend.setSupplyScore(aiTrend.getSupplyScore());
                originalTrend.setTrendStatus(aiTrend.getTrendStatus());
                originalTrend.setAdvice(aiTrend.getAdvice()); // Map the new AI advice field
                // Do NOT overwrite quantitative fields like Overheat/AvgPrice with AI results

                // Use the enriched originalTrend for the final response
                aiResponse.getAnalysisDetails().setInvestorTrend(originalTrend);
            } else if (originalTrend != null && aiResponse.getAnalysisDetails() != null) {
                // Fallback: If AI failed to provide investorTrend object, still show original
                // data
                aiResponse.getAnalysisDetails().setInvestorTrend(originalTrend);
            }

            // Create Model Verdict
            ModelVerdict modelVerdictObj = createModelVerdict(oldSummary);

            Verdicts verdicts = Verdicts.builder()
                    .modelVerdict(modelVerdictObj)
                    .aiVerdict(aiResponse.getAiVerdict())
                    .principles(new ValuationDto.Summary.Principles())
                    .build();

            // Create Display Layer
            ValuationDto.Summary.Display display = ValuationDto.Summary.Display.builder()
                    .verdict(modelVerdictObj.getRating())
                    .verdictLabel(mapVerdictToLabel(modelVerdictObj.getRating(),
                            aiResponse.getAiVerdict() != null ? aiResponse.getAiVerdict().getStance() : null))
                    .summary(aiResponse.getBeginnerVerdict() != null
                            ? aiResponse.getBeginnerVerdict().getSummarySentence()
                            : "분석 데이터를 불러올 수 없습니다.")
                    .risk(aiResponse.getAiVerdict() != null && aiResponse.getAiVerdict().getRiskLevel() != null
                            ? aiResponse.getAiVerdict().getRiskLevel().name()
                            : "UNKNOWN")
                    .build();

            Summary newSummary = Summary.builder()
                    .overallVerdict(oldSummary.getOverallVerdict())
                    .confidence(oldSummary.getConfidence())
                    .keyInsight(oldSummary.getKeyInsight())
                    .valuationLogic(oldSummary.getValuationLogic())
                    .decisionRule(oldSummary.getDecisionRule())
                    .aiAnalysis(aiAnalysis) // Keep raw text (Hidden by @JsonIgnore)
                    .verdicts(verdicts)
                    .beginnerVerdict(aiResponse.getBeginnerVerdict())
                    .display(display)
                    .build();

            return Response.builder()
                    .stockCode(response.getStockCode())
                    .stockName(response.getStockName())
                    .currentPrice(response.getCurrentPrice())
                    .marketCap(response.getMarketCap())
                    .targetReturn(response.getTargetReturn())
                    .discountRate(response.getDiscountRate())
                    .srim(response.getSrim())
                    .per(response.getPer())
                    .pbr(response.getPbr())
                    .band(response.getBand())
                    .summary(newSummary)
                    .analysisDetails(aiResponse.getAnalysisDetails())
                    .build();
        }

        // If no AI Analysis, return original response
        return response;
    }

    private String mapVerdictToLabel(String modelRating, ValuationDto.Stance aiStance) {
        // 1. If AI Stance is explicit BUY/ACCUMULATE, use "분할 매수"
        // (Assuming model isn't SELL. If model is SELL but AI is BUY, it's a conflict
        // based on principle,
        // but here we just map for display. Let's follow the user's simple suggestion:
        // Model Verdict 기준 + AI Correction)

        if ("BUY".equals(modelRating)) {
            if (aiStance == ValuationDto.Stance.ACCUMULATE)
                return "분할 매수";
            return "매수";
        } else if ("SELL".equals(modelRating)) {
            if (aiStance == ValuationDto.Stance.REDUCE)
                return "비중 축소";
            return "매도";
        } else {
            // HOLD
            if (aiStance == ValuationDto.Stance.ACCUMULATE)
                return "분할 매수 (관망)";
            if (aiStance == ValuationDto.Stance.REDUCE)
                return "비중 축소 (관망)";
            return "관망";
        }
    }

    private ModelVerdict createModelVerdict(Summary summary) {
        // Reverse engineer score (Optional: Pass score from generateSummary if
        // refactored)
        int score = 0;
        String verdict = summary.getOverallVerdict();
        if ("BUY".equals(verdict))
            score = 4; // Approx
        else if ("SELL".equals(verdict))
            score = -4; // Approx
        else
            score = 0;

        return ModelVerdict.builder()
                .rating(verdict)
                .score(score)
                .confidence(summary.getConfidence())
                .question("내재가치 대비 저평가되었는가?")
                .build();
    }

    @Getter
    @Setter
    @NoArgsConstructor
    private static class AiResponseJson {
        private AiVerdict aiVerdict;
        private ValuationDto.Summary.BeginnerVerdict beginnerVerdict;
        private ValuationDto.AnalysisDetails analysisDetails;
    }

    private AiResponseJson parseAiResponse(String jsonText) {
        AiResponseJson res = null;
        try {
            String json = extractJson(jsonText);
            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            res = mapper.readValue(json, AiResponseJson.class);
        } catch (Exception e) {
            log.error("Failed to parse AI Response JSON: {}", e.getMessage());
        }

        // Validate and apply Fallback if any critical field is missing
        if (res == null || res.getAiVerdict() == null || res.getBeginnerVerdict() == null) {
            AiResponseJson fallback = (res == null) ? new AiResponseJson() : res;

            if (fallback.getAiVerdict() == null) {
                fallback.setAiVerdict(AiVerdict.builder()
                        .stance(ValuationDto.Stance.HOLD)
                        .timing(ValuationDto.Timing.UNCERTAIN)
                        .riskLevel(ValuationDto.RiskLevel.MEDIUM)
                        .guidance("AI 분석 데이터를 불러오는 데 실패했습니다.")
                        .alignmentNote("데이터 파싱 오류 또는 서비스 응답 지연")
                        .build());
            }

            if (fallback.getBeginnerVerdict() == null) {
                fallback.setBeginnerVerdict(ValuationDto.Summary.BeginnerVerdict.builder()
                        .summarySentence("분석 데이터를 불러오는 중 오류가 발생했어요. 잠시 후 다시 시도해주세요.")
                        .build());
            }
            return fallback;
        }

        return res;
    }

    private String extractJson(String text) {
        int start = text.indexOf("{");
        int end = text.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return "{}";
    }

    @Transactional
    @Cacheable(value = "staticAnalysis", key = "#stockCode")
    public String getStaticAnalysis(String stockCode) {
        // 1. Check Cache (New Repository)
        Optional<StockStaticAnalysis> cachedAnalysis = stockStaticAnalysisRepository.findByStockCode(stockCode);
        if (cachedAnalysis.isPresent()) {
            StockStaticAnalysis staticData = cachedAnalysis.get();
            // DB Cache for 1 Year (8760 hours)
            if (staticData.getContent() != null && !staticData.isExpired(8760)) {
                return staticData.getContent();
            }
        }

        // 2. Generate
        Stock stock = stockRepository.findByStockCode(stockCode)
                .orElseThrow(() -> new IllegalArgumentException("Stock not found: " + stockCode));

        // Get Financial Context for Static Analysis (Growth, Risks)
        List<StockFinancialStatement> recentAnnuals = stockFinancialStatementRepository
                .findTop5ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");

        String analysisContent = generateStaticAnalysisText(stock, recentAnnuals);

        // 3. Save (New Entity)
        if (cachedAnalysis.isPresent()) {
            StockStaticAnalysis existing = cachedAnalysis.get();
            existing.updateContent(analysisContent);
            stockStaticAnalysisRepository.save(existing);
        } else {
            StockStaticAnalysis newAnalysis = StockStaticAnalysis.builder()
                    .stockCode(stockCode)
                    .content(analysisContent)
                    .lastModifiedDate(LocalDateTime.now()) // Default
                    .createdDate(LocalDateTime.now())
                    .build();
            stockStaticAnalysisRepository.save(newAnalysis);
        }

        return analysisContent;
    }

    @Transactional
    public String getValuationAnalysis(String stockCode) {
        // 1. Calculate Standard Valuation (Neutral)
        Request standardRequest = Request.builder()
                .userPropensity(UserPropensity.NEUTRAL)
                .build();
        Response val = calculateValuation(stockCode, standardRequest);
        return getValuationAnalysis(stockCode, val);
    }

    @Transactional
    public String getValuationAnalysis(String stockCode, Response val) {
        BigDecimal currentPrice = new BigDecimal(val.getCurrentPrice().replace(",", ""));

        // 2. Check Cache
        InvestorTrendDto investorTrend = null;
        Optional<StockAiSummary> cachedSummary = stockAiSummaryRepository.findByStockCode(stockCode);
        if (cachedSummary.isPresent()) {
            StockAiSummary summary = cachedSummary.get();
            // Cache Policy:
            // 1. Time-based: 1 Month (720 hours) - effectively permanent for stable stocks
            // 2. Price Trigger: ±5% Deviation
            // 3. Supply Trigger: Significant Divergence (Calculated during generation)
            boolean isExpired = summary.isExpired(720);
            boolean isPriceDeviated = false;

            if (summary.getReferencePrice() != null && summary.getReferencePrice().compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal refPrice = summary.getReferencePrice();
                BigDecimal diff = currentPrice.subtract(refPrice).abs();
                BigDecimal deviation = diff.divide(refPrice, 4, RoundingMode.HALF_UP);
                if (deviation.compareTo(new BigDecimal("0.05")) > 0) {
                    isPriceDeviated = true;
                }
            }

            // Supply Divergence Check (Optimized - Skip full generation if trigger not hit)
            boolean isSupplyDiverged = false;
            try {
                investorTrend = kisStockService.getInvestorTrend(stockCode);
                // Trigger if Z-Score deviates by more than 1.0 or Score deviates by more than
                // 20
                if (Math.abs(investorTrend.getForeignerZScore() - summary.getLastForeignerZScore()) > 1.0 ||
                        Math.abs(investorTrend.getSupplyScore() - summary.getLastSupplyScore()) > 20.0) {
                    isSupplyDiverged = true;
                }
            } catch (Exception e) {
                log.warn("Failed to check supply divergence for cache: {}", e.getMessage());
            }

            if (summary.getValuationAnalysis() != null && !isExpired && !isPriceDeviated && !isSupplyDiverged) {
                return summary.getValuationAnalysis();
            }
        }

        // 3. Generate
        StockFinancialRatio latestRatio = stockFinancialRatioRepository
                .findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
        StockBalanceSheet latestBalanceSheet = stockBalanceSheetRepository
                .findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
        List<StockFinancialStatement> recentQuarters = stockFinancialStatementRepository
                .findTop5ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "1");

        // Fetch Investor Trend (if not already fetched during divergence check)
        if (investorTrend == null) {
            try {
                investorTrend = kisStockService.getInvestorTrend(stockCode);
            } catch (Exception e) {
                log.warn("Failed to fetch investor trend: {}", e.getMessage());
            }
        }

        String analysis = generateValuationAnalysisText(val, latestRatio, latestBalanceSheet, recentQuarters,
                investorTrend);

        // Parse for Display Fields (Optimize for fast read)
        AiResponseJson aiJson = parseAiResponse(analysis);
        ModelVerdict modelVerdict = createModelVerdict(val.getSummary());

        String displayVerdict = modelVerdict.getRating();
        String displayLabel = mapVerdictToLabel(displayVerdict,
                aiJson.getAiVerdict() != null ? aiJson.getAiVerdict().getStance() : null);
        String displaySummary = aiJson.getBeginnerVerdict() != null ? aiJson.getBeginnerVerdict().getSummarySentence()
                : "분석 데이터를 불러올 수 없습니다.";
        String displayRisk = aiJson.getAiVerdict() != null && aiJson.getAiVerdict().getRiskLevel() != null
                ? aiJson.getAiVerdict().getRiskLevel().name()
                : "UNKNOWN";
        log.info("Display summary logic check: {}", displaySummary);

        // 4. Save
        if (cachedSummary.isPresent()) {
            StockAiSummary existing = cachedSummary.get();
            existing.updateValuationAnalysis(analysis, currentPrice, displayVerdict, displayLabel, displaySummary,
                    displayRisk, investorTrend != null ? investorTrend.getSupplyScore() : 0,
                    investorTrend != null ? investorTrend.getForeignerZScore() : 0);
            stockAiSummaryRepository.save(existing);
        } else {
            StockAiSummary newSummary = StockAiSummary.builder()
                    .stockCode(stockCode)
                    .valuationAnalysis(analysis)
                    .referencePrice(currentPrice)
                    .displayVerdict(displayVerdict)
                    .displayLabel(displayLabel)
                    .displaySummary(displaySummary)
                    .displayRisk(displayRisk)
                    .lastSupplyScore(investorTrend != null ? investorTrend.getSupplyScore() : 0)
                    .lastForeignerZScore(investorTrend != null ? investorTrend.getForeignerZScore() : 0)
                    .lastModifiedDate(LocalDateTime.now())
                    .createdDate(LocalDateTime.now())
                    .build();
            stockAiSummaryRepository.save(newSummary);
        }

        return analysis;
    }

    // Deprecated or Combined usage

    private String generateStaticAnalysisText(Stock stock, List<StockFinancialStatement> recentAnnuals) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("다음 기업의 **기업 개요**, **미래 성장 동력**, **리스크 요인**을 분석해줘.\n\n");
        prompt.append(String.format("종목명: %s (%s)\n", stock.getStockName(), stock.getStockCode()));

        if (recentAnnuals != null && !recentAnnuals.isEmpty()) {
            prompt.append("\n[연간 실적 추이 (참고용)]\n");
            for (int i = recentAnnuals.size() - 1; i >= 0; i--) {
                StockFinancialStatement s = recentAnnuals.get(i);
                prompt.append(String.format("- %s: 매출 %s / 영익 %s\n",
                        s.getStacYymm(), s.getSaleAccount(), s.getOperatingProfit()));
            }
        }

        prompt.append("\n[요청사항]\n");
        prompt.append("1. **Google Search**를 활용하여 최신 정보를 반영해.\n");
        prompt.append("2. 다음 3가지 항목만 작성해 (마크다운 헤더 필수):\n");
        prompt.append("   - **## 1. 기업 개요**: 주요 제품, 핵심 경쟁력, 사업 구조.\n");
        prompt.append("   - **## 2. 미래 성장 동력**: 신사업, R&D, 최근 호재.\n");
        prompt.append("   - **## 3. 리스크 요인**: 규제, 경쟁, 대외 환경(환율/금리/정치) 등.\n");
        prompt.append("3. 가치평가(주가 판단)는 절대 포함하지 마.\n");
        prompt.append("4. 금융 전문가 톤으로, 각 항목당 3~4줄 내외로 요약.\n");

        return geminiService.generateAdvice(prompt.toString());
    }

    private String generateValuationAnalysisText(Response val, StockFinancialRatio latestRatio,
            StockBalanceSheet balanceSheet,
            List<StockFinancialStatement> recentQuarters,
            InvestorTrendDto investorTrend) {
        StringBuilder prompt = new StringBuilder();
        String stockCode = val.getStockCode();

        // 1. Context Gathering
        String industryContext = getIndustryContext(stockCode);
        String fundamentalAnalysis = analyzeFundamentals(latestRatio, recentQuarters);
        String competitorData = analyzeCompetitorData(stockCode);
        String sectorPer = calculateSectorAveragePer(stockCode); // Added

        com.AISA.AISA.kisStock.dto.Dividend.DividendDetailDto dividendInfo = null;
        try {
            dividendInfo = dividendService.getDividendDetail(stockCode);
        } catch (Exception e) {
            log.warn("Failed to fetch dividend info for {}: {}", stockCode, e.getMessage());
        }

        prompt.append("너는 '주식 투자 전략가(Strategist)'이다. 단순한 숫자 해석을 넘어, 구체적인 매매 전략과 통찰력을 제시해야 한다.\n");
        prompt.append("\n[분석 대상 핵심 요약]\n");
        prompt.append(String.format("- 종목: %s (%s)\n", val.getStockName(), stockCode));
        prompt.append(String.format("- 섹터: %s\n", industryContext));
        prompt.append(String.format("- 현재가: %s원 (시가총액: %s)\n", val.getCurrentPrice(), val.getMarketCap()));
        prompt.append(String.format("- 모델 의견: **%s** (신뢰도: %s)\n",
                val.getSummary().getOverallVerdict(), val.getSummary().getConfidence()));

        prompt.append("\n[펀더멘털 심층 분석]\n");
        prompt.append(String.format("- 요약: %s\n", fundamentalAnalysis));
        if (latestRatio != null) {
            prompt.append(String.format("- PER: %.2f, PBR: %.2f\n", latestRatio.getPer(), latestRatio.getPbr()));
            if (latestRatio.getEps() != null)
                prompt.append(String.format("- EPS: %s원, ROE: %.2f%%\n", latestRatio.getEps(), latestRatio.getRoe()));

            prompt.append(String.format("- 성장성: 매출증가율 %.2f%%, 영업이익증가율 %.2f%%\n",
                    latestRatio.getSalesGrowth(), latestRatio.getOperatingProfitGrowth()));
            prompt.append(String.format("- 안정성: 부채비율 %.2f%%, 유보율 %.2f%%\n",
                    latestRatio.getDebtRatio(), latestRatio.getReserveRatio()));
        }
        if (dividendInfo != null) {
            prompt.append(String.format("- 배당: 수익률 %s%%, 성향 %s%%, 주기 %s\n",
                    dividendInfo.getDividendYield(), dividendInfo.getPayoutRatio(),
                    dividendInfo.getDividendFrequency()));
        }

        prompt.append("\n[수급 데이터 상세 (Professional Supply Analysis)]\n");
        prompt.append(String.format("- 종합 분석: %s (점수: %.1f)\n",
                investorTrend != null ? investorTrend.getTrendStatus() : "N/A",
                investorTrend != null ? investorTrend.getSupplyScore() : 0));

        if (investorTrend != null) {
            prompt.append(String.format("- [1개월] 외인 %s, 기관 %s (Z-Score: F=%.2f, I=%.2f)\n",
                    formatLargeNumber(parseBigDecimalSafe(investorTrend.getRecent1MonthForeignerNetBuy())),
                    formatLargeNumber(parseBigDecimalSafe(investorTrend.getRecent1MonthInstitutionNetBuy())),
                    investorTrend.getForeignerZScore(), investorTrend.getInstitutionZScore()));
            prompt.append(String.format("- [3개월] 외인 %s, 기관 %s\n",
                    formatLargeNumber(parseBigDecimalSafe(investorTrend.getRecent3MonthForeignerNetBuy())),
                    formatLargeNumber(parseBigDecimalSafe(investorTrend.getRecent3MonthInstitutionNetBuy()))));
            prompt.append(String.format("- [1년] 외인 %s, 기관 %s\n",
                    formatLargeNumber(parseBigDecimalSafe(investorTrend.getRecent1YearForeignerNetBuy())),
                    formatLargeNumber(parseBigDecimalSafe(investorTrend.getRecent1YearInstitutionNetBuy()))));

            prompt.append(String.format("- [매집원가/과열도] 외인 평단: %s원 (과열: %s), 기관 평단: %s원 (과열: %s)\n",
                    formatLargeNumber(investorTrend.getForeignerAvgPrice()),
                    formatOverheat(investorTrend.getForeignerOverheat()),
                    formatLargeNumber(investorTrend.getInstitutionAvgPrice()),
                    formatOverheat(investorTrend.getInstitutionOverheat())));

            // 신규 상장주 또는 데이터 부족에 대한 힌트 (Z-Score가 모두 0이면 보통 데이터 부족)
            if (investorTrend.getForeignerZScore() == 0 && investorTrend.getInstitutionZScore() == 0) {
                prompt.append(
                        "! 주의: 상장한 지 얼마 되지 않았거나 충분한 데이터(20일 미만)가 확보되지 않은 종목입니다. 장기 트렌드보다는 최근 수급과 공모가 대비 위치를 중시하십시오.\n");
            }
        }

        prompt.append("\n[Valuation Model 요약]\n");
        prompt.append("1. S-RIM (초과이익 모델): " + val.getSrim().getVerdict() + " (적정가: " + val.getSrim().getPrice()
                + ")\n");
        prompt.append(
                "2. PER 모델 (역사적 밴드): " + val.getPer().getVerdict() + " (적정가: " + val.getPer().getPrice() + ")\n");
        prompt.append(
                "3. PBR 모델 (자산 가치): " + val.getPbr().getVerdict() + " (적정가: " + val.getPbr().getPrice() + ")\n");

        prompt.append("\n[경쟁사 비교 데이터 (Competitor Data)]\n");
        prompt.append(competitorData);
        prompt.append("\n");

        prompt.append("\n[전략가로서의 미션]\n");
        prompt.append(
                "1. **통계적 수급 분석**: Z-Score가 +1.5 이상이면 '이례적 매집'으로, 과열 지수가 25% 이상이면 '가격 부담'으로 해석하라.\n");
        prompt.append("2. **수급 다이버전스**: 주가는 횡보/하락하지만 수급 점수가 상승하거나 Z-Score가 튄다면 이를 '잠재적 반등 신호'로 구체적으로 설명하라.\n");
        prompt.append("3. **매집 단가 활용**: 주가가 기관/외인 평단가(VWAP) 근처에 있다면 '안전마진 확보' 관점에서 분석하라.\n");
        prompt.append("4. **Google Search**와 **경쟁사 데이터**를 사용하여 동종 업계 Peers와 밸류에이션을 대조하라.\n");
        prompt.append("5. **초보자 코멘트**: 존댓말로 아주 쉽게 작성하되, '기관 평단가'라는 개념을 사용하여 가격 유리함을 설명해주어라.\n");

        prompt.append("\n[출력 포맷 (JSON 엄수)]\n");
        prompt.append("```json\n");
        prompt.append("{\n");
        prompt.append("  \"aiVerdict\": {\n");
        prompt.append("    \"stance\": \"[BUY, ACCUMULATE, HOLD, REDUCE, SELL]\",\n");
        prompt.append("    \"timing\": \"[EARLY, MID, LATE, UNCERTAIN]\",\n");
        prompt.append("    \"riskLevel\": \"[LOW, MEDIUM, HIGH]\",\n");
        prompt.append("    \"guidance\": \"종합 투자의견 (매집 원가 및 수급 점수 언급 포함, 300자 내외)\",\n");
        prompt.append("    \"alignmentNote\": \"수급 다이버전스 유무 및 평단가 대비 가격적 매력도 설명\"\n");
        prompt.append("  },\n");
        prompt.append("  \"analysisDetails\": {\n");
        prompt.append("    \"upsidePotential\": \"+OO% (목표가 OO원)\",\n");
        prompt.append("    \"downsideRisk\": \"-OO% (손절가 OO원)\",\n");
        prompt.append("    \"investmentTerm\": \"추천 투자 기간 (예: 6개월)\",\n");
        prompt.append("    \"catalysts\": [\"수급 주체별 매집 가속화\", \"저평가 해소\"],\n");
        prompt.append("    \"risks\": [\"과열 지수 상승에 따른 차익실현\", \"업황 부진\"],\n");
        prompt.append("    \"peerComparison\": {\n");
        prompt.append("      \"sectorAvgPer\": \"" + sectorPer + "\",\n");
        prompt.append("      \"status\": \"[BELOW_SECTOR_AVG, SIMILAR, ABOVE_SECTOR_AVG]\",\n");
        prompt.append("      \"peers\": [\n");
        prompt.append("        {\"name\": \"경쟁사A\", \"per\": \"12.5\", \"pbr\": \"1.1\"}\n");
        prompt.append("      ]\n");
        prompt.append("    },\n");
        prompt.append("    \"investorTrend\": {\n");
        prompt.append("       \"supplyScore\": " + (investorTrend != null ? (int) investorTrend.getSupplyScore() : 0)
                + ",\n");
        prompt.append("       \"foreignerOverheat\": "
                + (investorTrend != null ? investorTrend.getForeignerOverheat() : 0) + ",\n");
        prompt.append("       \"institutionOverheat\": "
                + (investorTrend != null ? investorTrend.getInstitutionOverheat() : 0) + ",\n");
        prompt.append("       \"trendStatus\": \"" + (investorTrend != null ? investorTrend.getTrendStatus() : "N/A")
                + "\",\n");
        prompt.append("       \"advice\": \"수급 데이터(평단가, 과열도, Z-Score)를 바탕으로 한 전문가적 조언 (200자 내외)\"\n");
        prompt.append("    }\n");
        prompt.append("  },\n");
        prompt.append("  \"beginnerVerdict\": {\n");
        prompt.append("    \"summarySentence\": \"기관이나 외국인이 산 평균 가격보다 저렴해요! 같이 사볼까요?\"\n");
        prompt.append("  }\n");
        prompt.append("}\n");
        prompt.append("```\n");
        prompt.append("\n! 주의: 출력은 반드시 위 JSON 형식만 허용되며, 다른 텍스트를 포함하지 마시오.\n");

        return geminiService.generateAdvice(prompt.toString());
    }

    // Legacy Support (Wrapper)
    private String generateAiDiagnosis(Response val, StockFinancialRatio latestRatio, StockBalanceSheet balanceSheet,
            List<StockFinancialStatement> recentAnnuals, List<StockFinancialStatement> recentQuarters) {
        // This serves as a fallback or for non-cached calls if any
        return getStaticAnalysis(val.getStockCode()) + "\n\n" + getValuationAnalysis(val.getStockCode());
    }

    public String fetchBondYieldFromEcos() {
        return kisMacroService.getLatestEcosBondYield();
    }

    @Transactional
    public void clearAiSummaryCache() {
        stockAiSummaryRepository.deleteAll();
    }

    private String analyzeCompetitorData(String stockCode) {
        StringBuilder sb = new StringBuilder();
        try {
            List<Stock> competitors = competitorAnalysisService.findCompetitors(stockCode);
            if (competitors.isEmpty()) {
                return "경쟁사 데이터 없음 (검색 필요)";
            }

            for (Stock comp : competitors) {
                // Fetch Latest Ratio
                StockFinancialRatio ratio = stockFinancialRatioRepository
                        .findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(comp.getStockCode(), "0");

                String per = (ratio != null && ratio.getPer() != null) ? ratio.getPer().toString() : "N/A";
                String pbr = (ratio != null && ratio.getPbr() != null) ? ratio.getPbr().toString() : "N/A";

                sb.append(String.format("- %s (%s): PER %s, PBR %s\n",
                        comp.getStockName(), comp.getStockCode(), per, pbr));
            }
        } catch (Exception e) {
            log.warn("Failed to fetch competitor data for {}: {}", stockCode, e.getMessage());
            return "경쟁사 데이터 로드 실패 (검색 권장)";
        }
        return sb.toString();
    }

    private BigDecimal parseBigDecimalSafe(String value) {
        try {
            if (value == null || value.trim().isEmpty())
                return BigDecimal.ZERO;
            return new BigDecimal(value.trim());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private String formatOverheat(BigDecimal overheat) {
        if (overheat == null)
            return "N/A";
        return String.format("%.1f%%", overheat);
    }

    // --- New Helper Methods for Advanced Analysis ---

    private String getIndustryContext(String stockCode) {
        try {
            Stock stock = stockRepository.findByStockCode(stockCode).orElse(null);
            if (stock != null && !stock.getStockIndustries().isEmpty()) {
                com.AISA.AISA.kisStock.Entity.stock.StockIndustry mainInd = stock.getStockIndustries().stream()
                        .filter(com.AISA.AISA.kisStock.Entity.stock.StockIndustry::isPrimary)
                        .findFirst()
                        .orElse(stock.getStockIndustries().get(0));

                String indName = mainInd.getSubIndustry().getIndustry().getName();
                String subIndName = mainInd.getSubIndustry().getName();
                return String.format("Industry: %s / Sub-Industry: %s", indName, subIndName);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch industry context for {}: {}", stockCode, e.getMessage());
        }
        return "Industry: Unknown";
    }

    private String analyzeInvestorTrendDetails(String stockCode) {
        StringBuilder analysis = new StringBuilder();
        try {
            List<com.AISA.AISA.kisStock.dto.InvestorTrend.StockInvestorDailyDto> trends = kisStockService
                    .getDailyInvestorTrend(stockCode, "3m"); // This returns DTO list

            if (trends == null || trends.isEmpty())
                return "수급 데이터 부족";

            // Sort by date desc just in case
            trends.sort((t1, t2) -> t2.getDate().compareTo(t1.getDate()));

            // Analyze last 5 days
            int limit = Math.min(trends.size(), 5);
            int foreignerBuyCount = 0;
            int institutionBuyCount = 0;
            long foreignerNetSum = 0;
            long institutionNetSum = 0;

            for (int i = 0; i < limit; i++) {
                com.AISA.AISA.kisStock.dto.InvestorTrend.StockInvestorDailyDto day = trends.get(i);

                // Parse amounts (assuming they are strings like "123456" or "-123")
                // Check for empty or non-numeric just in case, though DTO usually has valid
                // strings or "0"
                long fNet = parseLongSafe(day.getForeignerNetBuyAmount());
                long iNet = parseLongSafe(day.getInstitutionNetBuyAmount());

                if (fNet > 0)
                    foreignerBuyCount++;
                if (iNet > 0)
                    institutionBuyCount++;
                foreignerNetSum += fNet;
                institutionNetSum += iNet;
            }

            analysis.append(String.format("최근 %d거래일 중 외국인 %d일 순매수", limit, foreignerBuyCount));
            if (foreignerBuyCount >= 4)
                analysis.append("(강한 매수세), ");
            else
                analysis.append(", ");

            analysis.append(String.format("기관 %d일 순매수", institutionBuyCount));
            if (institutionBuyCount >= 4)
                analysis.append("(강한 매수세). ");
            else
                analysis.append(". ");

            if (foreignerNetSum > 0 && institutionNetSum > 0)
                analysis.append("양매수 유입 중. ");
            else if (foreignerNetSum < 0 && institutionNetSum < 0)
                analysis.append("양매도 출회 중. ");

        } catch (Exception e) {
            log.warn("Failed to analyze investor trend details for {}: {}", stockCode, e.getMessage());
            return "수급 분석 실패";
        }
        return analysis.toString();
    }

    private long parseLongSafe(String value) {
        try {
            if (value == null || value.trim().isEmpty())
                return 0;
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String analyzeFundamentals(StockFinancialRatio ratio, List<StockFinancialStatement> recentQuarters) {
        if (ratio == null)
            return "데이터 부족";

        StringBuilder sb = new StringBuilder();

        // 1. Profitability
        if (ratio.getRoe() != null) {
            sb.append(String.format("ROE: %.2f%% ", ratio.getRoe()));
            if (ratio.getRoe().compareTo(new BigDecimal(15)) >= 0)
                sb.append("(고수익성), ");
            else if (ratio.getRoe().compareTo(new BigDecimal(5)) < 0)
                sb.append("(저수익성), ");
            else
                sb.append(", ");
        }

        if (ratio.getSalesGrowth() != null) {
            sb.append(String.format("매출증가율: %.2f%% ", ratio.getSalesGrowth()));
            if (ratio.getSalesGrowth().compareTo(BigDecimal.ZERO) > 0)
                sb.append("(외형성장), ");
            else
                sb.append("(역성장), ");
        }

        if (ratio.getOperatingProfitGrowth() != null) {
            sb.append(String.format("영업이익증가율: %.2f%% ", ratio.getOperatingProfitGrowth()));
            if (ratio.getOperatingProfitGrowth().compareTo(BigDecimal.ZERO) > 0)
                sb.append("(성장), ");
            else
                sb.append("(역성장), ");
        }

        // 2. Stability
        if (ratio.getDebtRatio() != null) {
            sb.append(String.format("부채비율: %.2f%%", ratio.getDebtRatio()));
            if (ratio.getDebtRatio().compareTo(new BigDecimal(200)) > 0)
                sb.append("(재무 리스크 유의)");
            else if (ratio.getDebtRatio().compareTo(new BigDecimal(50)) < 0)
                sb.append("(매우 우량)");
        }

        return sb.toString();
    }

    private String calculateSectorAveragePer(String stockCode) {
        try {
            // 1. Get Stock & Industry
            Stock stock = stockRepository.findByStockCode(stockCode).orElse(null);
            if (stock == null || stock.getStockIndustries().isEmpty())
                return "N/A";

            // Use Primary SubIndustry
            com.AISA.AISA.kisStock.Entity.stock.StockIndustry mainInd = stock.getStockIndustries().stream()
                    .filter(com.AISA.AISA.kisStock.Entity.stock.StockIndustry::isPrimary)
                    .findFirst()
                    .orElse(stock.getStockIndustries().get(0));

            String subCode = mainInd.getSubIndustry().getCode();

            // 2. Find Peers in same SubIndustry (excluding self)
            // Note: stockRepository.findBySubIndustryCodeAndStockCodeNot needs to be
            // implemented or accessible
            // If not directly available in Interface, we might need to rely on existing
            // findBySubIndustry... and filter

            // Checking Repository capabilities:
            // We previously used:
            // stockRepository.findBySubIndustryCodeAndStockCodeNot(subCode, stockCode)
            // in CompetitorAnalysisService. So it should be valid if I saw it there.

            List<Stock> peers = stockRepository.findBySubIndustryCodeAndStockCodeNot(subCode, stockCode);
            if (peers.isEmpty())
                return "N/A";

            // 3. Calculate Average PER
            double sumPer = 0;
            int count = 0;

            for (Stock peer : peers) {
                StockFinancialRatio ratio = stockFinancialRatioRepository
                        .findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(peer.getStockCode(), "0");

                if (ratio != null && ratio.getPer() != null) {
                    double p = ratio.getPer().doubleValue();
                    if (p > 0 && p < 200) { // Filter outliers (negative or extremely high)
                        sumPer += p;
                        count++;
                    }
                }
            }

            if (count == 0)
                return "N/A (유효 데이터 부족)";

            return String.format("%.2f (동일 소분류 %d개사 평균)", sumPer / count, count);

        } catch (Exception e) {
            log.warn("Failed to calculate sector PER for {}: {}", stockCode, e.getMessage());
            return "N/A (계산 오류)";
        }
    }
}
