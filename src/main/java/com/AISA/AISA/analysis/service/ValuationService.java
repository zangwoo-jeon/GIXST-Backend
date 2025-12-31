package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.dto.ValuationDto;
import com.AISA.AISA.analysis.dto.ValuationDto.Response;
import com.AISA.AISA.analysis.dto.ValuationDto.Summary;
import com.AISA.AISA.analysis.dto.ValuationDto.ValuationBand;
import com.AISA.AISA.analysis.dto.ValuationDto.ValuationResult;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialRatio;
import com.AISA.AISA.kisStock.kisService.KisStockService;
import com.AISA.AISA.kisStock.dto.StockPrice.StockPriceDto;
import com.AISA.AISA.kisStock.repository.StockFinancialRatioRepository;
import com.AISA.AISA.kisStock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ValuationService {

    private final StockRepository stockRepository;
    private final StockFinancialRatioRepository stockFinancialRatioRepository;
    private final KisStockService kisStockService;

    // 기본 요구 수익률 (Fallback)
    private static final BigDecimal DEFAULT_TARGET_RETURN = new BigDecimal("0.09"); // 9%

    @Transactional(readOnly = true)
    public Response calculateValuation(String stockCode, BigDecimal targetReturn) {
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
        BigDecimal currentPrice = getCurrentPrice(stockCode, latestRatio);

        // If inference failed, fallback to stored ratio (ONLY if real-time failed)
        if (currentPrice.compareTo(BigDecimal.ZERO) == 0) {
            if (latestRatio.getEps() != null && latestRatio.getPer() != null) {
                currentPrice = latestRatio.getEps().multiply(latestRatio.getPer());
                log.warn("Real-time price fetch failed for {}. Fallback to EPS * PER calculation: {}", stockCode,
                        currentPrice);
            }
        }

        BigDecimal coe = (targetReturn != null && targetReturn.compareTo(BigDecimal.ZERO) > 0) ? targetReturn
                : DEFAULT_TARGET_RETURN;

        // 3. Calculate Models
        ValuationResult srim = calculateSRIM(latestRatio, history, coe, currentPrice);
        ValuationResult perModel = calculatePERModel(latestRatio, history, currentPrice);
        ValuationResult pbrModel = calculatePBRModel(latestRatio, history, coe, currentPrice);

        // 4. Calculate Band (Weighted)
        ValuationBand band = calculateBand(currentPrice, srim, perModel, pbrModel);

        // 5. Generate Summary (Interpretation)
        Summary summary = generateSummary(srim, perModel, pbrModel);

        return Response.builder()
                .stockCode(stockCode)
                .stockName(stock.getStockName())
                .currentPrice(currentPrice.setScale(0, RoundingMode.HALF_UP).toString())
                .targetReturn(coe.multiply(new BigDecimal(100)).setScale(1, RoundingMode.HALF_UP) + "%")
                .srim(srim)
                .per(perModel)
                .pbr(pbrModel)
                .band(band)
                .summary(summary)
                .build();
    }

    private BigDecimal getCurrentPrice(String stockCode, StockFinancialRatio ratio) {
        try {
            StockPriceDto priceDto = kisStockService.getStockPrice(stockCode);
            if (priceDto != null) {
                String rawPrice = priceDto.getStockPrice();
                if (rawPrice != null && !rawPrice.trim().isEmpty()) {
                    // Remove commas if present
                    return new BigDecimal(rawPrice.replace(",", "").trim());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch real-time price using KisStockService for {}: {}", stockCode, e.getMessage());
        }
        return BigDecimal.ZERO;
    }

    // S-RIM: BPS + (Review ROE - COE) * BPS / COE
    private ValuationResult calculateSRIM(StockFinancialRatio latest, List<StockFinancialRatio> history,
            BigDecimal coe,
            BigDecimal currentPrice) {

        // Calculate Weighted Average ROE
        BigDecimal roe = calculateWeightedAverageROE(history);
        String roeType = (history.size() > 1) ? history.size() + "Y_AVG" : "LATEST";

        BigDecimal bps = latest.getBps();

        if (bps == null || roe == null)
            return emptyResult("데이터 부족", "N/A");

        // S-RIM Formula: Value = BPS + (BPS * (ROE - COE)) / (COE - g)
        BigDecimal targetPrice = calculateSrimPrice(bps, roe, coe, BigDecimal.ZERO); // Default g=0

        // Scenario Analysis (Growth Rates)
        Map<String, ValuationResult> scenarios = new java.util.HashMap<>();

        // Conservative: g = 0%
        scenarios.put("CONSERVATIVE", buildScenarioResult(targetPrice, currentPrice, "S-RIM (g=0%)"));

        // Base: g = 2% (Constraint: g < COE)
        BigDecimal gBase = new BigDecimal("0.02");
        if (coe.subtract(gBase).compareTo(new BigDecimal("0.01")) > 0) {
            BigDecimal priceBase = calculateSrimPrice(bps, roe, coe, gBase);
            scenarios.put("BASE", buildScenarioResult(priceBase, currentPrice, "S-RIM (g=2%)"));
        } else {
            // Fallback if COE is too low
            scenarios.put("BASE", buildScenarioResult(targetPrice, currentPrice, "S-RIM (g=0% due to low COE)"));
        }

        // Optimistic: g = 4%
        BigDecimal gOpt = new BigDecimal("0.04");
        if (coe.subtract(gOpt).compareTo(new BigDecimal("0.01")) > 0) {
            BigDecimal priceOpt = calculateSrimPrice(bps, roe, coe, gOpt);
            scenarios.put("OPTIMISTIC", buildScenarioResult(priceOpt, currentPrice, "S-RIM (g=4%)"));
        } else {
            scenarios.put("OPTIMISTIC", scenarios.get("BASE"));
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
        if (eps == null || eps.compareTo(BigDecimal.ZERO) <= 0)
            return emptyResult("EPS 적자 전환", "EPS Negative");

        // 1. Calculate Historical PER Stats
        Stats perStats = calculateStats(history, "PER");

        // Default to 10 if no history
        BigDecimal basePer = (perStats.avg.compareTo(BigDecimal.ZERO) > 0) ? perStats.avg : new BigDecimal("10");

        // 2. Scenario Analysis
        Map<String, ValuationResult> scenarios = new java.util.HashMap<>();

        // Conservative: Min PER (or 0.8 * Avg)
        BigDecimal consPer = (perStats.min.compareTo(BigDecimal.ZERO) > 0) ? perStats.min
                : basePer.multiply(new BigDecimal("0.8"));
        scenarios.put("CONSERVATIVE",
                buildScenarioResult(eps.multiply(consPer), currentPrice, "Historical Min PER " + consPer));

        // Base: Avg PER
        scenarios.put("BASE",
                buildScenarioResult(eps.multiply(basePer), currentPrice, "Historical Avg PER " + basePer));

        // Optimistic: Max PER (or 1.2 * Avg)
        BigDecimal optPer = (perStats.max.compareTo(BigDecimal.ZERO) > 0) ? perStats.max
                : basePer.multiply(new BigDecimal("1.2"));
        scenarios.put("OPTIMISTIC",
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
                // Filter outliers (e.g. > 100 or < 0.1 temporarily?)
                // Let's allow all for now but handle extremums later if needed
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
        if (bps == null)
            return emptyResult("BPS 데이터 부족", "N/A");

        // 1. Calculate Historical PBR Stats
        Stats pbrStats = calculateStats(history, "PBR");

        // Default to 1.0 (Liquidation Value) or 0.6 if very low? Let's use 1.0 default
        BigDecimal basePbr = (pbrStats.avg.compareTo(BigDecimal.ZERO) > 0) ? pbrStats.avg : new BigDecimal("1.0");

        // 2. Scenario Analysis
        Map<String, ValuationResult> scenarios = new java.util.HashMap<>();

        // Conservative: Min PBR
        BigDecimal consPbr = (pbrStats.min.compareTo(BigDecimal.ZERO) > 0) ? pbrStats.min
                : basePbr.multiply(new BigDecimal("0.8"));
        scenarios.put("CONSERVATIVE",
                buildScenarioResult(bps.multiply(consPbr), currentPrice, "Historical Min PBR " + consPbr));

        // Base: Avg PBR
        scenarios.put("BASE",
                buildScenarioResult(bps.multiply(basePbr), currentPrice, "Historical Avg PBR " + basePbr));

        // Optimistic: Max PBR
        BigDecimal optPbr = (pbrStats.max.compareTo(BigDecimal.ZERO) > 0) ? pbrStats.max
                : basePbr.multiply(new BigDecimal("1.2"));
        scenarios.put("OPTIMISTIC",
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

        java.util.Map<String, Double> weights = new java.util.HashMap<>();
        weights.put("srim", 0.5);
        weights.put("per", 0.3);
        weights.put("pbr", 0.2);

        // Collect valid prices and apply weights
        List<BigDecimal> prices = new java.util.ArrayList<>();
        BigDecimal weightedSum = BigDecimal.ZERO;
        double totalWeight = 0.0;

        if (srim.isAvailable() && isValidPrice(srim.getPrice())) {
            BigDecimal p = new BigDecimal(srim.getPrice());
            prices.add(p);
            weightedSum = weightedSum.add(p.multiply(new BigDecimal(weights.get("srim"))));
            totalWeight += weights.get("srim");
        }
        if (per.isAvailable() && isValidPrice(per.getPrice())) {
            BigDecimal p = new BigDecimal(per.getPrice());
            prices.add(p);
            weightedSum = weightedSum.add(p.multiply(new BigDecimal(weights.get("per"))));
            totalWeight += weights.get("per");
        }
        if (pbr.isAvailable() && isValidPrice(pbr.getPrice())) {
            BigDecimal p = new BigDecimal(pbr.getPrice());
            prices.add(p);
            weightedSum = weightedSum.add(p.multiply(new BigDecimal(weights.get("pbr"))));
            totalWeight += weights.get("pbr");
        }

        if (prices.isEmpty() || totalWeight == 0)
            return ValuationBand.builder().build();

        BigDecimal min = prices.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal max = prices.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);

        // Weighted Average
        BigDecimal avg = weightedSum.divide(new BigDecimal(totalWeight), 0, RoundingMode.HALF_UP);

        BigDecimal gap = BigDecimal.ZERO;
        if (currentPrice.compareTo(BigDecimal.ZERO) > 0) {
            gap = avg.subtract(currentPrice).divide(currentPrice, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal(100));
        }

        // Position %
        BigDecimal position = BigDecimal.ZERO;
        if (max.compareTo(min) > 0) {
            position = currentPrice.subtract(min).divide(max.subtract(min), 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal(100));
            if (position.compareTo(BigDecimal.ZERO) < 0)
                position = BigDecimal.ZERO;
            if (position.compareTo(new BigDecimal(100)) > 0)
                position = new BigDecimal(100);
        } else {
            position = (currentPrice.compareTo(max) > 0) ? new BigDecimal(100) : BigDecimal.ZERO;
        }

        return ValuationBand.builder()
                .minPrice(min.toString())
                .maxPrice(max.toString())
                .currentPrice(currentPrice.toString())
                .gapRate(gap.setScale(2, RoundingMode.HALF_UP).toString())
                .position(position.setScale(0, RoundingMode.HALF_UP).toString())
                .weights(weights)
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

        String logic = "S-RIM은 최근 5년 가중 평균 ROE를 사용하였으며, PER/PBR 모델은 과거 5년 역사적 밴드(Min/Avg/Max)를 기준으로 시나리오를 분석했습니다.";

        return Summary.builder()
                .overallVerdict(overallVerdict)
                .confidence(confidence)
                .keyInsight(insight)
                .valuationLogic(logic)
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

}
