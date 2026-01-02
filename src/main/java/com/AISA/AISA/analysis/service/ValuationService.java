package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.dto.ValuationDto;
import com.AISA.AISA.analysis.dto.ValuationDto.Response;
import com.AISA.AISA.analysis.dto.ValuationDto.Summary;
import com.AISA.AISA.analysis.dto.ValuationDto.ValuationBand;
import com.AISA.AISA.analysis.dto.ValuationDto.ValuationResult;
import com.AISA.AISA.analysis.entity.StockAiSummary;
import com.AISA.AISA.analysis.repository.StockAiSummaryRepository;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialRatio;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialStatement;
import com.AISA.AISA.kisStock.Entity.stock.StockBalanceSheet;
import com.AISA.AISA.kisStock.kisService.KisStockService;
import com.AISA.AISA.kisStock.dto.StockPrice.StockPriceDto;
import com.AISA.AISA.kisStock.repository.StockFinancialRatioRepository;
import com.AISA.AISA.kisStock.repository.StockFinancialStatementRepository;
import com.AISA.AISA.kisStock.repository.StockBalanceSheetRepository;
import com.AISA.AISA.kisStock.repository.StockRepository;
import com.AISA.AISA.kisStock.kisService.KisMacroService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final KisMacroService kisMacroService; // Added

    @Autowired
    public ValuationService(StockRepository stockRepository,
            StockFinancialRatioRepository stockFinancialRatioRepository,
            StockBalanceSheetRepository stockBalanceSheetRepository,
            StockFinancialStatementRepository stockFinancialStatementRepository,
            KisStockService kisStockService,
            GeminiService geminiService,
            StockAiSummaryRepository stockAiSummaryRepository,
            KisMacroService kisMacroService) {
        this.stockRepository = stockRepository;
        this.stockFinancialRatioRepository = stockFinancialRatioRepository;
        this.stockBalanceSheetRepository = stockBalanceSheetRepository;
        this.stockFinancialStatementRepository = stockFinancialStatementRepository;
        this.kisStockService = kisStockService;
        this.geminiService = geminiService;
        this.stockAiSummaryRepository = stockAiSummaryRepository;
        this.kisMacroService = kisMacroService;
    }

    // 기본 요구 수익률 (Fallback)

    @Transactional
    public Response getStandardizedValuationReport(String stockCode) {
        // 1. Calculate Standard Valuation (Neutral)
        ValuationDto.Request standardRequest = ValuationDto.Request.builder()
                .userPropensity(ValuationDto.UserPropensity.NEUTRAL)
                .build();
        Response response = calculateValuation(stockCode, standardRequest);

        // Fetch Data for AI (Same as calculateValuationWithAi)
        StockFinancialRatio latestRatio = stockFinancialRatioRepository
                .findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
        StockBalanceSheet latestBalanceSheet = stockBalanceSheetRepository
                .findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
        List<StockFinancialStatement> recentAnnuals = stockFinancialStatementRepository
                .findTop5ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
        List<StockFinancialStatement> recentQuarters = stockFinancialStatementRepository
                .findTop5ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "1");

        // 2. Fetch Cached AI Analysis (Standardized)
        // Note: We pass the 'standard' response here to be safe
        String aiAnalysis = getOrGenerateCachedAiAnalysis(stockCode, response, latestRatio, latestBalanceSheet,
                recentAnnuals, recentQuarters);

        // 3. Build Final Response
        return buildResponseWithAi(response, aiAnalysis);
    }

    public Response calculateValuation(String stockCode, ValuationDto.Request request) {
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
        BigDecimal trillion = new BigDecimal("1000000000000");
        BigDecimal billion = new BigDecimal("100000000");

        if (value.compareTo(trillion) >= 0) {
            return value.divide(trillion, 1, RoundingMode.HALF_UP) + "조원";
        } else if (value.compareTo(billion) >= 0) {
            return value.divide(billion, 0, RoundingMode.HALF_UP) + "억원";
        }
        return value.toString() + "원";
    }

    private ValuationDto.DiscountRateInfo determineCOE(ValuationDto.Request request) {
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

    public Response calculateValuationWithAi(String stockCode, ValuationDto.Request request) {
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

        // 3. Update Summary with AI Analysis
        return buildResponseWithAi(response, aiAnalysis);
    }

    private Response buildResponseWithAi(Response response, String aiAnalysis) {
        if (response.getSummary() != null) {
            Summary oldSummary = response.getSummary();
            Summary newSummary = Summary.builder()
                    .overallVerdict(oldSummary.getOverallVerdict())
                    .confidence(oldSummary.getConfidence())
                    .keyInsight(oldSummary.getKeyInsight())
                    .valuationLogic(oldSummary.getValuationLogic())
                    .decisionRule(oldSummary.getDecisionRule())
                    .aiAnalysis(aiAnalysis)
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
                    .build();
        }
        return response;
    }

    private String generateAiDiagnosis(Response val, StockFinancialRatio latestRatio, StockBalanceSheet balanceSheet,
            List<StockFinancialStatement> recentAnnuals, List<StockFinancialStatement> recentQuarters) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("다음 종목의 가치평가 및 재무제표를 분석하고 정밀한 투자 조언을 해줘.\n\n");
        prompt.append(String.format("종목명: %s (%s)\n", val.getStockName(), val.getStockCode()));
        prompt.append(String.format("현재가: %s원 (시가총액: %s)\n", val.getCurrentPrice(), val.getMarketCap()));

        // Add Balance Sheet Data
        if (balanceSheet != null) {
            prompt.append("\n[재무상태표 (최근 연간)]\n");
            prompt.append(String.format("- 자산총계: %s\n", balanceSheet.getTotalAssets()));
            prompt.append(String.format("- 부채총계: %s\n", balanceSheet.getTotalLiabilities()));
            prompt.append(String.format("- 자본총계: %s\n", balanceSheet.getTotalCapital()));
        }

        // Add Income Statement Data (Annual Trend)
        if (recentAnnuals != null && !recentAnnuals.isEmpty()) {
            prompt.append("\n[연간 실적 추이 (최근 5년)]\n");
            // Reverse to show oldest -> new
            for (int i = recentAnnuals.size() - 1; i >= 0; i--) {
                StockFinancialStatement s = recentAnnuals.get(i);
                String note = "";
                // Check for Partial Year (Not ending in 12)
                if (s.getStacYymm() != null && !s.getStacYymm().endsWith("12")) {
                    note = " (부분 연도/진행중 - 단순비교 주의)";
                }
                prompt.append(String.format("- %s%s: 매출 %s / 영익 %s / 순익 %s\n",
                        s.getStacYymm(), note, s.getSaleAccount(), s.getOperatingProfit(), s.getNetIncome()));
            }
        }

        // Add Quarterly Trend Data (Crucial for detecting recent trends)
        if (recentQuarters != null && !recentQuarters.isEmpty()) {
            prompt.append("\n[최근 5분기 실적 추이 (단위: 억 원/백만 원 등 재무제표 단위 참조)]\n");
            // Reverse to show oldest -> new
            for (int i = recentQuarters.size() - 1; i >= 0; i--) {
                StockFinancialStatement q = recentQuarters.get(i);
                String yoyInfo = "";

                // Check if YoY data exists (4 quarters ago)
                if (i + 4 < recentQuarters.size()) {
                    StockFinancialStatement qYoY = recentQuarters.get(i + 4);
                    yoyInfo = calculateQuarterlyYoY(q, qYoY);
                }

                prompt.append(String.format("- %s: 매출 %s / 영익 %s / 순익 %s %s\n",
                        q.getStacYymm(), q.getSaleAccount(), q.getOperatingProfit(), q.getNetIncome(), yoyInfo));
            }
        }

        // Add Financial Ratios
        if (latestRatio != null) {
            prompt.append("\n[주요 재무 비율]\n");
            prompt.append(String.format("- 부채비율: %s%%\n", latestRatio.getDebtRatio()));
            prompt.append(String.format("- 유보율: %s%%\n", latestRatio.getReserveRatio()));
            prompt.append(String.format("- 매출 성장률(YoY): %s%%\n", latestRatio.getSalesGrowth()));
            prompt.append(String.format("- 영업이익 성장률(YoY): %s%%\n", latestRatio.getOperatingProfitGrowth()));
            prompt.append(String.format("- 당기순이익 성장률(YoY): %s%%\n", latestRatio.getNetIncomeGrowth()));
        } else {
            prompt.append("\n[재무 비율]\n데이터 없음 (AI가 검색을 통해 보완할 것)\n");
        }

        prompt.append("\n[모델별 평가]\n");
        prompt.append(String.format("1. S-RIM: %s (Gap: %s%%) - %s\n",
                val.getSrim().getVerdict(), val.getSrim().getGapRate(), val.getSrim().getDescription()));
        if (!val.getSrim().isAvailable())
            prompt.append("   - 사유: " + val.getSrim().getReason() + "\n");

        prompt.append(String.format("2. PER: %s (Gap: %s%%) - %s\n",
                val.getPer().getVerdict(), val.getPer().getGapRate(), val.getPer().getDescription()));
        if (!val.getPer().isAvailable())
            prompt.append("   - 사유: " + val.getPer().getReason() + "\n");

        prompt.append(String.format("3. PBR: %s (Gap: %s%%) - %s\n",
                val.getPbr().getVerdict(), val.getPbr().getGapRate(), val.getPbr().getDescription()));

        prompt.append("\n[종합 의견]\n");
        prompt.append(String.format("결론: %s (Confidence: %s)\n",
                val.getSummary().getOverallVerdict(), val.getSummary().getConfidence()));
        prompt.append(String.format("핵심: %s\n", val.getSummary().getKeyInsight()));
        prompt.append(String.format("규칙: %s\n", val.getSummary().getDecisionRule()));

        prompt.append("\n[요청사항]\n");
        prompt.append("1. 위 가치평가 데이터와 **Google Search**를 활용하여 종합적인 투자 리포트를 작성해.\n");
        prompt.append("2. **필수 포함 항목** (반드시 다음 순서와 마크다운 형식을 지켜줘):\n");
        prompt.append("   - **## 1. 기업 개요**: 주요 제품, 경쟁력, 수익 구조 (매출 비중 등)\n");
        prompt.append("   - **## 2. 미래 성장 동력**: 최근 추진 중인 신사업 또는 R&D 현황\n");
        prompt.append("   - **## 3. 리스크 요인**: 정치적 이슈(예: 트럼프 IRA 영향), 규제, 경쟁 심화 등 외부 환경 분석\n");
        prompt.append("   - **## 4. 가치평가 해석**: 모델 결과와 위 정성적 요인을 종합하여 현재 주가의 적정성 판단\n");
        prompt.append("3. '적정 주가' 표현 대신 '가치 평가 적정성'이나 '리스크 대비 매력도' 사용.\n");
        prompt.append("4. **강조**하고 싶은 단어(키워드)는 `**`를 사용하여 굵게 표시하고, 소제목(예: 기업 개요)과 구분되도록 해.\n");
        prompt.append("5. 금융 전문가 톤으로 작성하되, 600자 이내로 요약해.\n");

        return geminiService.generateAdvice(prompt.toString());
    }

    private String getOrGenerateCachedAiAnalysis(String stockCode, Response currentValuation,
            StockFinancialRatio ratio, StockBalanceSheet bs, List<StockFinancialStatement> annuals,
            List<StockFinancialStatement> quarters) {

        BigDecimal currentPrice = new BigDecimal(currentValuation.getCurrentPrice().replace(",", ""));

        // 1. Check Cache
        Optional<StockAiSummary> cachedSummary = stockAiSummaryRepository.findByStockCode(stockCode);
        if (cachedSummary.isPresent()) {
            StockAiSummary summary = cachedSummary.get();
            boolean isExpired = summary.isExpired(24);
            boolean isPriceDeviated = false;

            // Check Price Trigger (5% deviation)
            if (summary.getReferencePrice() != null && summary.getReferencePrice().compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal refPrice = summary.getReferencePrice();
                BigDecimal diff = currentPrice.subtract(refPrice).abs();
                BigDecimal deviation = diff.divide(refPrice, 4, RoundingMode.HALF_UP);
                if (deviation.compareTo(new BigDecimal("0.05")) > 0) {
                    isPriceDeviated = true;
                }
            }

            if (!isExpired && !isPriceDeviated) {
                return summary.getAiAnalysis();
            }
        }

        // 2. Cache Miss: Generate Fresh Analysis with STANDARDIZED (NEUTRAL) Settings
        // Strictly, we should recalculate valuation with NEUTRAL settings for the AI
        // context
        // But to save API calls/Compute, we use the current context if it's close
        // enough,
        // OR we can just rely on the Prompt Instructions to be objective.
        // For strict standardization as per plan:
        // We will allow the AI to see the *current* valuation (which might be
        // Aggressive),
        // BUT we instruct it to be "Objective".
        // Wait, the plan said "Force NEUTRAL".
        // To do that, we need to calculate a 'standardValuation'.
        ValuationDto.Request standardRequest = ValuationDto.Request.builder()
                .userPropensity(ValuationDto.UserPropensity.NEUTRAL)
                .build();
        // Recalculate Base Valuation for AI Baseline
        Response standardValuation = calculateValuation(stockCode, standardRequest);

        String freshAnalysis = generateAiDiagnosis(standardValuation, ratio, bs, annuals, quarters);

        // 3. Save to DB
        if (cachedSummary.isPresent()) {
            StockAiSummary existing = cachedSummary.get();
            existing.updateAnalysis(freshAnalysis, null, currentPrice);
            stockAiSummaryRepository.save(existing);
        } else {
            StockAiSummary newSummary = StockAiSummary.builder()
                    .stockCode(stockCode)
                    .aiAnalysis(freshAnalysis)
                    .referencePrice(currentPrice)
                    .lastModifiedDate(LocalDateTime.now())
                    .createdDate(LocalDateTime.now())
                    .build();
            stockAiSummaryRepository.save(newSummary);
        }

        return freshAnalysis;
    }

    private String calculateQuarterlyYoY(StockFinancialStatement current, StockFinancialStatement past) {
        try {
            if (current.getNetIncome() == null || past.getNetIncome() == null)
                return "";

            double curNet = current.getNetIncome().doubleValue();
            double pastNet = past.getNetIncome().doubleValue();

            if (pastNet == 0)
                return "";

            double growth = (curNet - pastNet) / Math.abs(pastNet) * 100.0;
            return String.format("(YoY %+.1f%%)", growth);
        } catch (Exception e) {
            return "";
        }

    }

    public String fetchBondYieldFromEcos() {
        return kisMacroService.getLatestEcosBondYield();
    }

    @Transactional
    public void clearAiSummaryCache() {
        stockAiSummaryRepository.deleteAll();
    }
}
