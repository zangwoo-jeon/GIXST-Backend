package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.dto.ValuationBaseDto.ValuationResult;
import com.AISA.AISA.analysis.dto.ValuationBaseDto.Summary;
import com.AISA.AISA.analysis.dto.ValuationBaseDto.Stance;
import com.AISA.AISA.analysis.dto.ValuationBaseDto.UserPropensity;
import com.AISA.AISA.analysis.dto.ValuationBaseDto.TechnicalIndicators;
import com.AISA.AISA.analysis.dto.ValuationBaseDto.ValuationBand;
import com.AISA.AISA.analysis.dto.ValuationBaseDto.DiscountRateInfo;
import com.AISA.AISA.analysis.dto.OverseasValuationDto.*;
import com.AISA.AISA.analysis.repository.OverseasStockAiSummaryRepository;
import com.AISA.AISA.kisOverseasStock.dto.OverseasStockCashFlowDto;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockFinancialRatioRepository;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockFinancialStatementRepository;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockRepository;
import com.AISA.AISA.kisOverseasStock.repository.OverseasStockTradingMultipleRepository;
import com.AISA.AISA.kisOverseasStock.service.KisOverseasStockInformationService;
import com.AISA.AISA.kisOverseasStock.entity.OverseasStockFinancialRatio;
import com.AISA.AISA.kisOverseasStock.entity.OverseasStockFinancialStatement;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.kisService.KisMacroService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OverseasValuationService {

    private final KisOverseasStockRepository overseasStockRepository;
    private final KisOverseasStockFinancialRatioRepository financialRatioRepository;
    private final KisOverseasStockFinancialStatementRepository financialStatementRepository;
    private final KisOverseasStockInformationService overseasService;
    private final KisMacroService kisMacroService;
    private final GeminiService geminiService;
    private final OverseasStockAiSummaryRepository overseasStockAiSummaryRepository;
    private final OverseasStockTradingMultipleRepository tradingMultipleRepository;
    private final com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockDailyDataRepository dailyDataRepository;

    private enum StockStyle {
        GROWTH("성장주 (Growth)", "높은 매출 성장과 미래 가치 기대"),
        BLEND("혼합형 (Blend)", "성장성과 수익성의 균형 (배당+성장)"),
        VALUE("가치주 (Value)", "자산 가치 및 배당 중심의 안정성");

        @SuppressWarnings("unused")
        private final String label;
        @SuppressWarnings("unused")
        private final String description;

        StockStyle(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }

    @Getter
    @Setter
    @Builder
    private static class StyleScores {
        private double growthScore;
        private double valueScore;
        private String confidence;
        private StockStyle style;
    }

    @Transactional
    public Response calculateValuation(String stockCode, Request request) {
        Stock stock = overseasStockRepository.findByStockCode(stockCode)
                .filter(s -> s.getStockType() == Stock.StockType.US_STOCK || s.getStockType() == Stock.StockType.US_ETF)
                .orElseThrow(() -> new IllegalArgumentException("Overseas Stock not found: " + stockCode));

        OverseasStockFinancialRatio latestRatio = financialRatioRepository
                .findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");

        List<OverseasStockFinancialRatio> history = financialRatioRepository
                .findTop5ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");

        if (latestRatio == null) {
            return Response.builder()
                    .stockCode(stockCode)
                    .stockName(stock.getStockName())
                    .currentPrice("0")
                    .build();
        }

        BigDecimal currentPrice = BigDecimal.ZERO;
        String marketCapStr = "N/A";

        try {
            var priceDetail = overseasService.getPriceDetail(stockCode);
            if (priceDetail != null && priceDetail.getMarketCap() != null) {
                BigDecimal mc = new BigDecimal(priceDetail.getMarketCap());
                marketCapStr = String.format("$%.2f B",
                        mc.divide(new BigDecimal("1000000000"), 2, RoundingMode.HALF_UP));
            }
        } catch (Exception e) {
            log.warn("Failed to fetch market cap for {}: {}", stockCode, e.getMessage());
        }

        try {
            currentPrice = overseasService.getCurrentPrice(stockCode);
        } catch (Exception e) {
            log.warn("Failed to fetch real-time price for {}: {}", stockCode, e.getMessage());
        }

        if (currentPrice.compareTo(BigDecimal.ZERO) == 0 && latestRatio != null) {
            if (latestRatio.getPer() != null && latestRatio.getEpsUsd() != null
                    && latestRatio.getPer().compareTo(BigDecimal.ZERO) > 0) {
                currentPrice = latestRatio.getEpsUsd().multiply(latestRatio.getPer());
            } else if (latestRatio.getPbr() != null && latestRatio.getBpsUsd() != null) {
                currentPrice = latestRatio.getBpsUsd().multiply(latestRatio.getPbr());
            }
        }

        DiscountRateInfo discountRateInfo = determineUSCOE(request);
        BigDecimal coe = new BigDecimal(discountRateInfo.getValue().replace("%", "")).divide(new BigDecimal(100));

        ValuationResult srim = calculateSRIM(latestRatio, history, coe, currentPrice);
        ValuationResult perModel = calculatePERModel(latestRatio, history, currentPrice);
        ValuationResult pbrModel = calculatePBRModel(latestRatio, history, coe, currentPrice);

        ValuationBand band = calculateBand(currentPrice, srim, perModel, pbrModel, stockCode, stock.getStockName());

        List<com.AISA.AISA.kisOverseasStock.entity.OverseasStockDailyData> dailyData = dailyDataRepository
                .findTop60ByStockOrderByDateDesc(stock);
        // Stage 1: Basic Scores & Tier 1 Data Prep
        TechnicalIndicators tech = calculateTechnicalIndicators(dailyData);
        double trendScore = calculateTrendScore(tech, currentPrice);

        Response prepResponse = Response.builder()
                .stockCode(stockCode).stockName(stock.getStockName())
                .currentPrice(currentPrice.toString()).marketCap(marketCapStr)
                .build();

        StyleScores styleScores = calculateStyleScores(stockCode, prepResponse);
        prepResponse.setCurrentPosition(CurrentPosition.builder()
                .stockCode(stockCode).stockName(stock.getStockName())
                .currentPrice(currentPrice.toString()).marketCap(marketCapStr)
                .trendScore(trendScore)
                .growthScore(styleScores.getGrowthScore())
                .valueScore(styleScores.getValueScore())
                .styleConfidence(styleScores.getConfidence())
                .stockStyle(styleScores.getStyle().name())
                .build());

        // Stage 2: Individual Models & Consensus Governance
        ValuationResult pegModel = calculatePEGModel(stockCode, latestRatio.getEpsUsd(), currentPrice);
        ValuationResult adjPsrModel = calculateAdjustedPSRModel(stockCode, latestRatio.getPsr(), currentPrice);
        ValuationResult enhPbrModel = calculateEnhancedPBRModel(latestRatio, currentPrice);

        ValuationResult finalSrim = srim; // Keep SRIM separate (no overwrite)
        ValuationResult finalPer = adjPsrModel.isAvailable() ? adjPsrModel : perModel;
        ValuationResult finalPbr = enhPbrModel.isAvailable() ? enhPbrModel : pbrModel;

        calculateValuationConsensus(styleScores.getStyle(), finalSrim, pegModel,
                finalPer,
                finalPbr, currentPrice);

        // [P0] Valuation Score Normalization (Friend's Consensus)
        // Logic: Normalize weights based on AVAILABLE models only. Maximize Robustness.
        double wSrim = 5.0, wPer = 3.0, wPbr = 2.0; // Base Weights
        double totalWeight = 0.0;
        double weightedScoreSum = 0.0;

        if (finalSrim.isAvailable()) {
            totalWeight += wSrim;
            weightedScoreSum += getScore(finalSrim) * wSrim;
        }
        if (finalPer.isAvailable()) {
            // [Constraint] Cap PER weight at 70% if SRIM is missing to prevent domination
            if (!finalSrim.isAvailable())
                wPer = Math.min(wPer, 7.0); // Adjust base if needed, or normalize later
            // Actually, if SRIM missing, Total=3+2=5. PER is 3/5 = 60%. Safe.
            totalWeight += wPer;
            weightedScoreSum += getScore(finalPer) * wPer;
        }
        if (finalPbr.isAvailable()) {
            totalWeight += wPbr;
            weightedScoreSum += getScore(finalPbr) * wPbr;
        }

        double scoreBaseRaw = (totalWeight > 0) ? (weightedScoreSum / totalWeight) : 0.0; // Normalized -1 to 1
        // Map -1..1 to 0..100. -1 -> 0, 0 -> 50, 1 -> 100
        double earlyValuationScore = Math.max(0, Math.min(100, 50.0 + (scoreBaseRaw * 50.0)));
        prepResponse.getCurrentPosition().setValuationScore(earlyValuationScore);

        double probScoreProxyFinal = (trendScore * 0.5) + (earlyValuationScore * 0.5);
        MarketPhase phaseFinal = determineMarketPhase(trendScore, earlyValuationScore, probScoreProxyFinal);
        prepResponse.getCurrentPosition().setMarketPhase(phaseFinal.name());
        prepResponse.getCurrentPosition().setMarketPhaseDescription(phaseFinal.desc);

        // Stage 3: Strategy & Tier 1 Freezing
        ActionStrategy strategy = ActionStrategy.builder().build();
        calculatePriceStrategyForNewDto(
                prepResponse.toBuilder().srim(finalSrim).per(finalPer).pbr(finalPbr).band(band).build(), strategy,
                null, tech);

        // Pre-AI Narrative Resolution (Standardizing S-RIM label etc.)
        resolveNarrativeInconsistency(prepResponse.toBuilder().srim(finalSrim).actionStrategy(strategy).build(),
                strategy, null, tech);

        prepResponse = prepResponse.toBuilder()
                .srim(finalSrim).per(finalPer).pbr(finalPbr).band(band)
                .actionStrategy(strategy)
                .build();

        // Stage 4: Attribution & Drivers
        Summary.ConfidenceAttribution attribution = calculateConfidenceAttribution(prepResponse, tech);

        // [V6.1] Integrated DB Caching Logic
        String aiAnalysis = getValuationAnalysis(stockCode, prepResponse, request);

        // Stage 6: Final Assembly
        Response response = prepResponse.toBuilder()
                .targetReturn(discountRateInfo.getValue())
                .discountRate(discountRateInfo)
                .build();

        // Update overall summary with attribution
        Summary summary = Summary.builder()
                .confidence(styleScores.getConfidence())
                .attribution(attribution)
                .build();
        response.setSummary(summary);

        return buildResponseWithAi(response, aiAnalysis, stockCode, stock, latestRatio, tech);
    }

    private DiscountRateInfo determineUSCOE(Request request) {
        if (request != null && request.getExpectedTotalReturn() != null) {
            String value = BigDecimal.valueOf(request.getExpectedTotalReturn()).multiply(new BigDecimal(100))
                    .setScale(1, RoundingMode.HALF_UP) + "%";
            return DiscountRateInfo.builder()
                    .profile("CUSTOM")
                    .value(value)
                    .basis("User defined custom override")
                    .build();
        }

        BigDecimal us10y = null;
        try {
            us10y = kisMacroService.getLatestBondYield(com.AISA.AISA.kisStock.enums.BondYield.US_10Y);
        } catch (Exception e) {
        }

        if (us10y == null)
            us10y = new BigDecimal("4.5");
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
        return DiscountRateInfo.builder()
                .profile(profile)
                .value(finalRate.setScale(1, RoundingMode.HALF_UP) + "%")
                .basis("US 10Y (" + us10y + "%) + Spread (" + spread + "%)")
                .source("MARKET_RATE_ADJUSTED")
                .note("Based on US 10Y Treasury Yield + Corporate Risk Premium")
                .build();
    }

    private ValuationResult calculateSRIM(OverseasStockFinancialRatio latest, List<OverseasStockFinancialRatio> history,
            BigDecimal coe, BigDecimal currentPrice) {
        BigDecimal roe = calculateWeightedAverageROE(history);
        String roeType = (history.size() > 1) ? history.size() + "Y_AVG" : "LATEST";

        BigDecimal growth = calculateRevenueGrowth(latest.getStockCode());
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

        BigDecimal coePercent = coe.multiply(new BigDecimal("100"));
        if (growth.compareTo(new BigDecimal("10.0")) >= 0 && roe.compareTo(coePercent) < 0) {
            w = new BigDecimal("0.85");
            tier += " (ROE < COE 패널티 적용)";
            modelType = "가치 파괴적 성장 경계 모델";
        }

        String desc = String.format("[%s] 매출 성장률 %.1f%%를 반영한 %s (w=%.2f) 결과", tier, growth, modelType, w);
        if (latest.getEpsUsd() != null && latest.getEpsUsd().compareTo(BigDecimal.ZERO) <= 0) {
            if (roe.compareTo(coePercent) > 0)
                roe = coePercent;
        }

        BigDecimal bps = latest.getBpsUsd();
        if (bps == null || roe == null || bps.compareTo(BigDecimal.ZERO) <= 0)
            return emptyResult("데이터 부족 또는 자본 잠식", "N/A");

        BigDecimal targetPrice = calculateSrimPriceWithW(bps, roe, coe, w);
        BigDecimal roeDecimal = roe.divide(new BigDecimal(100), 4, RoundingMode.HALF_UP);
        boolean isSrimNegative = targetPrice.compareTo(BigDecimal.ZERO) <= 0 || roeDecimal.compareTo(coe) < 0;

        if (isSrimNegative) {
            String floorDesc = "모델상 산출된 내재가치 하한선(Asset Floor) 대비 현재 가격이 프리미엄 구간에 있음 (ROE < COE)";
            BigDecimal gap = bps.subtract(currentPrice).divide(currentPrice, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal(100));
            return ValuationResult.builder()
                    .price(bps.setScale(2, RoundingMode.HALF_UP).toString())
                    .gapRate(gap.setScale(2, RoundingMode.HALF_UP).toString())
                    .verdict("N/A") // Or OVERVALUED, but N/A fits the 'floor' concept better for now? Or
                                    // OVERVALUED. User said "Premium over Asset Floor".
                    // Let's keep verdict "N/A" for consistency with "Price vs Floor" concept, or
                    // handle verdict.
                    // Actually, "N/A" might cause the Consensus to ignore it? No, consensus checks
                    // `isAvailable()`.
                    // But `getScore` checks verdict. "OVERVALUED" -> -1.
                    // If ROE < COE, it IS overvalued vs floor.
                    .verdict("OVERVALUED")
                    .description(floorDesc)
                    .available(true) // Now available for consensus!
                    .reason("Premium over Asset Floor")
                    .build();
        }

        return buildResult(targetPrice, currentPrice, desc, true, "Normal", roeType);
    }

    private BigDecimal calculateRevenueGrowth(String stockCode) {
        try {
            List<OverseasStockFinancialStatement> annuals = financialStatementRepository
                    .findTop2ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
            if (annuals.size() == 2) {
                BigDecimal latestRev = annuals.get(0).getTotalRevenue();
                BigDecimal prevRev = annuals.get(1).getTotalRevenue();
                if (prevRev != null && prevRev.compareTo(BigDecimal.ZERO) > 0 && latestRev != null) {
                    return latestRev.subtract(prevRev).divide(prevRev, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal(100));
                }
            }
        } catch (Exception e) {
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal calculateSrimPriceWithW(BigDecimal bps, BigDecimal roe, BigDecimal coe, BigDecimal w) {
        BigDecimal roeDecimal = roe.divide(new BigDecimal(100), 4, RoundingMode.HALF_UP);
        BigDecimal excessReturn = bps.multiply(roeDecimal.subtract(coe));
        BigDecimal denominator = BigDecimal.ONE.add(coe).subtract(w);
        if (denominator.compareTo(BigDecimal.ZERO) <= 0)
            return bps;
        BigDecimal premium = excessReturn.multiply(w).divide(denominator, 2, RoundingMode.HALF_UP);
        return bps.add(premium);
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

    private ValuationResult calculatePERModel(OverseasStockFinancialRatio ratio,
            List<OverseasStockFinancialRatio> history, BigDecimal currentPrice) {
        BigDecimal eps = ratio.getEpsUsd();
        if (eps == null || eps.compareTo(BigDecimal.ZERO) <= 0)
            return emptyResult("적자 기업 (EPS <= 0)", "Negative EPS");
        BigDecimal sumPer = BigDecimal.ZERO;
        int count = 0;
        for (var h : history) {
            if (h.getPer() != null && h.getPer().compareTo(BigDecimal.ZERO) > 0) {
                sumPer = sumPer.add(h.getPer());
                count++;
            }
        }
        BigDecimal avgPer = (count > 0) ? sumPer.divide(new BigDecimal(count), 2, RoundingMode.HALF_UP)
                : new BigDecimal("15");

        // [Historical Trap Protection] Cap average PER to avoid bubble-era distortions
        if (avgPer.compareTo(new BigDecimal("80")) > 0) {
            avgPer = new BigDecimal("80");
        }

        BigDecimal targetPrice = eps.multiply(avgPer);
        return buildResult(targetPrice, currentPrice, "PER " + avgPer + "배 (과격한 과거평균 보정) 적용", true, "Normal",
                "PER (Hist. Avg)");
    }

    private ValuationResult calculatePBRModel(OverseasStockFinancialRatio ratio,
            List<OverseasStockFinancialRatio> history, BigDecimal coe, BigDecimal currentPrice) {
        BigDecimal bps = ratio.getBpsUsd();
        if (bps == null || bps.compareTo(BigDecimal.ZERO) <= 0)
            return emptyResult("자본 잠식", "Negative Equity");
        BigDecimal sumPbr = BigDecimal.ZERO;
        int count = 0;
        for (var h : history) {
            if (h.getPbr() != null && h.getPbr().compareTo(BigDecimal.ZERO) > 0) {
                sumPbr = sumPbr.add(h.getPbr());
                count++;
            }
        }
        BigDecimal avgPbr = (count > 0) ? sumPbr.divide(new BigDecimal(count), 2, RoundingMode.HALF_UP)
                : new BigDecimal("2.0");

        // [Historical Trap Protection] Cap average PBR to avoid bubble-era distortions
        if (avgPbr.compareTo(new BigDecimal("20")) > 0) {
            avgPbr = new BigDecimal("20");
        }

        BigDecimal targetPrice = bps.multiply(avgPbr);
        return buildResult(targetPrice, currentPrice, "PBR " + avgPbr + "배 (과격한 과거평균 보정) 적용", true, "Normal",
                "PBR (Hist. Avg)");
    }

    private ValuationResult buildResult(BigDecimal targetPrice, BigDecimal currentPrice, String desc, boolean available,
            String reason, String modelName) {
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
                .verdict(verdict).description(desc).available(true).modelName(modelName).roeType(modelName).build();
    }

    private ValuationResult emptyResult(String desc, String reason) {
        return ValuationResult.builder().price("0").gapRate("0").verdict("N/A").description(desc).available(false)
                .reason(reason).build();
    }

    private ValuationBand calculateBand(BigDecimal currentPrice, ValuationResult srim, ValuationResult per,
            ValuationResult pbr, String stockCode, String stockName) {
        Map<String, Double> dynamicWeights = determineIndustryWeights(stockCode, stockName);
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

        if (srim.isAvailable() && isValidPrice(srim.getPrice())) {
            double nw = dynamicWeights.get("srim") / validWeightSum;
            finalWeights.put("srim", Math.round(nw * 1000d) / 1000d);
        }
        if (per.isAvailable() && isValidPrice(per.getPrice())) {
            double nw = dynamicWeights.get("per") / validWeightSum;
            finalWeights.put("per", Math.round(nw * 1000d) / 1000d);
        }
        if (pbr.isAvailable() && isValidPrice(pbr.getPrice())) {
            double nw = dynamicWeights.get("pbr") / validWeightSum;
            finalWeights.put("pbr", Math.round(nw * 1000d) / 1000d);
        }

        BigDecimal min = prices.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal max = prices.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);

        String status = "WITHIN_BAND";
        String position = "50.0";
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

        return ValuationBand.builder().minPrice(min.setScale(2, RoundingMode.HALF_UP).toString())
                .maxPrice(max.setScale(2, RoundingMode.HALF_UP).toString())
                .currentPrice(currentPrice.setScale(2, RoundingMode.HALF_UP).toString())
                .position(position).status(status)
                .weights(finalWeights).build();
    }

    private Map<String, Double> determineIndustryWeights(String stockCode, String stockName) {
        Map<String, Double> weights = new HashMap<>();
        String name = stockName.toUpperCase();
        boolean isIntangibleBase = name.contains("SOFT") || name.contains("BIO") || name.contains("MED")
                || name.contains("TECH") ||
                name.contains("CLOUD") || name.contains("PLATFORM") || name.contains("ALPHABET")
                || name.contains("GOOGLE") ||
                name.contains("APPLE") || name.contains("MICROSOFT") || name.contains("NVIDIA")
                || name.contains("META");

        if (isIntangibleBase) {
            weights.put("srim", 0.12);
            weights.put("per", 0.58);
            weights.put("pbr", 0.3);
        } else {
            weights.put("srim", 0.5);
            weights.put("per", 0.3);
            weights.put("pbr", 0.2);
        }
        return weights;
    }

    private boolean isValidPrice(String priceStr) {
        try {
            return priceStr != null && Double.parseDouble(priceStr) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private int getScore(ValuationResult result) {
        if (result == null || !result.isAvailable()) {
            if (result != null && "Value Destruction".equals(result.getReason()))
                return -2; // Strong penalty for ROE < COE
            return 0;
        }
        String verdict = result.getVerdict();
        if ("UNDERVALUED".equals(verdict))
            return 1;
        if ("OVERVALUED".equals(verdict))
            return -1;
        return 0;
    }

    @Transactional
    public Response calculateValuationWithAi(String stockCode, Request request) {
        return calculateValuation(stockCode, request);
    }

    @Transactional
    @Cacheable(value = "overseasValuationReport", key = "#stockCode", condition = "!#refresh")
    @CacheEvict(value = "overseasValuationReport", key = "#stockCode", condition = "#refresh")
    public Response getStandardizedValuationReport(String stockCode, boolean refresh) {
        Request request = Request.builder().userPropensity(UserPropensity.NEUTRAL).forceRefresh(refresh).build();
        return calculateValuationWithAi(stockCode, request);
    }

    @Transactional
    @CacheEvict(value = "overseasValuationReport", allEntries = true)
    public void clearOverseasAiSummaryCache() {
        overseasStockAiSummaryRepository.deleteAll();
    }

    @Transactional
    public String getValuationAnalysis(String stockCode, Response val, Request request) {
        BigDecimal currentPrice = new BigDecimal(val.getCurrentPrice().replace(",", ""));
        boolean force = (request != null && request.isForceRefresh());

        Optional<com.AISA.AISA.analysis.entity.OverseasStockAiSummary> cachedSummary = overseasStockAiSummaryRepository
                .findByStockCode(stockCode);

        if (force && cachedSummary.isPresent()) {
            overseasStockAiSummaryRepository.delete(cachedSummary.get());
            cachedSummary = Optional.empty();
        }

        if (!force && cachedSummary.isPresent() && !val.getSummary().getOverallVerdict().equals("N/A")) {
            var summary = cachedSummary.get();
            if (!summary.isExpired(720)) {
                BigDecimal refPrice = summary.getReferencePrice();
                if (refPrice != null && refPrice.compareTo(BigDecimal.ZERO) != 0) {
                    BigDecimal deviation = currentPrice.subtract(refPrice).abs().divide(refPrice, 4,
                            RoundingMode.HALF_UP);
                    if (deviation.compareTo(new BigDecimal("0.05")) <= 0)
                        return summary.getValuationAnalysis();
                }
            }
        }

        TechnicalIndicators tech = (val.getOverseasAnalysisDetails() != null)
                ? val.getOverseasAnalysisDetails().getTechnicalIndicators()
                : new TechnicalIndicators();

        ActionStrategy tempStrategy = new ActionStrategy();
        calculatePriceStrategyForNewDto(val, tempStrategy,
                val.getProbabilityAndRisk() != null ? val.getProbabilityAndRisk().getProbabilities() : null, tech);

        String res = tempStrategy.getResistancePrice();
        String sup = tempStrategy.getSupportPrice();
        String sT = tempStrategy.getTargetPrices().getShortTerm();
        String mT = tempStrategy.getTargetPrices().getMidTerm();
        String lT = tempStrategy.getTargetPrices().getLongTerm();

        ValuationResult consensusResult = calculateValuationConsensus(
                StockStyle.valueOf(val.getCurrentPosition().getStockStyle()), val.getSrim(),
                emptyResult("PEG", "Not Configured"), val.getPer(), val.getPbr(),
                currentPrice);
        Summary.ConfidenceAttribution attribution = calculateConfidenceAttribution(val, tech);
        String analysis = generateValuationAnalysisText(val, consensusResult, res, sup, sT, mT, lT, tech, attribution);
        AiResponseJson aiJson = parseAiResponse(analysis);
        String displayVerdict = val.getSummary().getOverallVerdict();
        String displayLabel = mapVerdictToLabel(displayVerdict,
                aiJson.getAiVerdict() != null ? aiJson.getAiVerdict().getStance() : Stance.HOLD);
        String displaySummary = aiJson.getActionPlan() != null ? aiJson.getActionPlan() : "전략적 대응이 필요합니다.";
        String displayRisk = (aiJson.getAiVerdict() != null && aiJson.getAiVerdict().getRiskLevel() != null)
                ? aiJson.getAiVerdict().getRiskLevel().name()
                : "MEDIUM";

        if (cachedSummary.isPresent()) {
            var existing = cachedSummary.get();
            existing.updateValuationAnalysis(analysis, currentPrice, displayVerdict, displayLabel, displaySummary,
                    displayRisk, 0, 0);
            overseasStockAiSummaryRepository.save(existing);
        } else {
            overseasStockAiSummaryRepository
                    .save(com.AISA.AISA.analysis.entity.OverseasStockAiSummary.builder().stockCode(stockCode)
                            .valuationAnalysis(analysis).referencePrice(currentPrice).displayVerdict(displayVerdict)
                            .displayLabel(displayLabel).displaySummary(displaySummary).displayRisk(displayRisk)
                            .lastModifiedDate(LocalDateTime.now()).createdDate(LocalDateTime.now()).build());
        }
        return analysis;
    }

    private String generateValuationAnalysisText(Response val, ValuationResult consensus, String resistance,
            String support,
            String shortTerm, String midTerm, String longTerm, TechnicalIndicators tech,
            Summary.ConfidenceAttribution attribution) {
        StringBuilder prompt = new StringBuilder();
        String stockCode = val.getStockCode();
        OverseasStockFinancialRatio latestRatio = financialRatioRepository
                .findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");

        prompt.append("너는 기관 투자자용 리서치를 작성하는 '수석 퀀트 전략가(Lead Quant Strategist)'이다.\n");
        prompt.append("모든 분석은 제공된 [Frozen Data]를 기반으로 하며, 임의로 수치를 생성하거나 수정하는 것은 엄격히 금지된다.\n");

        prompt.append(String.format("\n### [Frozen Data - SSoT]\n"));
        prompt.append(String.format("- 종목: %s (%s), 현재가: $%s\n", val.getStockName(), stockCode, val.getCurrentPrice()));

        if (latestRatio != null) {
            prompt.append(String.format("- 펀더멘털: PER %.2f, PBR %.2f, ROE %.2f%%, EPS $%.2f\n",
                    latestRatio.getPer(), latestRatio.getPbr(), latestRatio.getRoe(), latestRatio.getEpsUsd()));
        }

        if (val.getCurrentPosition() != null) {
            prompt.append(String.format("- 시스템 판정: Style(%s), Phase(%s - %s)\n",
                    val.getCurrentPosition().getStockStyle(), val.getCurrentPosition().getMarketPhase(),
                    val.getCurrentPosition().getMarketPhaseDescription()));
            prompt.append(String.format("- 점수: Growth(%.1f), Value(%.1f), ValuationScore(%.1f)\n",
                    val.getCurrentPosition().getGrowthScore(), val.getCurrentPosition().getValueScore(),
                    val.getCurrentPosition().getValuationScore()));
        }

        prompt.append("\n### [Valuation Consensus & Model Data]\n");
        prompt.append(String.format("- **통합 적정가 (Consensus)**: $%s (%s)\n", consensus.getPrice(),
                consensus.getDescription()));
        prompt.append(
                String.format("- S-RIM/PEG: $%s (%s)\n", val.getSrim().getPrice(), val.getSrim().getDescription()));
        prompt.append(String.format("- PSR/PER: $%s (%s)\n", val.getPer().getPrice(), val.getPer().getDescription()));
        prompt.append(String.format("- PBR-ROE: $%s (%s)\n", val.getPbr().getPrice(), val.getPbr().getDescription()));

        if (val.getBand() != null) {
            prompt.append(String.format("- 밴드 위치: %s%% (Min: $%s, Max: $%s)\n", val.getBand().getPosition(),
                    val.getBand().getMinPrice(), val.getBand().getMaxPrice()));
        }

        prompt.append("\n### [Technical Levels & Strategy]\n");
        prompt.append(String.format("- 지지선(Support): %s, 저항선(Resistance): %s\n", support, resistance));
        prompt.append(String.format("- 목표가: 단기(%s), 중기(%s), 장기(%s)\n", shortTerm, midTerm, longTerm));

        if (tech != null) {
            prompt.append(String.format("- 기술지표: RSI(%.1f), Stochastic(%s), Regime(%s)\n",
                    tech.getRsi(), tech.getStochasticZone(), tech.getMarketRegime()));
        }

        prompt.append("\n### [Confidence Attribution]\n");
        prompt.append(String.format("- 신뢰 지수: 데이터품질(%.1f), 모델합의도(%.1f), 국면안정성(%.1f)\n",
                attribution.getDataQuality(), attribution.getModelAgreement(), attribution.getRegimeStability()));
        prompt.append(String.format("- 핵심 주도 요인: %s\n", attribution.getPrimaryDriver()));

        // [Divergence Warning Injection]
        if ("HIGH".equals(val.getActionStrategy().getDivergenceRiskIndex())
                || "EXTREME".equals(val.getActionStrategy().getDivergenceRiskIndex())) {
            String phase = val.getCurrentPosition().getMarketPhase();
            double rsi = tech != null ? tech.getRsi() : 50.0;
            if (("IDEAL_BULL".equals(phase) || "LATE_STAGE_RALLY".equals(phase)) && rsi < 40) {
                prompt.append(
                        "\n⚠️ **[CRITICAL WARNING: Bearish Divergence Detected]**\n- 현재 주가 추세(Phase)는 상승세이나, 모멘텀(RSI/Regime)은 급격히 약화되고 있음을 반드시 언급하라.\n- 이는 상승 에너지 고갈 및 추세 반전 가능성을 내포하므로, 보수적인 관점에서의 비중 관리가 필요함을 시사하라.\n");
            }
        }

        prompt.append("\n### [분석 미션: 실전 매매 가이드]\n");
        prompt.append("너는 단순한 분석가가 아니라, 고객의 수익을 책임지는 '실전 트레이딩 코치'이다.\n");
        prompt.append("애매한 표현(~보입니다, ~판단됩니다)을 지양하고, **확신에 찬 행동 지침(사라, 팔아라, 기다려라)**을 제시하라.\n");
        prompt.append("모든 전문 용어는 반드시 **친숙한 한국어**로 번역하거나 병기하라. (예: Pullback -> 건전한 조정, Resistance -> 저항선)\n");

        prompt.append(String.format(
                "\n[Action Plan Data]\n- 1차 매수(Breakout): %s (저항선 돌파 시)\n- 2차 매수(Dip Buying): %s (지지선 지지 및 반등 확인 시)\n- 목표가(Target): 단기 %s, 중기 %s\n- 손절가(Stop Loss): %s 이탈 시\n",
                resistance, support, shortTerm, midTerm, support));

        prompt.append("\n[작성 구조: 4단계 실전 전략]\n");
        prompt.append("다음 4단계 구조로 `guidance`를 작성하라. **문장은 짧고 명료하게(개조식 스타일)** 작성하라.\n");

        prompt.append("1. **[전술적 행동: 이중 경로 대응]**: 시장의 방향에 따른 대처 방법을 두 가지 시나리오로 나누어 제시하라.\n");
        prompt.append(
                String.format("   - **경로 A (상승 돌파 시)**: 저항선($%s)을 돌파하며 성장이 확인될 때 비중 X%%로 진입하는 전략.\n", resistance));
        prompt.append(String.format("   - **경로 B (하락 눌림 시)**: 지지선($%s)까지 밀렸다가 반등이 확인될 때 비중 Y%%로 진입하는 전략.\n", support));
        prompt.append("   - 목표가와 손절가를 명확한 수치($)로 제시하고, 현재가에서 어떤 경로가 더 유리한지 AI의 '우선 추천'을 명시하라.\n");

        prompt.append("\n2. **[현 위치 진단]**: 현재 주가의 상태를 정의하라.\n");
        prompt.append("   - 밸류에이션: 현재 주가가 저평가/적정/고평가 구간 중 어디인지 진단하라.\n");
        prompt.append(String.format("   - 모멘텀: 현재가($%s) 기준 상승/하락 추세 여부와 과열/과매도 상태를 설명하라.\n", val.getCurrentPrice()));

        prompt.append("\n3. **[판단 근거]**: 위 결론을 내린 구체적인 지표 기반 이유를 설명하라.\n");
        prompt.append("   - 밸류에이션: S-RIM(내재가치 하한), PER/PBR(과거 평균 대비 위치), 패닉/과열 여부를 활용하라.\n");
        prompt.append("   - 펀더멘털 & 모멘텀: PEG Ratio, 주주환원율, RSI 및 Stochastic 지표를 근거로 사용하라.\n");

        prompt.append("\n4. **[시나리오 대응]**: 예상과 다르게 움직일 경우의 즉각적인 행동 지침.\n");
        prompt.append("   - 목표가 도달 시의 익절 분할/전략.\n");
        prompt.append("   - 손절가 붕괴 시의 리스크 관리 및 즉각 청산 지침.\n");

        prompt.append("\n[전략 가이드]\n");
        prompt.append("- 1차 매수(Breakout): 저항선을 상향 돌파하며 상승세가 확인될 때 따라가는 '추세 추종' 전략이다.\n");
        prompt.append("- 2차 매수(Dip Buying): 저항 돌파에 실패하고 지지선까지 밀렸을 때, 지지력을 확인하고 '저점 매수'하는 전략이다.\n");
        prompt.append("- 두 시나리오 중 현재 시장 상황에 가장 적합한 경로를 우선적으로 추천하라.\n");

        prompt.append("\n[필수 지침]\n");
        prompt.append("- **Tone**: 단호하고 직관적인 코칭 톤 (Coaching Tone). 투자의사 결정에 즉각적인 도움을 주어야 한다.\n");
        prompt.append(
                "- **Language**: 'Distribution' -> '물량 떠넘기기', 'Consolidation' -> '기간 조정', 'Retracement' -> '되돌림' 등 투자자가 직관적으로 이해할 수 있는 한국어 표현을 사용하라.\n");

        prompt.append("\n[용어 번역 가이드 (필수)]\n");
        prompt.append("다음 영문 용어가 등장할 경우, 반드시 지정된 한국어 표현을 사용하라 (괄호 안에 원문 병기 가능).\n");
        prompt.append("- LATE_STAGE_RALLY -> 상승 피로감 누적 구간 (Late Stage Rally)\n");
        prompt.append("- RALLY -> 본격 상승 구간 (Rally)\n");
        prompt.append("- IDEAL_BULL -> 이상적인 강세장 (Ideal Bull)\n");
        prompt.append("- RECOVERY -> 회복 국면 (Recovery)\n");
        prompt.append("- BOTTOM_FORMATION -> 바닥 다지기 (Bottom Formation)\n");
        prompt.append("- FALLING_KNIFE -> 떨어지는 칼날 (급락 위험)\n");
        prompt.append("- PANIC_SELL -> 패닉 셀링 (투매)\n");
        prompt.append("- PEAK_DISTRIBUTION -> 고점 분산 구간 (Peak Distribution)\n");
        prompt.append("- BEARISH_DIVERGENCE -> 하락 반전 신호 (Bearish Divergence)\n");
        prompt.append("- BULLISH_DIVERGENCE -> 상승 반전 신호 (Bullish Divergence)\n");
        prompt.append("- VALUATION_CONFLICT -> 적정 주가 불일치 (모델 간 충돌)\n");
        prompt.append("- GROWTH -> 성장주 (Growth Interest)\n");
        prompt.append("- VALUE -> 가치주 (Value Interest)\n");
        prompt.append("- UNCERTAIN -> 방향성 탐색 구간 (Uncertain)\n");
        prompt.append("- STRONG_TREND -> 강한 상승 추세\n");
        prompt.append("- WEAK_TREND -> 추세 약화\n");
        prompt.append(
                "- **PBR-ROE 해석**: PBR-ROE 모델이 'UNDERVALUED'로 나오더라도, 이는 '과거 평균 보정 기준'임을 명시하고, 현재 밸류에이션 부담이 있다면 이를 맹목적 저평가로 해석하지 말라.\n");
        prompt.append(
                "- **S-RIM 해석**: S-RIM 가격이 낮을 경우, '내재가치 하한선(Asset Floor)'임을 명시하고, 현재가가 이를 상회하면 '성장 프리미엄이 반영된 상태'라고 긍정/중립적으로 해석하라 (무조건적 고평가 매도 의견 지양).\n");

        prompt.append("\nJSON format 가이드:\n");
        prompt.append("{\n");
        prompt.append("  \"keyInsight\": \"30자 이내의 결론형 한 줄 요약 (예: 저항선 돌파 전까지 매수 보류하고 관망)\",\n");
        prompt.append("  \"investmentTerm\": \"권장 투자 기간 (예: 1~3개월)\",\n");
        prompt.append("  \"aiVerdict\": {\n");
        prompt.append("    \"stance\": \"BUY/ACCUMULATE/HOLD/REDUCE/SELL\",\n");
        prompt.append("    \"timing\": \"EARLY/MID/LATE\",\n");
        prompt.append("    \"riskLevel\": \"LOW/MEDIUM/HIGH\",\n");
        prompt.append(
                "    \"guidance\": \"1. 전술적 행동: 이중 경로 대응...\\n\\n2. 현 위치 진단...\\n\\n3. 판단 근거...\\n\\n4. 시나리오 대응...\"\n");
        prompt.append("  },\n");
        prompt.append("  \"probabilities\": {\n");
        prompt.append("    \"shortTerm\": \"단기 상승 확률 (예: 40%)\",\n");
        prompt.append("    \"midTerm\": \"중기 확률\",\n");
        prompt.append("    \"longTerm\": \"장기 확률\"\n");
        prompt.append("  },\n");
        prompt.append("  \"timingAction\": \"지금 당장 해야 할 행동 (1문장)\",\n");
        prompt.append("  \"actionPlan\": \"구체적인 진입/청산 가격 포함 전략\",\n");
        prompt.append("  \"probabilityInfo\": \"확률 산출의 근거 (한국어)\",\n");
        prompt.append("  \"catalysts\": [\"호재 (한국어)\"], \"risks\": [\"악재 (한국어)\"]\n");
        prompt.append("}\n");

        return geminiService.generateAdvice(prompt.toString());
    }

    private Response buildResponseWithAi(Response response, String aiAnalysis, String stockCode, Stock stock,
            OverseasStockFinancialRatio latestRatio, TechnicalIndicators tech) {
        AiResponseJson aiJson = parseAiResponse(aiAnalysis);
        String overallVerdict = (aiJson.getAiVerdict() != null && aiJson.getAiVerdict().getStance() != null)
                ? aiJson.getAiVerdict().getStance().name()
                : "HOLD";

        // [V6] Use Pre-calculated Quantitative Data
        Summary currentSummary = response.getSummary();
        double trendScore = response.getCurrentPosition() != null ? response.getCurrentPosition().getTrendScore()
                : 50.0;
        double valuationScore = response.getCurrentPosition() != null
                ? response.getCurrentPosition().getValuationScore()
                : 50.0;

        Summary.Probabilities probs = fillMissingProbabilities(aiJson.getProbabilities(), trendScore, valuationScore,
                overallVerdict);

        Summary updatedSummary = Summary.builder()
                .overallVerdict(overallVerdict)
                .confidence(currentSummary != null ? currentSummary.getConfidence() : "HIGH")
                .attribution(currentSummary != null ? currentSummary.getAttribution() : null)
                .keyInsight(aiJson.getKeyInsight() != null ? aiJson.getKeyInsight() : "AI 전략 분석 리포트")
                .verdicts(Summary.Verdicts.builder().aiVerdict(aiJson.getAiVerdict()).build())
                .probabilities(probs)
                .catalysts(aiJson.getCatalysts())
                .risks(aiJson.getRisks())
                .timingAction(aiJson.getTimingAction() != null ? aiJson.getTimingAction() : "관망 추천")
                .build();

        ProbabilityAndRisk probabilityAndRisk = ProbabilityAndRisk.builder()
                .probabilities(probs).probabilityInfo(aiJson.getProbabilityInfo())
                .build();

        ActionStrategy finalStrategy = response.getActionStrategy().toBuilder()
                .actionPlan(aiJson.getActionPlan())
                .timingAction(aiJson.getTimingAction() != null ? aiJson.getTimingAction() : "관망 추천")
                .build();

        var multiplesOpt = tradingMultipleRepository.findByStockCode(stockCode);
        OverseasAnalysisDetails details = OverseasAnalysisDetails.builder()
                .investmentTerm(aiJson.getInvestmentTerm() != null ? aiJson.getInvestmentTerm() : "3개월 내외")
                .catalysts(aiJson.getCatalysts()).risks(aiJson.getRisks())
                .qualityMetrics(OverseasAnalysisDetails.QualityMetrics.builder()
                        .pegRatio(multiplesOpt.isPresent() && multiplesOpt.get().getPegRatio() != null
                                ? String.format("%.2f", multiplesOpt.get().getPegRatio())
                                : "N/A")
                        .evEbitda(multiplesOpt.isPresent() && multiplesOpt.get().getEvEbitda() != null
                                ? String.format("%.2f", multiplesOpt.get().getEvEbitda())
                                : "N/A")
                        .shareholderReturnRate(calculateShareholderReturnRate(stockCode))
                        .build())
                .technicalIndicators(tech)
                .build();

        return response.toBuilder()
                .summary(updatedSummary)
                .actionStrategy(finalStrategy)
                .probabilityAndRisk(probabilityAndRisk)
                .overseasAnalysisDetails(details)
                .build();
    }

    private void calculatePriceStrategyForNewDto(Response response, ActionStrategy strategy,
            Summary.Probabilities probs, TechnicalIndicators tech) {
        if (response.getBand() == null || response.getBand().getMinPrice() == null)
            return;

        BigDecimal cur = new BigDecimal(response.getCurrentPrice().replace(",", ""));
        double trendScore = response.getCurrentPosition() != null ? response.getCurrentPosition().getTrendScore()
                : 50.0;
        double valuationScore = response.getCurrentPosition() != null
                ? response.getCurrentPosition().getValuationScore()
                : 50.0;
        double upProb = 50.0;
        if (probs != null && probs.getShortTerm() != null) {
            try {
                upProb = Double.parseDouble(probs.getShortTerm().replace("%", ""));
            } catch (Exception e) {
            }
        }

        // Layer 1: Raw Model Values
        BigDecimal srim = response.getSrim().isAvailable() ? new BigDecimal(response.getSrim().getPrice()) : null;
        BigDecimal per = response.getPer().isAvailable() ? new BigDecimal(response.getPer().getPrice()) : null;
        BigDecimal pbr = response.getPbr().isAvailable() ? new BigDecimal(response.getPbr().getPrice()) : null;

        // Layer 2: Continuous Weight Engine
        // TrendWeight = 0.2 + 0.5 * TrendScore/100
        double wTrend = 0.2 + 0.5 * (trendScore / 100.0);
        double wVal = 1.0 - wTrend;

        // Internal Valuation Weighting based on Stock Style
        double wSrim = 0.2, wPer = 0.5, wPbr = 0.3;
        String style = (response.getCurrentPosition() != null && response.getCurrentPosition().getStockStyle() != null)
                ? response.getCurrentPosition().getStockStyle()
                : "BLEND";

        if ("GROWTH".equals(style)) {
            wPer = 0.6;
            wSrim = 0.3;
            wPbr = 0.1;
        } else if ("VALUE".equals(style)) {
            wPbr = 0.6;
            wSrim = 0.3;
            wPer = 0.1;
        } else {
            wPer = 0.4;
            wSrim = 0.3;
            wPbr = 0.3;
        }

        if (srim == null) {
            wPer = 0.7;
            wPbr = 0.3;
            wSrim = 0;
        }

        BigDecimal rawFV = BigDecimal.ZERO;
        double totalVWeight = 0;
        if (srim != null) {
            rawFV = rawFV.add(srim.multiply(BigDecimal.valueOf(wSrim)));
            totalVWeight += wSrim;
        }
        if (per != null) {
            rawFV = rawFV.add(per.multiply(BigDecimal.valueOf(wPer)));
            totalVWeight += wPer;
        }
        if (pbr != null) {
            rawFV = rawFV.add(pbr.multiply(BigDecimal.valueOf(wPbr)));
            totalVWeight += wPbr;
        }

        BigDecimal fairValue = (totalVWeight > 0)
                ? rawFV.divide(BigDecimal.valueOf(totalVWeight), 2, RoundingMode.HALF_UP)
                : cur;

        // Divergence Risk Index calculation
        double rawGap = (fairValue.subtract(cur).doubleValue() / cur.doubleValue()) * 100.0;
        String riskIndex = Math.abs(rawGap) > 80 ? "EXTREME"
                : Math.abs(rawGap) > 50 ? "HIGH" : Math.abs(rawGap) > 25 ? "MEDIUM" : "LOW";

        // [Divergence Logic] Phase-Momentum Conflict Detection
        double rsi = tech != null ? tech.getRsi() : 50.0;
        String regime = tech != null ? tech.getMarketRegime() : "SIDEWAYS";
        MarketPhase mPhase = determineMarketPhase(trendScore, valuationScore, upProb);

        boolean isBearishDivergence = (mPhase == MarketPhase.IDEAL_BULL || mPhase == MarketPhase.LATE_STAGE_RALLY
                || mPhase == MarketPhase.RECOVERY)
                && (rsi < 40 || "WEAK_TREND".equals(regime));

        if (isBearishDivergence) {
            riskIndex = ("LOW".equals(riskIndex) || "MEDIUM".equals(riskIndex)) ? "HIGH" : "EXTREME";
        } else if ((mPhase == MarketPhase.FALLING_KNIFE || mPhase == MarketPhase.BOTTOM_FISHING) && rsi > 55) {
            if ("LOW".equals(riskIndex))
                riskIndex = "MEDIUM"; // Bullish divergence (recovery signal)
        }

        if ((trendScore > 80 && rsi < 40) || (trendScore < 20 && rsi > 60)) {
            riskIndex = "HIGH"; // Extreme sentiment/price divergence
        }
        strategy.setDivergenceRiskIndex(riskIndex);

        // Layer 3: Path Generation (Trading Target)
        // Technical Target Pull (Bullish: +7%, Bearish: -7%)
        double techPullRatio = (trendScore > 50) ? 0.07 : -0.07;
        BigDecimal techTarget = cur.add(cur.multiply(BigDecimal.valueOf(techPullRatio)));

        // Blend Tech and Fundamental using wTrend
        BigDecimal tradingFV = techTarget.multiply(BigDecimal.valueOf(wTrend))
                .add(fairValue.multiply(BigDecimal.valueOf(wVal)))
                .setScale(2, RoundingMode.HALF_UP);

        // Layer 4: Governance Layer - (1) Shock Absorber (Logistic Compression)
        double gapRatio = tradingFV.subtract(cur).doubleValue() / cur.doubleValue();
        double compressedGap = gapRatio / (1.0 + Math.abs(gapRatio));
        BigDecimal adjustedFV = cur.add(cur.multiply(BigDecimal.valueOf(compressedGap))).setScale(2,
                RoundingMode.HALF_UP);

        // Path Ratio based on Market Regime
        double rShort = 0.20, rMid = 0.55, rLong = 0.85;
        String phaseStr = response.getCurrentPosition() != null ? response.getCurrentPosition().getMarketPhase()
                : "UNCERTAIN";
        if ("IDEAL_BULL".equals(phaseStr) || "RECOVERY".equals(phaseStr)) {
            rShort = 0.35;
            rMid = 0.75;
            rLong = 1.0;
        } else if ("FALLING_KNIFE".equals(phaseStr) || ("WEAK_TREND".equals(phaseStr) && cur.compareTo(fairValue) > 0)
                || ("LATE_STAGE_RALLY".equals(phaseStr) && cur.compareTo(fairValue) > 0)) {
            rShort = 0.10;
            rMid = 0.35;
            rLong = 0.65;
        }

        BigDecimal gap = adjustedFV.subtract(cur);
        BigDecimal shortT = cur.add(gap.multiply(BigDecimal.valueOf(rShort))).setScale(2, RoundingMode.HALF_UP);
        BigDecimal midT = cur.add(gap.multiply(BigDecimal.valueOf(rMid))).setScale(2, RoundingMode.HALF_UP);
        BigDecimal longT = cur.add(gap.multiply(BigDecimal.valueOf(rLong))).setScale(2, RoundingMode.HALF_UP);

        // Layer 4: Governance Layer - (2) Soft Guardrail (Stability-based)
        double stability = 50.0;
        if (response.getOverseasAnalysisDetails() != null
                && response.getOverseasAnalysisDetails().getTechnicalIndicators() != null) {
            stability = response.getOverseasAnalysisDetails().getTechnicalIndicators().getRegimeStability();
        }

        if (trendScore >= 70) {
            BigDecimal floor = cur.multiply(BigDecimal.valueOf(0.75 + 0.15 * (stability / 100.0)));
            shortT = shortT.max(floor);
        }

        // Layer 4: Governance Layer - (3) Contradiction Detector & Probability
        // Alignment

        if (upProb > 60.0 && shortT.compareTo(cur) < 0) {
            shortT = cur.multiply(BigDecimal.valueOf(1.02));
        }

        shortT = shortT.max(cur.multiply(BigDecimal.valueOf(1.005)));

        // Layer 4: Governance Layer - (4) Monotonicity Rule
        if (fairValue.compareTo(cur) >= 0) {
            if (shortT.compareTo(midT) > 0)
                midT = shortT.multiply(BigDecimal.valueOf(1.02));
            if (midT.compareTo(longT) > 0)
                longT = midT.multiply(BigDecimal.valueOf(1.05));
        } else {
            if (shortT.compareTo(midT) < 0)
                midT = shortT.multiply(BigDecimal.valueOf(0.98));
            if (midT.compareTo(longT) < 0)
                longT = midT.multiply(BigDecimal.valueOf(0.95));
        }

        // Support Price logic
        BigDecimal theoreticalMin = new BigDecimal(response.getBand().getMinPrice().replace(",", ""));
        BigDecimal support = cur.subtract(cur.subtract(theoreticalMin).multiply(BigDecimal.valueOf(rShort))).setScale(2,
                RoundingMode.HALF_UP);

        if (tech != null && tech.getMovingAverages() != null) {
            var ma = tech.getMovingAverages();
            if (ma.get("MA20") != null) {
                BigDecimal ma20 = new BigDecimal(ma.get("MA20").replaceAll("[^0-9.]", ""));
                // Protect: Don't let support be TOO far from current (max 12% drop for standard
                // support)
                BigDecimal maxDrop = cur.multiply(BigDecimal.valueOf(0.88));
                support = ma20.max(maxDrop);
            }
        }
        support = support.min(cur.multiply(BigDecimal.valueOf(0.99)));
        support = support.setScale(2, RoundingMode.HALF_UP);

        BigDecimal resistancePrice = shortT;
        strategy.setResistancePrice("$" + String.format("%,.2f", resistancePrice));
        strategy.setSupportPrice("$" + String.format("%,.2f", support));

        // [Breakout Scenario] If Trend is not weak (>= 40), set Target Price slightly
        // above Resistance (+1.5%)
        // to suggest a "Breakout & Stabilize" scenario into the next level.
        if (trendScore >= 40) {
            shortT = resistancePrice.multiply(new BigDecimal("1.015")).setScale(2, RoundingMode.HALF_UP);
            // Ensure monotonicity after bump
            if (midT.compareTo(shortT) < 0)
                midT = shortT.multiply(new BigDecimal("1.05")).setScale(2, RoundingMode.HALF_UP);
            if (longT.compareTo(midT) < 0)
                longT = midT.multiply(new BigDecimal("1.05")).setScale(2, RoundingMode.HALF_UP);
        }

        strategy.setTargetPrices(ActionStrategy.TargetPrices.builder()
                .shortTerm("$" + String.format("%,.2f", shortT))
                .midTerm("$" + String.format("%,.2f", midT))
                .longTerm("$" + String.format("%,.2f", longT))
                .build());
    }

    private String calculateShareholderReturnRate(String stockCode) {
        List<OverseasStockCashFlowDto> returnsList = overseasService.getShareholderReturnInfo(stockCode);
        if (returnsList.isEmpty())
            return "N/A";
        OverseasStockCashFlowDto cashFlow = returnsList.get(0);
        BigDecimal rate = cashFlow.getShareholderReturnRate();
        if (rate == null || rate.compareTo(BigDecimal.ZERO) == 0) {
            List<OverseasStockFinancialStatement> stmts = financialStatementRepository
                    .findTop2ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
            if (!stmts.isEmpty()) {
                BigDecimal netIncome = stmts.get(0).getNetIncome();
                if (netIncome != null && netIncome.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal totalReturn = (cashFlow.getRepurchaseOfCapitalStock() != null
                            ? cashFlow.getRepurchaseOfCapitalStock()
                            : BigDecimal.ZERO)
                            .add(cashFlow.getCashDividendsPaid() != null ? cashFlow.getCashDividendsPaid()
                                    : BigDecimal.ZERO);
                    rate = totalReturn.divide(netIncome, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100));
                }
            }
        }
        return rate != null ? String.format("%.2f%%", rate) : "N/A";
    }

    private String mapVerdictToLabel(String modelRating, Stance aiStance) {
        if ("BUY".equals(modelRating))
            return aiStance == Stance.ACCUMULATE ? "분할 매수" : "매수";
        if ("SELL".equals(modelRating))
            return aiStance == Stance.REDUCE ? "비중 축소" : "매도";
        return "관망";
    }

    private TechnicalIndicators calculateTechnicalIndicators(
            List<com.AISA.AISA.kisOverseasStock.entity.OverseasStockDailyData> dailyData) {
        if (dailyData == null || dailyData.size() < 20)
            return null;
        List<BigDecimal> prices = dailyData.stream().map(d -> d.getClosingPrice())
                .collect(java.util.stream.Collectors.toList());
        java.util.Collections.reverse(prices);

        Map<String, String> mas = new HashMap<>();
        if (dailyData.size() >= 20) {
            BigDecimal ma20 = dailyData.stream().limit(20).map(d -> d.getClosingPrice())
                    .reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(20), 2, RoundingMode.HALF_UP);
            mas.put("MA20", String.format("$%.2f", ma20));
        }
        if (dailyData.size() >= 60) {
            BigDecimal ma60 = dailyData.stream().limit(60).map(d -> d.getClosingPrice())
                    .reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
            mas.put("MA60", String.format("$%.2f", ma60));
        }

        double rsi = calculateRsi(prices, 14);
        double[] stochastic = calculateStochastic(dailyData, 14, 3);
        return TechnicalIndicators.builder().rsi(rsi).stochasticK(stochastic[0]).stochasticD(stochastic[1])
                .stochasticZone(determineStochasticZone(stochastic[0]))
                .movingAverages(mas)
                .atr(calculateATR(dailyData, 14))
                .marketRegime(rsi > 60 ? "STRONG_TREND" : rsi < 40 ? "WEAK_TREND" : "SIDEWAYS").build();
    }

    private double calculateATR(List<com.AISA.AISA.kisOverseasStock.entity.OverseasStockDailyData> data, int period) {
        if (data == null || data.size() < period + 1)
            return 0.0;
        List<com.AISA.AISA.kisOverseasStock.entity.OverseasStockDailyData> sorted = new ArrayList<>(data);
        java.util.Collections.reverse(sorted); // Oldest first

        double trSum = 0.0;
        // Simple Average TR for first period
        for (int i = 1; i <= period; i++) {
            double high = sorted.get(i).getHighPrice().doubleValue();
            double low = sorted.get(i).getLowPrice().doubleValue();
            double prevClose = sorted.get(i - 1).getClosingPrice().doubleValue();
            double tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            trSum += tr;
        }
        double atr = trSum / period;

        // Smoothing
        for (int i = period + 1; i < sorted.size(); i++) {
            double high = sorted.get(i).getHighPrice().doubleValue();
            double low = sorted.get(i).getLowPrice().doubleValue();
            double prevClose = sorted.get(i - 1).getClosingPrice().doubleValue();
            double tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            atr = ((atr * (period - 1)) + tr) / period;
        }

        return atr;
    }

    private double calculateTrendScore(TechnicalIndicators tech, BigDecimal currentPrice) {
        double technicalComponent = 50.0;
        if (tech != null) {
            // [P0] RSI Logic Correction
            if (tech.getRsi() < 30) // Oversold
                technicalComponent += 15;
            else if (tech.getRsi() < 40) // Approaching Oversold
                technicalComponent += 10;
            else if (tech.getRsi() > 70) // Overbought
                technicalComponent -= 10;

            if ("GOLDEN".equals(tech.getStochasticSignal()))
                technicalComponent += 15;
            else if ("DEAD".equals(tech.getStochasticSignal()))
                technicalComponent -= 15;
            if ("OVERSOLD".equals(tech.getStochasticZone()))
                technicalComponent += 10;
            else if ("OVERBOUGHT".equals(tech.getStochasticZone()))
                technicalComponent -= 10;
        }
        return Math.max(0, Math.min(100, technicalComponent));
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
        return loss == 0 ? 100.0 : 100 - (100 / (1 + (gain / period) / (loss / period)));
    }

    private double[] calculateStochastic(List<com.AISA.AISA.kisOverseasStock.entity.OverseasStockDailyData> data, int n,
            int m) {
        if (data == null || data.size() < n + m)
            return new double[] { 50.0, 50.0 };
        List<Double> kList = new ArrayList<>();
        List<com.AISA.AISA.kisOverseasStock.entity.OverseasStockDailyData> sorted = new ArrayList<>(data);
        java.util.Collections.reverse(sorted);
        for (int i = n - 1; i < sorted.size(); i++) {
            double low = sorted.subList(i - n + 1, i + 1).stream().mapToDouble(d -> d.getLowPrice().doubleValue()).min()
                    .orElse(0);
            double high = sorted.subList(i - n + 1, i + 1).stream().mapToDouble(d -> d.getHighPrice().doubleValue())
                    .max().orElse(1);
            double current = sorted.get(i).getClosingPrice().doubleValue();
            kList.add(high - low == 0 ? 50.0 : ((current - low) / (high - low)) * 100.0);
        }
        double k = kList.get(kList.size() - 1);
        double pk1 = kList.size() >= 2 ? kList.get(kList.size() - 2) : k;
        double pk2 = kList.size() >= 3 ? kList.get(kList.size() - 3) : k;
        k = (k + pk1 + pk2) / 3.0;
        return new double[] { Math.round(k * 10.0) / 10.0, Math.round(k * 10.0) / 10.0 };
    }

    private String determineStochasticZone(double k) {
        if (k >= 80)
            return "OVERBOUGHT";
        if (k >= 60)
            return "STRONG";
        if (k <= 20)
            return "OVERSOLD";
        if (k <= 40)
            return "WEAK";
        return "NEUTRAL";
    }

    private String determineStochasticSignal(List<com.AISA.AISA.kisOverseasStock.entity.OverseasStockDailyData> data,
            int n, int m) {
        double[] curr = calculateStochastic(data, n, m);
        double[] prev = calculateStochastic(data.subList(1, data.size()), n, m);
        if (prev[0] <= prev[1] && curr[0] > curr[1])
            return "GOLDEN";
        if (prev[0] >= prev[1] && curr[0] < curr[1])
            return "DEAD";
        return "NONE";
    }

    @Getter
    private enum MarketPhase {
        IDEAL_BULL("이상적인 상승장 (Ideal Bull)", "강력한 펀더멘털과 수급이 동반된 상승세. 적극 매수 구간."),
        LATE_STAGE_RALLY("과열/추세 후반 (Late Stage)", "주가는 상승 중이나 밸류에이션 부담이 가중됨. 추세 추종하되 비중 조절 필요."),
        BOTTOM_FISHING("저점 매수 기회 (Bottom Fishing)", "주가는 바닥권이나 밸류에이션 매력이 높음. 분할 매수로 접근 유효."),
        FALLING_KNIFE("하락세/관망 (Falling Knife)", "추세와 밸류에이션 모두 부정적. 성급한 진입 자제."),
        RECOVERY("회복 국면 (Recovery)", "수급이 개선되며 턴어라운드 시도 중."),
        UNCERTAIN("방향성 탐색 (Uncertain)", "뚜렷한 방향성이 없는 중립 구간. 관망 필요.");

        final String title;
        final String desc;

        MarketPhase(String title, String desc) {
            this.title = title;
            this.desc = desc;
        }
    }

    private MarketPhase determineMarketPhase(double trendScore, double valuationScore, double probScore) {
        // [V5 Enhancement] Fundamental Destruction check
        if (valuationScore < 35 && trendScore < 50)
            return MarketPhase.FALLING_KNIFE;

        if (trendScore >= 60 && valuationScore >= 60)
            return MarketPhase.IDEAL_BULL;
        if (trendScore >= 60 && valuationScore < 40)
            return MarketPhase.LATE_STAGE_RALLY;
        if (trendScore < 40 && valuationScore >= 60)
            return MarketPhase.BOTTOM_FISHING;
        if (trendScore < 40 && valuationScore < 40)
            return MarketPhase.FALLING_KNIFE;
        if (probScore >= 55 && trendScore >= 40)
            return MarketPhase.RECOVERY;
        return MarketPhase.UNCERTAIN;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    private static class AiResponseJson {
        private String keyInsight;
        private com.AISA.AISA.analysis.dto.ValuationBaseDto.Summary.AiVerdict aiVerdict;
        private com.AISA.AISA.analysis.dto.ValuationBaseDto.Summary.BeginnerVerdict beginnerVerdict;
        private List<String> catalysts;
        private List<String> risks;
        private Map<String, Double> suggestedWeights;
        private String actionPlan;
        private String probabilityInfo;
        private String timingAction;
        private String investmentTerm;
        private com.AISA.AISA.analysis.dto.ValuationBaseDto.Summary.Probabilities probabilities;
    }

    private void resolveNarrativeInconsistency(Response response, ActionStrategy strategy,
            Summary.Probabilities probs, TechnicalIndicators tech) {
        if (response.getCurrentPosition() == null || strategy == null)
            return;

        String style = response.getCurrentPosition().getStockStyle();
        BigDecimal cur = new BigDecimal(response.getCurrentPrice().replace(",", ""));

        // 1. S-RIM Paradox Fix: Labeling and Weighting
        if (response.getSrim() != null && response.getSrim().isAvailable()) {
            BigDecimal srimPrice = new BigDecimal(response.getSrim().getPrice().replace(",", ""));
            if (srimPrice.compareTo(cur) > 0) {
                // Price > Current: This is not a "floor", it's a growth-driven intrinsic value
                response.getSrim().setDescription("성장 가동 내재가치 (Forward Growth Value)");
                response.getSrim().setReason("성장률 모멘텀이 반영된 미래 내재 가치");
            } else {
                // Price <= Current: This acts as a floor
                response.getSrim().setDescription("보수적 내재가치 (Asset Floor)");
                response.getSrim().setReason("자산 가치 기반의 최후방 지지선");
            }
        }

        // 2. Probability Cap for HOLD verdict
        if (response.getSummary() != null && "HOLD".equals(response.getSummary().getOverallVerdict())
                && probs != null) {
            if (probs.getShortTerm() != null) {
                double p = Double.parseDouble(probs.getShortTerm().replace("%", ""));
                if (p > 55.0)
                    probs.setShortTerm("55%");
            }
            if (probs.getMidTerm() != null) {
                double p = Double.parseDouble(probs.getMidTerm().replace("%", ""));
                if (p > 58.0)
                    probs.setMidTerm("58%");
            }
            if (probs.getLongTerm() != null) {
                double p = Double.parseDouble(probs.getLongTerm().replace("%", ""));
                if (p > 60.0)
                    probs.setLongTerm("60%");
            }
        }

        // 3. Target Price Monotonicity & Probability-Upside Sync
        if (strategy.getTargetPrices() != null) {
            String shortTStr = strategy.getTargetPrices().getShortTerm().replace("$", "").replace(",", "");
            String midTStr = strategy.getTargetPrices().getMidTerm().replace("$", "").replace(",", "");
            String longTStr = strategy.getTargetPrices().getLongTerm().replace("$", "").replace(",", "");

            try {
                BigDecimal sT = new BigDecimal(shortTStr);
                BigDecimal mT = new BigDecimal(midTStr);
                BigDecimal lT = new BigDecimal(longTStr);

                // Probability-Upside Sync: If short-term prob > 50%, upside must be at least 3%
                if (probs != null && probs.getShortTerm() != null) {
                    double p = Double.parseDouble(probs.getShortTerm().replace("%", ""));
                    if (p > 50.0 && sT.compareTo(cur.multiply(BigDecimal.valueOf(1.03))) < 0) {
                        sT = cur.multiply(BigDecimal.valueOf(1.03)).setScale(2, RoundingMode.HALF_UP);
                        strategy.getTargetPrices().setShortTerm("$" + sT);
                    }
                }

                // Monotonicity Fix for GROWTH style
                if ("GROWTH".equals(style)) {
                    if (mT.compareTo(sT) < 0) {
                        mT = sT.multiply(BigDecimal.valueOf(1.05)).setScale(2, RoundingMode.HALF_UP);
                        strategy.getTargetPrices().setMidTerm("$" + mT);
                    }
                    if (lT.compareTo(mT) < 0) {
                        lT = mT.multiply(BigDecimal.valueOf(1.07)).setScale(2, RoundingMode.HALF_UP);
                        strategy.getTargetPrices().setLongTerm("$" + lT);
                    }
                }

                // 4. Strategic Cap: If near resistance and HOLD, don't force too much upside
                String resStr = strategy.getResistancePrice().replace("$", "").replace(",", "");
                BigDecimal resistance = new BigDecimal(resStr);
                if (response.getSummary() != null && "HOLD".equals(response.getSummary().getOverallVerdict())) {
                    // [P1] Target Price vs Resistance: If target is near resistance (<1%), set
                    // target = resistance * 1.02
                    BigDecimal upperRes = resistance.multiply(BigDecimal.valueOf(1.01));
                    BigDecimal lowerRes = resistance.multiply(BigDecimal.valueOf(0.99));

                    double atr = 0.0;
                    if (tech != null)
                        atr = tech.getAtr();

                    if (sT.compareTo(upperRes) <= 0 && sT.compareTo(lowerRes) >= 0) {
                        // Target ~ Resistance -> Target = Wall problem.
                        // Fix: Target = max(Resistance * 1.03, Resistance + 0.5 * ATR)
                        // Exception: If Trend < 40 (Weak), allow Target = Resistance

                        if (response.getCurrentPosition().getTrendScore() >= 40) {
                            BigDecimal breakoutTarget = resistance.multiply(BigDecimal.valueOf(1.03));
                            BigDecimal atrTarget = resistance.add(BigDecimal.valueOf(atr * 0.5));
                            sT = breakoutTarget.max(atrTarget).setScale(2, RoundingMode.HALF_UP);
                        }
                        // else: Trend is weak, so failing at resistance is a valid scenario. Leave as
                        // is (Target ~ Resistance).

                        strategy.getTargetPrices().setShortTerm("$" + sT);
                    } else if (sT.compareTo(resistance) > 0
                            && sT.compareTo(cur.multiply(BigDecimal.valueOf(1.05))) < 0) {
                        // Original logic: Cap short-term target at resistance if near (within 5%) BUT
                        // not if already adjusted above
                        // Only cap if we didn't boost it.
                        // Actually, the user wants "Target >= Resistance * 1.02".
                        // If calculated target > resistance but < cur*1.05 (very close upside), let it
                        // be resistance?
                        // The user said "Same value = Wall".
                        // So if we cap, we should probably cap slightly below or match.
                        // But the user recommendation is: Target >= Resistance * 1.02.
                        // Implication: If we think it's capped, maybe we shouldn't set target at
                        // resistance.
                        // Let's stick to the User's Rule: if near, boost to breakout.
                        // If not near, leave as is.
                    }
                }
            } catch (Exception e) {
                log.warn("Narrative Resolution failed during numeric conversion: {}", e.getMessage());
            }
        }
    }

    private StyleScores calculateStyleScores(String stockCode, Response val) {
        double growthScore = 50.0;
        double valueScore = 50.0;

        // 1. Growth Score calculation
        List<OverseasStockFinancialStatement> stmts = financialStatementRepository
                .findTop2ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
        if (stmts.size() >= 2) {
            BigDecimal currentRev = stmts.get(0).getTotalRevenue();
            BigDecimal pastRev = stmts.get(1).getTotalRevenue();
            if (pastRev != null && pastRev.compareTo(BigDecimal.ZERO) > 0 && currentRev != null) {
                double revGrowth = (currentRev.subtract(pastRev).doubleValue() / pastRev.doubleValue()) * 100.0;
                growthScore += Math.max(-20, Math.min(20, revGrowth));
            }

            if (currentRev != null && stmts.get(0).getOperatingIncome() != null
                    && currentRev.compareTo(BigDecimal.ZERO) > 0) {
                double margin = (stmts.get(0).getOperatingIncome().doubleValue() / currentRev.doubleValue()) * 100.0;
                growthScore += Math.max(-15, Math.min(15, margin - 10)); // 10% 기준 보너스/페널티
            }
        }

        var multiples = tradingMultipleRepository.findByStockCode(stockCode);
        if (multiples.isPresent()) {
            Double peg = multiples.get().getPegRatio();
            if (peg != null && peg > 0) {
                growthScore += Math.max(-15, Math.min(15, (1.2 - peg) * 25)); // PEG 1.2 기준 (미국 테크주 관대)
            }
        }

        // 2. Value Score calculation
        OverseasStockFinancialRatio ratio = financialRatioRepository
                .findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
        if (ratio != null && ratio.getRoe() != null) {
            valueScore += Math.max(-20, Math.min(20, ratio.getRoe().doubleValue() - 10)); // ROE 10% 기준
        }

        String shareholderReturn = calculateShareholderReturnRate(stockCode);
        if (!"N/A".equals(shareholderReturn)) {
            try {
                double returnRate = Double.parseDouble(shareholderReturn.replace("%", ""));
                valueScore += Math.max(0, Math.min(15, returnRate / 5.0));
            } catch (Exception e) {
            }
        }

        // 3. Determine Style
        double ratioVal = growthScore / Math.max(1.0, valueScore);
        StockStyle style = (ratioVal > 1.3) ? StockStyle.GROWTH
                : (ratioVal < 0.75) ? StockStyle.VALUE : StockStyle.BLEND;
        String confidence = Math.abs(growthScore - valueScore) > 30 ? "HIGH"
                : Math.abs(growthScore - valueScore) > 15 ? "MEDIUM" : "LOW";

        return StyleScores.builder()
                .growthScore(Math.min(100, Math.max(0, growthScore)))
                .valueScore(Math.min(100, Math.max(0, valueScore)))
                .style(style)
                .confidence(confidence)
                .build();
    }

    private ValuationResult calculatePEGModel(String stockCode, BigDecimal eps, BigDecimal currentPrice) {
        var multiples = tradingMultipleRepository.findByStockCode(stockCode);
        if (multiples.isEmpty() || multiples.get().getPegRatio() == null || eps == null
                || eps.compareTo(BigDecimal.ZERO) <= 0) {
            return emptyResult("PEG", "데이터 부족");
        }

        double peg = multiples.get().getPegRatio();
        // 적정주가 = PEG 1.0 기준 역산
        BigDecimal targetPrice = currentPrice.divide(BigDecimal.valueOf(Math.max(0.01, peg)), 2, RoundingMode.HALF_UP);

        return buildResult(targetPrice, currentPrice, "PEG", peg < 1.0,
                String.format("PEG %.2f 기반 적정가 (성장성 대비 가격)", peg), "PEG Model");
    }

    private ValuationResult calculateAdjustedPSRModel(String stockCode, BigDecimal psr, BigDecimal currentPrice) {
        List<OverseasStockFinancialStatement> stmts = financialStatementRepository
                .findTop2ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
        if (stmts.isEmpty() || psr == null)
            return emptyResult("Adj.PSR", "데이터 부족");

        BigDecimal rev = stmts.get(0).getTotalRevenue();
        BigDecimal op = stmts.get(0).getOperatingIncome();
        if (rev == null || op == null || rev.compareTo(BigDecimal.ZERO) <= 0)
            return emptyResult("Adj.PSR", "마진 데이터 부족");

        double margin = op.doubleValue() / rev.doubleValue();
        // 마진 15% 기점으로 보정
        double marginFactor = Math.max(0.5, Math.min(1.5, margin / 0.15));
        BigDecimal adjPrice = currentPrice.multiply(BigDecimal.valueOf(marginFactor))
                .divide(BigDecimal.valueOf(Math.max(0.1, psr.doubleValue() / 5.0)), 2, RoundingMode.HALF_UP);

        return buildResult(adjPrice, currentPrice, "Adj.PSR", margin > 0.15,
                String.format("마진률 %.1f%% 반영 조정 PSR 모델", margin * 100), "Adjusted PSR");
    }

    private ValuationResult calculateEnhancedPBRModel(OverseasStockFinancialRatio ratio, BigDecimal currentPrice) {
        if (ratio == null || ratio.getPbr() == null || ratio.getRoe() == null || ratio.getBpsUsd() == null) {
            return emptyResult("PBR-ROE", "데이터 부족");
        }

        double roe = ratio.getRoe().doubleValue() / 100.0;
        double coe = 0.09; // 미국 장기 COE 9% 수준
        double targetPbr = Math.max(0.5, roe / coe);
        BigDecimal targetPrice = ratio.getBpsUsd().multiply(BigDecimal.valueOf(targetPbr)).setScale(2,
                RoundingMode.HALF_UP);

        boolean undervalued = ratio.getPbr().doubleValue() < targetPbr;
        return buildResult(targetPrice, currentPrice, "PBR-ROE", undervalued,
                String.format("ROE(%.1f%%) 대비 적정 PBR(%.2f) 산출", roe * 100, targetPbr), "Enhanced PBR");
    }

    // [V6] Consensus Price Governance: f(Style, Stability) + [P0] PEG Integration &
    // Median Fallback
    private ValuationResult calculateValuationConsensus(StockStyle style, ValuationResult srim, ValuationResult peg,
            ValuationResult per,
            ValuationResult pbr, BigDecimal currentPrice) {
        // Governance: Use PEG instead of SRIM for Growth Style if available
        ValuationResult growthModel = (style == StockStyle.GROWTH && peg.isAvailable()) ? peg : srim;
        boolean isPegUsed = (style == StockStyle.GROWTH && peg.isAvailable());

        double wSrim = 0.33, wPer = 0.33, wPbr = 0.34; // Default BLEND

        if (style == StockStyle.GROWTH) {
            wPer = 0.60;
            wSrim = 0.30; // This weight applies to 'growthModel' (PEG or SRIM)
            wPbr = 0.10;
        } else if (style == StockStyle.VALUE) {
            wPbr = 0.60;
            wSrim = 0.30;
            wPer = 0.10;
        }

        double totalW = 0, sumPrice = 0;
        int validModelCount = 0;
        List<Double> validPrices = new java.util.ArrayList<>();

        // 1. Growth Model (SRIM or PEG)
        if (growthModel.isAvailable()) {
            double p = Double.parseDouble(growthModel.getPrice().replace(",", ""));
            if (p > 0) {
                totalW += wSrim;
                sumPrice += p * wSrim;
                validModelCount++;
                validPrices.add(p);
            }
        }
        if (per.isAvailable()) {
            double p = Double.parseDouble(per.getPrice().replace(",", ""));
            if (p > 0) {
                totalW += wPer;
                sumPrice += p * wPer;
                validModelCount++;
                validPrices.add(p);
            }
        }
        if (pbr.isAvailable()) {
            double p = Double.parseDouble(pbr.getPrice().replace(",", ""));
            if (p > 0) {
                totalW += wPbr;
                sumPrice += p * wPbr;
                validModelCount++;
                validPrices.add(p);
            }
        }

        if (validModelCount == 0)
            return emptyResult("Consensus", "가용 모델 없음");

        BigDecimal consensusPrice;
        String description;

        // [P0] Fail-Safe: If total weight is too low or calculation fails, use
        // unweighted
        // average
        if (totalW < 0.1) {
            double avg = validPrices.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            consensusPrice = BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP);
            description = "가용 모델의 단순 평균 (가중치 산출 불가)";
        } else {
            // Normalize weights if some models are missing
            double normalizationFactor = 1.0 / totalW;
            consensusPrice = BigDecimal.valueOf(sumPrice * normalizationFactor).setScale(2, RoundingMode.HALF_UP);
            description = String.format("%s 스타일 가중치를 적용한 통합 적정가", style);
        }

        // [P0] Consensus Engine Fail-Safe: Ensure price is strictly positive
        if (consensusPrice.compareTo(BigDecimal.ZERO) <= 0) {
            // Option B: Auto Fallback to Median
            if (!validPrices.isEmpty()) {
                Collections.sort(validPrices);
                double median;
                if (validPrices.size() % 2 == 0)
                    median = (validPrices.get(validPrices.size() / 2 - 1) + validPrices.get(validPrices.size() / 2))
                            / 2.0;
                else
                    median = validPrices.get(validPrices.size() / 2);

                if (median > 0) {
                    consensusPrice = BigDecimal.valueOf(median).setScale(2, RoundingMode.HALF_UP);
                    description = "Consensus (Median Fallback)"; // Mark fallback
                } else {
                    throw new RuntimeException("Consensus Calculation Failed: Median <= 0");
                }
            } else {
                throw new RuntimeException("Consensus Calculation Failed: No Valid Models");
            }
        }

        // Final check blocked by Exception above, so safe to proceed
        boolean undervalued = consensusPrice.compareTo(currentPrice) > 0;
        description = isPegUsed ? "PEG/PER/PBR 통합" : "SRIM/PER/PBR 통합";

        return buildResult(consensusPrice, currentPrice, "Consensus", undervalued,
                isPegUsed ? "PEG 반영 통합 모델" : description,
                "Consensus Engine (v2)");
    }

    // [V6] Confidence Attribution: Decomposition of AI Confidence
    // [V6] Confidence Attribution: Decomposition of AI Confidence
    private Summary.ConfidenceAttribution calculateConfidenceAttribution(Response res, TechnicalIndicators tech) {
        double dataScore = 100.0;
        if (res.getSrim() == null || !res.getSrim().isAvailable())
            dataScore -= 20;
        if (res.getPer() == null || !res.getPer().isAvailable())
            dataScore -= 20;
        if (res.getPbr() == null || !res.getPbr().isAvailable())
            dataScore -= 20;

        double modelAgreement = 100.0;
        try {
            List<Double> validPrices = new java.util.ArrayList<>();
            if (res.getSrim() != null && res.getSrim().isAvailable())
                validPrices.add(Double.parseDouble(res.getSrim().getPrice().replace(",", "")));
            if (res.getPer() != null && res.getPer().isAvailable())
                validPrices.add(Double.parseDouble(res.getPer().getPrice().replace(",", "")));
            if (res.getPbr() != null && res.getPbr().isAvailable())
                validPrices.add(Double.parseDouble(res.getPbr().getPrice().replace(",", "")));

            if (validPrices.size() > 1) {
                double avg = validPrices.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                double maxDev = validPrices.stream().mapToDouble(p -> Math.abs(p - avg)).max().orElse(0.0);
                double devRatio = maxDev / avg; // e.g., 0.3 = 30% deviation

                // [P0] Confidence Logic Tuning: Exponential decay for deviation > 30%
                if (devRatio > 0.3) {
                    modelAgreement = Math.max(0, 100 - (devRatio * 300)); // Severe penalty (e.g. 50% dev -> 100 - 150 =
                                                                          // 0)
                } else {
                    modelAgreement = Math.max(0, 100 - (devRatio * 100)); // Mild penalty
                }
            } else if (validPrices.isEmpty()) {
                modelAgreement = 0.0;
            }
        } catch (Exception e) {
            modelAgreement = 50.0; // Fallback
        }

        double stability = tech != null ? (tech.getRegimeStability() != null ? tech.getRegimeStability() * 100 : 50.0)
                : 50.0;

        List<String> drivers = identifyTopDrivers(res, tech);

        return Summary.ConfidenceAttribution.builder()
                .dataQuality(dataScore).modelAgreement(modelAgreement).regimeStability(stability)
                .primaryDriver(drivers.isEmpty() ? "복합적 시장 요인" : drivers.get(0))
                .build();
    }

    private List<String> identifyTopDrivers(Response res, TechnicalIndicators tech) {
        List<String> drivers = new java.util.ArrayList<>();

        // [P1] Primary Driver Logic: Hierarchical Selection
        // 1. Valuation Conflict (Highest Priority if Risk is HIGH)
        if (res.getActionStrategy() != null && "HIGH".equals(res.getActionStrategy().getDivergenceRiskIndex())) {
            drivers.add("Valuation Model Conflict (High Dispersion)");
        }

        // 2. Resistance Test (Critical Price Action)
        try {
            if (res.getActionStrategy() != null) {
                BigDecimal cur = new BigDecimal(res.getCurrentPrice().replace(",", ""));
                BigDecimal resPrice = new BigDecimal(
                        res.getActionStrategy().getResistancePrice().replace("$", "").replace(",", ""));
                BigDecimal gap = resPrice.subtract(cur).abs().divide(cur, 4, RoundingMode.HALF_UP);
                if (gap.doubleValue() < 0.03) { // Within 3%
                    drivers.add("Resistance Test (Key Level Approach)");
                }
            }
        } catch (Exception e) {
        }

        // 3. Technical Extremes
        if (tech != null) {
            if (tech.getRsi() < 30)
                drivers.add("Technical Oversold (RSI < 30)");
            else if (tech.getRsi() > 70)
                drivers.add("Technical Overbought (RSI > 70)");
        }

        // 4. Fundamental/Trend (Default)
        if (drivers.isEmpty()) {
            if (res.getCurrentPosition().getValuationScore() > 80)
                drivers.add("Strong Valuation Appeal");
            else if (res.getCurrentPosition().getTrendScore() > 80)
                drivers.add("Strong Momentum");
            else
                drivers.add("Mixed Market Signals");
        }

        return drivers;
    }

    private Summary.Probabilities fillMissingProbabilities(Summary.Probabilities input, Double trendScore,
            Double valuationScore, String verdict) {
        if (input == null)
            input = new Summary.Probabilities();

        // [P1] Probability Decaying Logic (Mean Reversion)
        // Baseline: BUY(55%), HOLD(45%), SELL(35%)
        double baseline = "BUY".equals(verdict) ? 55.0 : "SELL".equals(verdict) ? 35.0 : 45.0;

        if (isInvalidProb(input.getShortTerm())) {
            double p = trendScore != null ? Math.min(99, Math.max(1, trendScore)) : 50.0;
            input.setShortTerm(String.format("%.1f%%", p));
        }

        double shortProb = extractProbability(input.getShortTerm());

        if (isInvalidProb(input.getMidTerm())) {
            // Decay towards baseline
            double p = (shortProb * 0.6) + (baseline * 0.4);
            input.setMidTerm(String.format("%.1f%%", Math.min(99, Math.max(1, p))));
        }

        double midProb = extractProbability(input.getMidTerm());

        if (isInvalidProb(input.getLongTerm())) {
            // Decay further towards baseline
            double p = (midProb * 0.5) + (baseline * 0.5);
            input.setLongTerm(String.format("%.1f%%", Math.min(99, Math.max(1, p))));
        }
        return input;
    }

    private boolean isInvalidProb(String prob) {
        if (prob == null || "N/A".equals(prob) || prob.isEmpty())
            return true;
        try {
            extractProbability(prob);
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private double extractProbability(String probStr) {
        String num = probStr.replaceAll("[^0-9.]", "");
        if (num.isEmpty())
            throw new NumberFormatException("No digits found in " + probStr);
        return Double.parseDouble(num);
    }

    private AiResponseJson parseAiResponse(String jsonText) {
        if (jsonText == null || jsonText.isEmpty())
            return fallbackAiResponse();
        try {
            int start = jsonText.indexOf("{");
            int end = jsonText.lastIndexOf("}");
            if (start >= 0 && end > start) {
                ObjectMapper mapper = new ObjectMapper();
                mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                AiResponseJson res = mapper.readValue(jsonText.substring(start, end + 1), AiResponseJson.class);
                if (res.getAiVerdict() == null) {
                    res.setAiVerdict(com.AISA.AISA.analysis.dto.ValuationBaseDto.Summary.AiVerdict.builder()
                            .stance(com.AISA.AISA.analysis.dto.ValuationBaseDto.Stance.HOLD)
                            .riskLevel(com.AISA.AISA.analysis.dto.ValuationBaseDto.RiskLevel.MEDIUM)
                            .guidance("분석 실패").build());
                }
                return res;
            }
        } catch (Exception e) {
            log.error("Parse error: {}", e.getMessage());
        }
        return fallbackAiResponse();
    }

    private AiResponseJson fallbackAiResponse() {
        AiResponseJson f = new AiResponseJson();
        f.setKeyInsight("분석 지연");
        f.setAiVerdict(com.AISA.AISA.analysis.dto.ValuationBaseDto.Summary.AiVerdict.builder()
                .stance(com.AISA.AISA.analysis.dto.ValuationBaseDto.Stance.HOLD)
                .riskLevel(com.AISA.AISA.analysis.dto.ValuationBaseDto.RiskLevel.MEDIUM)
                .guidance("서버 오류").build());
        f.setBeginnerVerdict(com.AISA.AISA.analysis.dto.ValuationBaseDto.Summary.BeginnerVerdict.builder()
                .summarySentence("잠시 후 시도").build());
        f.setCatalysts(List.of("데이터 부족"));
        f.setRisks(List.of("불확실성"));
        f.setTimingAction("관망");
        return f;
    }
}
