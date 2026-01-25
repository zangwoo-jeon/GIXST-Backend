package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.dto.ValuationDto;
import com.AISA.AISA.analysis.dto.ValuationDto.*;
import com.AISA.AISA.analysis.dto.ValuationDto.Summary.AiVerdict;
import com.AISA.AISA.analysis.dto.ValuationDto.Summary.ModelVerdict;
import com.AISA.AISA.analysis.dto.ValuationDto.Summary.Verdicts;
import com.AISA.AISA.analysis.entity.StockAiSummary;
import com.AISA.AISA.analysis.repository.OverseasStockAiSummaryRepository;
import com.AISA.AISA.analysis.repository.StockAiSummaryRepository;
import com.AISA.AISA.kisOverseasStock.dto.OverseasStockCashFlowDto;
import com.AISA.AISA.kisOverseasStock.entity.OverseasStockBalanceSheet;
import com.AISA.AISA.kisOverseasStock.entity.OverseasStockFinancialRatio;
import com.AISA.AISA.kisOverseasStock.entity.OverseasStockFinancialStatement;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockBalanceSheetRepository;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockFinancialRatioRepository;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockFinancialStatementRepository;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockRepository;
import com.AISA.AISA.kisOverseasStock.repository.OverseasStockTradingMultipleRepository;
import com.AISA.AISA.kisOverseasStock.service.KisOverseasStockInformationService;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.kisService.CompetitorAnalysisService;
import com.AISA.AISA.kisStock.kisService.DividendService;
import com.AISA.AISA.kisStock.kisService.KisMacroService;
import com.AISA.AISA.kisStock.kisService.KisStockService;
import com.AISA.AISA.kisStock.dto.InvestorTrend.InvestorTrendDto;
import com.AISA.AISA.kisStock.dto.StockPrice.StockPriceDto;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OverseasValuationService {

    private final KisOverseasStockRepository overseasStockRepository;
    private final KisOverseasStockFinancialRatioRepository financialRatioRepository;
    private final KisOverseasStockBalanceSheetRepository balanceSheetRepository;
    private final KisOverseasStockFinancialStatementRepository financialStatementRepository;
    private final KisOverseasStockInformationService overseasService;
    private final KisStockService kisStockService;
    private final GeminiService geminiService;
    private final OverseasStockAiSummaryRepository overseasStockAiSummaryRepository; // Changed
    private final DividendService dividendService;
    private final CompetitorAnalysisService competitorAnalysisService;
    private final KisMacroService kisMacroService;
    private final OverseasStockTradingMultipleRepository tradingMultipleRepository;

    // --- Valuation Logic (Mirrored from ValuationService but using Overseas
    // Entities) ---

    @Transactional
    public Response calculateValuation(String stockCode, Request request) {
        Stock stock = overseasStockRepository.findByStockCodeAndStockType(stockCode, Stock.StockType.US_STOCK)
                .orElseThrow(() -> new IllegalArgumentException("Overseas Stock not found: " + stockCode));

        // 1. Get Latest Financial Data
        OverseasStockFinancialRatio latestRatio = financialRatioRepository
                .findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");

        // Fetch Historical Data for Average ROE
        List<OverseasStockFinancialRatio> history = financialRatioRepository
                .findTop5ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");

        if (latestRatio == null) {
            return Response.builder()
                    .stockCode(stockCode)
                    .stockName(stock.getStockName())
                    .currentPrice("0")
                    .build();
        }

        // 2. Prepare Data (Real-time Price & Market Cap)
        BigDecimal currentPrice = BigDecimal.ZERO;
        String marketCapStr = "N/A";

        try {
            var priceDetail = overseasService.getPriceDetail(stockCode);
            if (priceDetail != null) {
                if (priceDetail.getMarketCap() != null) {
                    // Market Cap in USD (Raw) -> Format to Billions
                    BigDecimal mc = new BigDecimal(priceDetail.getMarketCap());
                    marketCapStr = String.format("$%.2f B",
                            mc.divide(new BigDecimal("1000000000"), 2, RoundingMode.HALF_UP));
                }
                // We can't easily get raw price from priceDetail (it has string
                // marketCap/listedShares).
                // But we added getCurrentPrice separately.
                // However, doing 2 API calls is wasteful.
                // Let's rely on getCurrentPrice for price, and priceDetail for Market Cap for
                // now (or optimize later).
                // Actually priceDetail implementation in KisOverseasStockInformationService
                // calcs market cap from price.
                // It doesn't expose raw price.
                // So I will keep getCurrentPrice for Price, and use priceDetail for Cap.
            }
        } catch (Exception e) {
            log.warn("Failed to fetch market cap for {}: {}", stockCode, e.getMessage());
        }

        try {
            currentPrice = overseasService.getCurrentPrice(stockCode);
        } catch (Exception e) {
            log.warn("Failed to fetch real-time price for {}: {}", stockCode, e.getMessage());
        }

        // Fallback: If API failed or returned 0, try to infer from latest Ratio (EPS *
        // PER)
        if (currentPrice.compareTo(BigDecimal.ZERO) == 0 && latestRatio != null) {
            if (latestRatio.getPer() != null && latestRatio.getEpsUsd() != null
                    && latestRatio.getPer().compareTo(BigDecimal.ZERO) > 0) {
                currentPrice = latestRatio.getEpsUsd().multiply(latestRatio.getPer());
            } else if (latestRatio.getPbr() != null && latestRatio.getBpsUsd() != null) {
                currentPrice = latestRatio.getBpsUsd().multiply(latestRatio.getPbr());
            }
        }

        // Determine COE (Dynamic US Bond Yield Basis)
        ValuationDto.DiscountRateInfo discountRateInfo = determineUSCOE(request);
        BigDecimal coe = new BigDecimal(discountRateInfo.getValue().replace("%", "")).divide(new BigDecimal(100));

        // 3. Calculate Models (Adapted for Overseas Entities)
        ValuationResult srim = calculateSRIM(latestRatio, history, coe, currentPrice);
        ValuationResult perModel = calculatePERModel(latestRatio, history, currentPrice);
        ValuationResult pbrModel = calculatePBRModel(latestRatio, history, coe, currentPrice);

        // 4. Calculate Band
        ValuationBand band = calculateBand(currentPrice, srim, perModel, pbrModel);

        // 5. Generate Summary
        Summary summary = generateSummary(srim, perModel, pbrModel);

        return Response.builder().stockCode(stockCode).stockName(stock.getStockName())
                .currentPrice(currentPrice.setScale(2, RoundingMode.HALF_UP).toString()) // USD usually 2 decimals
                .marketCap(marketCapStr)
                .targetReturn(discountRateInfo.getValue())
                .discountRate(discountRateInfo)
                .srim(srim).per(perModel).pbr(pbrModel).band(band).summary(summary)
                .build();
    }

    private ValuationDto.DiscountRateInfo determineUSCOE(Request request) {
        // 1. Advanced Custom Input
        if (request != null && request.getExpectedTotalReturn() != null) {
            String value = BigDecimal.valueOf(request.getExpectedTotalReturn()).multiply(new BigDecimal(100))
                    .setScale(1, RoundingMode.HALF_UP) + "%";
            return ValuationDto.DiscountRateInfo.builder()
                    .profile("CUSTOM")
                    .value(value)
                    .basis("User defined custom override")
                    .build();
        }

        // 2. Fetch US 10Y Treasury Yield
        BigDecimal us10y = null;
        try {
            us10y = kisMacroService.getLatestBondYield(com.AISA.AISA.kisStock.enums.BondYield.US_10Y);
        } catch (Exception e) {
            log.warn("Failed to fetch US 10Y Yield: {}", e.getMessage());
        }

        // If fetch fails, fallback to 4.5% (approx)
        if (us10y == null)
            us10y = new BigDecimal("4.5");

        // 3. Apply Spread based on Propensity (US Investment Grade Spread)
        // Neutral: US 10Y + 2.0% (Approx BBB Spread)
        // Conservative: + 3.0%
        // Aggressive: + 1.0%

        BigDecimal spread = new BigDecimal("2.0");
        String profile = "NEUTRAL";

        if (request != null && request.getUserPropensity() != null) {
            switch (request.getUserPropensity()) {
                case CONSERVATIVE:
                    spread = new BigDecimal("3.0");
                    profile = "CONSERVATIVE";
                    break;
                case AGGRESSIVE:
                    spread = new BigDecimal("1.0");
                    profile = "AGGRESSIVE";
                    break;
                default:
                    break;
            }
        }

        BigDecimal finalRate = us10y.add(spread);

        return ValuationDto.DiscountRateInfo.builder()
                .profile(profile)
                .value(finalRate.setScale(1, RoundingMode.HALF_UP) + "%")
                .basis("US 10Y (" + us10y + "%) + Spread (" + spread + "%)")
                .source("MARKET_RATE_ADJUSTED")
                .note("Based on US 10Y Treasury Yield + Corporate Risk Premium")
                .build();
    }

    // --- Private Calculation Methods (Copy-Paste-Adapt from ValuationService) ---
    // Adapting StockFinancialRatio -> OverseasStockFinancialRatio
    // Adapting EPS -> epsUsd, ROE -> roe, etc.

    private ValuationResult calculateSRIM(OverseasStockFinancialRatio latest, List<OverseasStockFinancialRatio> history,
            BigDecimal coe, BigDecimal currentPrice) {
        BigDecimal roe = calculateWeightedAverageROE(history);
        String roeType = (history.size() > 1) ? history.size() + "Y_AVG" : "LATEST";

        // Safety: Cap ROE at COE if Loss (EPS < 0)
        if (latest.getEpsUsd() != null && latest.getEpsUsd().compareTo(BigDecimal.ZERO) <= 0) {
            BigDecimal coePercent = coe.multiply(new BigDecimal("100"));
            if (roe.compareTo(coePercent) > 0)
                roe = coePercent;
        }

        BigDecimal bps = latest.getBpsUsd(); // USD
        if (bps == null || roe == null || bps.compareTo(BigDecimal.ZERO) <= 0)
            return emptyResult("데이터 부족 또는 자본 잠식", "N/A");

        BigDecimal targetPrice = calculateSrimPrice(bps, roe, coe, BigDecimal.ZERO);

        BigDecimal roeDecimal = roe.divide(new BigDecimal(100), 4, RoundingMode.HALF_UP);
        boolean isSrimNegative = targetPrice.compareTo(BigDecimal.ZERO) <= 0 || roeDecimal.compareTo(coe) < 0;

        if (isSrimNegative) {
            return ValuationResult.builder().price("0").gapRate("0").verdict("N/A").description("S-RIM (ROE < COE)")
                    .available(false).reason("Value Destruction").build();
        }

        return buildResult(targetPrice, currentPrice, "S-RIM (초과이익모델, g=0%)", true, "Normal", roeType);
    }

    private BigDecimal calculateWeightedAverageROE(List<OverseasStockFinancialRatio> history) {
        if (history == null || history.isEmpty())
            return BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal weights = BigDecimal.ZERO;
        int maxWeight = history.size();
        for (int i = 0; i < history.size(); i++) {
            BigDecimal roe = history.get(i).getRoe();
            if (roe != null) {
                BigDecimal w = new BigDecimal(maxWeight - i);
                total = total.add(roe.multiply(w));
                weights = weights.add(w);
            }
        }
        return (weights.compareTo(BigDecimal.ZERO) == 0) ? BigDecimal.ZERO
                : total.divide(weights, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateSrimPrice(BigDecimal bps, BigDecimal roe, BigDecimal coe, BigDecimal g) {
        BigDecimal roeDecimal = roe.divide(new BigDecimal(100), 4, RoundingMode.HALF_UP);
        BigDecimal excessReturn = bps.multiply(roeDecimal.subtract(coe));
        BigDecimal denominator = coe.subtract(g);
        if (denominator.compareTo(BigDecimal.ZERO) <= 0)
            return bps;
        return bps.add(excessReturn.divide(denominator, 2, RoundingMode.HALF_UP));
    }

    private ValuationResult calculatePERModel(OverseasStockFinancialRatio ratio,
            List<OverseasStockFinancialRatio> history, BigDecimal currentPrice) {
        BigDecimal eps = ratio.getEpsUsd();
        if (eps == null || eps.compareTo(BigDecimal.ZERO) <= 0) {
            return emptyResult("적자 기업 (EPS <= 0)", "Negative EPS");
        }

        // Hist Avg PER
        BigDecimal sumPer = BigDecimal.ZERO;
        int count = 0;
        for (var h : history) {
            if (h.getPer() != null && h.getPer().compareTo(BigDecimal.ZERO) > 0) {
                sumPer = sumPer.add(h.getPer());
                count++;
            }
        }
        BigDecimal avgPer = (count > 0) ? sumPer.divide(new BigDecimal(count), 2, RoundingMode.HALF_UP)
                : new BigDecimal("15"); // US Market Avg approx 15-20

        BigDecimal targetPrice = eps.multiply(avgPer);
        return buildResult(targetPrice, currentPrice, "PER " + avgPer + "배 (과거평균) 적용", true, "Normal", null);
    }

    private ValuationResult calculatePBRModel(OverseasStockFinancialRatio ratio,
            List<OverseasStockFinancialRatio> history, BigDecimal coe, BigDecimal currentPrice) {
        BigDecimal bps = ratio.getBpsUsd();
        if (bps == null || bps.compareTo(BigDecimal.ZERO) <= 0) {
            return emptyResult("자본 잠식", "Negative Equity");
        }

        // Hist Avg PBR
        BigDecimal sumPbr = BigDecimal.ZERO;
        int count = 0;
        for (var h : history) {
            if (h.getPbr() != null && h.getPbr().compareTo(BigDecimal.ZERO) > 0) {
                sumPbr = sumPbr.add(h.getPbr());
                count++;
            }
        }
        BigDecimal avgPbr = (count > 0) ? sumPbr.divide(new BigDecimal(count), 2, RoundingMode.HALF_UP)
                : new BigDecimal("2.0"); // US Market Higher PBR

        BigDecimal targetPrice = bps.multiply(avgPbr);
        return buildResult(targetPrice, currentPrice, "PBR " + avgPbr + "배 (과거평균) 적용", true, "Normal", null);
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

        return ValuationResult.builder()
                .price(targetPrice.setScale(2, RoundingMode.HALF_UP).toString())
                .gapRate(gap.setScale(2, RoundingMode.HALF_UP).toString())
                .verdict(verdict).description(desc).available(true).roeType(roeType).build();
    }

    private ValuationResult emptyResult(String desc, String reason) {
        return ValuationResult.builder().price("0").gapRate("0").verdict("N/A").description(desc).available(false)
                .reason(reason).build();
    }

    private ValuationBand calculateBand(BigDecimal currentPrice,
            ValuationResult srim,
            ValuationResult per,
            ValuationResult pbr) {

        Map<String, Double> baseWeights = new HashMap<>();
        // Default weights (Similar to Domestic, can be tuned for US)
        baseWeights.put("srim", 0.5);
        baseWeights.put("per", 0.3);
        baseWeights.put("pbr", 0.2);

        // Collect valid prices and corresponding weights
        List<BigDecimal> prices = new java.util.ArrayList<>();
        BigDecimal weightedSum = BigDecimal.ZERO;
        double validWeightSum = 0.0;

        // Final Normalized Weights map for response
        Map<String, Double> finalWeights = new HashMap<>();
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

        BigDecimal min = prices.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal max = prices.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);

        // Gap Rate uses Weighted Average Target Price vs Current Price?
        // Or uses Band Mid?
        // Domestic Logic uses `avg` (Weighted Average) for Gap Rate in Band? No wait.
        // Domestic GapRate in Band: ((cur - mid) / mid)? No, let's check.
        // Domestic Logic: gap = avg.subtract(currentPrice).divide(...) -> No, actually:
        // Wait, calculateBand in ValuationService:
        // BigDecimal avg = weightedSum;
        // BigDecimal gap = avg.subtract(currentPrice)...
        // Yes, it uses weighted average target price.

        BigDecimal avg = weightedSum;
        BigDecimal gap = BigDecimal.ZERO;
        if (currentPrice.compareTo(BigDecimal.ZERO) > 0) {
            // In ValuationDto.ValuationBand, gapRate usually means "How much upside to
            // target?"
            // But existing logic in ValuationService seems to use: (Target - Current) /
            // Current.
            // Wait, Implementation plan says: gapRate: 밴드(중간값) 대비 괴리율?
            // Actually let's stick to ValuationService logic: (WeightedTarget - Current) /
            // Current
            gap = avg.subtract(currentPrice).divide(currentPrice, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal(100));
        }

        // Position % & Status
        String position = "0.0";
        String status = "WITHIN_BAND";

        // Single Model Case
        if (prices.size() == 1) {
            status = "SINGLE_MODEL";
            if (finalWeights.get("pbr") > 0.9)
                status += " (PBR)";
            else if (finalWeights.get("per") > 0.9)
                status += " (PER)";
            else if (finalWeights.get("srim") > 0.9)
                status += " (S-RIM)";

            position = null;
        } else if (max.compareTo(min) > 0) {
            BigDecimal range = max.subtract(min);
            // Position: (Current - Min) / (Max - Min) * 100
            BigDecimal posVal = currentPrice.subtract(min).divide(range, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal(100));
            position = posVal.setScale(1, RoundingMode.HALF_UP).toString();

            if (posVal.compareTo(BigDecimal.ZERO) < 0) {
                status = "BELOW_BAND";
            } else if (posVal.compareTo(new BigDecimal(100)) > 0) {
                status = "ABOVE_BAND";
            }
        } else {
            position = "50.0";
        }

        return ValuationBand.builder()
                .minPrice(min.setScale(2, RoundingMode.HALF_UP).toString())
                .maxPrice(max.setScale(2, RoundingMode.HALF_UP).toString())
                .currentPrice(currentPrice.setScale(2, RoundingMode.HALF_UP).toString())
                .gapRate(gap.setScale(2, RoundingMode.HALF_UP).toString())
                .position(position)
                .status(status)
                .weights(finalWeights)
                .build();
    }

    private boolean isValidPrice(String priceStr) {
        try {
            if (priceStr == null)
                return false;
            double d = Double.parseDouble(priceStr);
            return d > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private Summary generateSummary(ValuationResult srim, ValuationResult per, ValuationResult pbr) {
        return Summary.builder().overallVerdict("HOLD").confidence("LOW").keyInsight("데이터 기반 분석").build();
    }

    // --- AI Report Generation (Core Task) ---

    @Transactional
    public Response calculateValuationWithAi(String stockCode, Request request) {
        Response baseValuation = calculateValuation(stockCode, request);
        String aiAnalysis = getValuationAnalysis(stockCode, baseValuation);

        return buildResponseWithAi(baseValuation, aiAnalysis);
    }

    @Transactional
    public Response getStandardizedValuationReport(String stockCode, boolean refresh) {
        Request request = Request.builder()
                .userPropensity(UserPropensity.NEUTRAL)
                .forceRefresh(refresh)
                .build();
        return calculateValuationWithAi(stockCode, request);
    }

    @Transactional
    public String getValuationAnalysis(String stockCode, Response val) {
        BigDecimal currentPrice = new BigDecimal(val.getCurrentPrice());

        // 1. Check Cache
        Optional<com.AISA.AISA.analysis.entity.OverseasStockAiSummary> cachedSummary = overseasStockAiSummaryRepository
                .findByStockCode(stockCode);
        if (cachedSummary.isPresent()) {
            com.AISA.AISA.analysis.entity.OverseasStockAiSummary summary = cachedSummary.get();
            // Cache Policy: 1 Month (720 hours) or Significant Price Deviation
            boolean isExpired = summary.isExpired(720);
            boolean isPriceDeviated = false;

            if (summary.getReferencePrice() != null && summary.getReferencePrice().compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal refPrice = summary.getReferencePrice();
                BigDecimal diff = currentPrice.subtract(refPrice).abs();
                BigDecimal deviation = diff.divide(refPrice, 4, RoundingMode.HALF_UP);
                if (deviation.compareTo(new BigDecimal("0.05")) > 0) { // 5% deviation
                    isPriceDeviated = true;
                }
            }

            if (summary.getValuationAnalysis() != null && !isExpired && !isPriceDeviated) {
                return summary.getValuationAnalysis();
            }
        }

        // 2. Generate
        String analysis = generateValuationAnalysisText(val);

        // 3. Save Cache
        // Parse for Display Fields
        AiResponseJson aiJson = parseAiResponse(analysis);

        // Simplified Logic for Display Verdict (Reuse logic or simple mapping)
        String displayVerdict = "HOLD"; // Default
        String displayLabel = "관망";

        if (val.getSummary() != null && val.getSummary().getOverallVerdict() != null) {
            displayVerdict = val.getSummary().getOverallVerdict();
            if ("BUY".equals(displayVerdict))
                displayLabel = "매수";
            else if ("SELL".equals(displayVerdict))
                displayLabel = "매도";
        }

        String displaySummary = aiJson.getBeginnerVerdict() != null ? aiJson.getBeginnerVerdict().getSummarySentence()
                : "데이터 분석 중...";
        String displayRisk = aiJson.getAiVerdict() != null && aiJson.getAiVerdict().getRiskLevel() != null
                ? aiJson.getAiVerdict().getRiskLevel().name()
                : "MEDIUM";

        if (cachedSummary.isPresent()) {
            com.AISA.AISA.analysis.entity.OverseasStockAiSummary existing = cachedSummary.get();
            existing.updateValuationAnalysis(analysis, currentPrice, displayVerdict, displayLabel, displaySummary,
                    displayRisk, 0, 0); // Supply scores 0 for now
            overseasStockAiSummaryRepository.save(existing);
        } else {
            com.AISA.AISA.analysis.entity.OverseasStockAiSummary newSummary = com.AISA.AISA.analysis.entity.OverseasStockAiSummary
                    .builder()
                    .stockCode(stockCode)
                    .valuationAnalysis(analysis)
                    .referencePrice(currentPrice)
                    .displayVerdict(displayVerdict)
                    .displayLabel(displayLabel)
                    .displaySummary(displaySummary)
                    .displayRisk(displayRisk)
                    .lastModifiedDate(LocalDateTime.now())
                    .createdDate(LocalDateTime.now())
                    .build();
            overseasStockAiSummaryRepository.save(newSummary);
        }

        return analysis;
    }

    private String generateValuationAnalysisText(Response val) {
        StringBuilder prompt = new StringBuilder();
        String stockCode = val.getStockCode();

        // Fetch Data for Prompt
        OverseasStockFinancialRatio latestRatio = financialRatioRepository
                .findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
        List<OverseasStockCashFlowDto> returns = overseasService.getShareholderReturnInfo(stockCode);

        prompt.append("너는 '미국 주식 투자 전략가'이다. 월가(Wall St.)의 시각으로 분석하라.\n");
        prompt.append(String.format("종목: %s (%s)\n", val.getStockName(), stockCode));
        prompt.append(String.format("현재가: $%s\n", val.getCurrentPrice()));

        if (latestRatio != null) {
            prompt.append("\n[펀더멘털 지표]\n");
            prompt.append(String.format("- PER: %.2f, PBR: %.2f, ROE: %.2f%%\n", latestRatio.getPer(),
                    latestRatio.getPbr(), latestRatio.getRoe()));
            prompt.append(String.format("- EPS: $%.2f\n", latestRatio.getEpsUsd()));
        }

        if (!returns.isEmpty()) {
            prompt.append("\n[주주환원율 데이터 (Shareholder Yield) - 핵심 지표]\n");
            int limit = Math.min(returns.size(), 3);
            for (int i = 0; i < limit; i++) {
                var r = returns.get(i);
                String rateStr = (r.getShareholderReturnRate() != null) ? r.getShareholderReturnRate() + "%" : "N/A";
                // Formatting large numbers? Keep simplistic for USD big decimals or just raw
                // string
                prompt.append(String.format("- %s: 총 환원율 %s (자사주매입 $%s + 배당 $%s)\n",
                        r.getStacYymm(), rateStr, r.getRepurchaseOfCapitalStock(), r.getCashDividendsPaid()));

                if (r.getShareholderReturnRate() != null
                        && r.getShareholderReturnRate().compareTo(BigDecimal.ZERO) < 0) {
                    prompt.append("  (참고: 적자 상태에서의 환원으로, 마이너스 수익률로 표기됨)\n");
                }
            }
        }

        prompt.append("\n[전략가 미션]\n");
        prompt.append("1. **주주환원 집중 분석**: 단순 배당률보다 '총 주주환원율(자사주 매입 포함)'을 최우선으로 평가하라.\n");
        prompt.append("2. **적자 기업 해석**: 만약 환원율이 음수(적자)라면, '무리한 배당'인지 '일시적 실적 악화 방어'인지 논평하라.\n");
        prompt.append("3. **밸류에이션**: S-RIM 및 PER 밴드 결과를 참고하여 진입 가격대를 제시하라.\n");
        prompt.append("4. **초보자 가이드**: '자사주 매입'이 왜 주가에 좋은지 쉽게 설명하며 매수 의견을 제시하라.\n");

        prompt.append("\n[출력 포맷 (JSON)]\n");
        prompt.append("```json\n");
        prompt.append("{\n");
        prompt.append("  \"keyInsight\": \"30자 이내의 핵심 한줄 요약 (가장 중요)\",\n");
        prompt.append("  \"aiVerdict\": {\n");
        prompt.append("    \"stance\": \"[BUY, HOLD, SELL]\",\n");
        prompt.append("    \"timing\": \"[EARLY, MID, LATE, UNCERTAIN]\",\n");
        prompt.append("    \"riskLevel\": \"[LOW, MEDIUM, HIGH]\",\n");
        prompt.append("    \"guidance\": \"종합 의견 (상세)\"\n");
        prompt.append("  },\n");
        prompt.append("  \"beginnerVerdict\": {\n");
        prompt.append("    \"summarySentence\": \"초보자용 한줄 요약 (친근한 말투)\"\n");
        prompt.append("  },\n");
        prompt.append("  \"catalysts\": [\"상승 재료 1\", \"상승 재료 2 (구체적으로)\"],\n");
        prompt.append("  \"risks\": [\"리스크 1\", \"리스크 2 (구체적으로)\"]\n");
        prompt.append("}\n");
        prompt.append("```\n");

        return geminiService.generateAdvice(prompt.toString());
    }

    private Response buildResponseWithAi(Response response, String aiAnalysis) {
        AiResponseJson aiJson = parseAiResponse(aiAnalysis);

        // Map AiVerdict to Summary.Verdicts
        Summary.Verdicts verdicts = null;
        if (aiJson.getAiVerdict() != null) {
            verdicts = Summary.Verdicts.builder()
                    .aiVerdict(aiJson.getAiVerdict())
                    .build();
        }

        // Determine Overall Verdict (Logic: Model + AI)
        // For now, use AI Stance if available, else Logic
        String overallVerdict = "HOLD";
        if (aiJson.getAiVerdict() != null && aiJson.getAiVerdict().getStance() != null) {
            overallVerdict = aiJson.getAiVerdict().getStance().name();
        }

        Summary.Display display = null;
        String risk = "MEDIUM";
        if (aiJson.getAiVerdict() != null && aiJson.getAiVerdict().getRiskLevel() != null) {
            risk = aiJson.getAiVerdict().getRiskLevel().name();
        }

        String summaryText = "데이터 분석 중...";
        if (aiJson.getBeginnerVerdict() != null && aiJson.getBeginnerVerdict().getSummarySentence() != null) {
            summaryText = aiJson.getBeginnerVerdict().getSummarySentence();
        }

        display = Summary.Display.builder()
                .verdict(overallVerdict)
                .verdictLabel(overallVerdict.equals("BUY") || overallVerdict.equals("ACCUMULATE") ? "매수"
                        : overallVerdict.equals("SELL") || overallVerdict.equals("REDUCE") ? "매도" : "관망")
                .summary(summaryText)
                .risk(risk)
                .build();

        Summary newSummary = Summary.builder()
                .overallVerdict(overallVerdict)
                .confidence("HIGH") // AI Analysis Done
                .keyInsight((aiJson.getKeyInsight() != null && !aiJson.getKeyInsight().isEmpty())
                        ? aiJson.getKeyInsight()
                        : (aiJson.getAiVerdict() != null ? aiJson.getAiVerdict().getGuidance() : "AI 분석 완료"))
                .verdicts(verdicts)
                .beginnerVerdict(aiJson.getBeginnerVerdict())
                .display(display)
                .build();

        // Populate AnalysisDetails from AI
        ValuationDto.OverseasAnalysisDetails details = ValuationDto.OverseasAnalysisDetails.builder()
                .investmentTerm("6-12 Months")
                .upsidePotential(response.getSrim() != null ? response.getSrim().getGapRate() + "%" : "N/A")
                .catalysts(aiJson.getCatalysts())
                .risks(aiJson.getRisks())
                .build();

        // [New Logic] Fetch PEG, EV/EBITDA, Shareholder Yield
        try {
            // 1. Yahoo Finance Metrics (PEG, EV/EBITDA)
            var multiplesOpt = tradingMultipleRepository.findByStockCode(response.getStockCode());
            if (multiplesOpt.isPresent()) {
                var m = multiplesOpt.get();
                if (m.getPegRatio() != null) {
                    details.setPegRatio(String.format("%.2f", m.getPegRatio()));
                }
                if (m.getEvEbitda() != null) {
                    details.setEvEbitda(String.format("%.2fx", m.getEvEbitda()));
                }
            }

            // 2. Shareholder Yield (Buyback + Dividend) / MarketCap
            List<OverseasStockCashFlowDto> returns = overseasService.getShareholderReturnInfo(response.getStockCode());
            String marketCapStr = response.getMarketCap(); // e.g., "$123.45 B"

            if (!returns.isEmpty() && marketCapStr != null && !marketCapStr.equals("N/A")) {
                // Simplified calculation for latest annual yield
                // Actually `getShareholderReturnInfo` returns DTOs with pre-calculated
                // `shareholderReturnRate` (yield)
                // Check OverseasStockCashFlow entity logic: (Buyback + Div) / NetIncome ?? No,
                // usually it's yield over Market Cap.
                // Let's check `OverseasStockCashFlow` entity definition again.
                // It says: shareholderReturnRate // ( (자사주매입 + 배당금) / 순이익 * 100 ) <- This is
                // Payout Ratio, NOT Yield!
                // Wait, User said "Shareholder Yield = Dividend Yield + Buyback Yield".
                // That means (Div + Buyback) / Market Cap.
                // The current `shareholderReturnRate` in `OverseasStockCashFlow` seems to be
                // Total Payout Ratio (vs Net Income), NOT Yield (vs Market Cap).
                // We need to calculate Yield manually here using (Div + Buyback) / Market Cap.

                // Reuse `marketCap` from response (derived from priceDetail)
                // $1965.11 B -> 1965110000000
                BigDecimal mktCap = parseMarketCapToBigDecimal(marketCapStr);

                var latest = returns.get(0); // Latest Annual
                BigDecimal buyback = latest.getRepurchaseOfCapitalStock(); // Million USD
                BigDecimal div = latest.getCashDividendsPaid(); // Million USD

                if (mktCap.compareTo(BigDecimal.ZERO) > 0 && buyback != null && div != null) {
                    BigDecimal totalReturn = buyback.add(div); // Already in Raw Units
                    BigDecimal yield = totalReturn.divide(mktCap, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal(100));

                    String detailStr = String.format("%.2f%% (Buyback $%.1fB + Div $%.1fB)",
                            yield,
                            buyback.divide(new BigDecimal("1000000000"), 1, RoundingMode.HALF_UP),
                            div.divide(new BigDecimal("1000000000"), 1, RoundingMode.HALF_UP));

                    details.setShareholderYield(detailStr);
                }
            }

            // 3. Downside Risk (Low Band Price vs Current Price)
            if (response.getBand() != null && response.getBand().getMinPrice() != null) {
                BigDecimal minPrice = new BigDecimal(response.getBand().getMinPrice());
                BigDecimal curPrice = new BigDecimal(response.getCurrentPrice());
                if (curPrice.compareTo(BigDecimal.ZERO) > 0 && minPrice.compareTo(BigDecimal.ZERO) > 0) {
                    if (curPrice.compareTo(minPrice) > 0) {
                        BigDecimal downsideRiskVal = minPrice.subtract(curPrice)
                                .divide(curPrice, 4, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal(100));
                        details.setDownsideRisk(String.format("%.2f%%", downsideRiskVal));
                    } else {
                        details.setDownsideRisk("Low Risk (Below Band)");
                    }
                }
            }

        } catch (Exception e) {
            log.warn("Failed to populate advanced metrics for {}: {}", response.getStockCode(), e.getMessage());
        }

        return response.toBuilder()
                .summary(newSummary)
                .overseasAnalysisDetails(details)
                .build();
    }

    private BigDecimal parseMarketCapToBigDecimal(String marketCapStr) {
        // Format: "$1965.40 B"
        try {
            String clean = marketCapStr.replace("$", "").replace(" B", "").trim();
            BigDecimal val = new BigDecimal(clean);
            return val.multiply(new BigDecimal("1000000000")); // Billion
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    private static class AiResponseJson {
        private String keyInsight;
        private AiVerdict aiVerdict;
        private ValuationDto.Summary.BeginnerVerdict beginnerVerdict;
        private List<String> catalysts;
        private List<String> risks;
    }

    private AiResponseJson parseAiResponse(String jsonText) {
        try {
            int start = jsonText.indexOf("{");
            int end = jsonText.lastIndexOf("}");
            if (start >= 0) {
                String json = jsonText.substring(start, end + 1);
                ObjectMapper mapper = new ObjectMapper();
                mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                return mapper.readValue(json, AiResponseJson.class);
            }
        } catch (Exception e) {
            log.error("Failed to parse AI JSON: {}", e.getMessage());
        }
        return new AiResponseJson();
    }
}
