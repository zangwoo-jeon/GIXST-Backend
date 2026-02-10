package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.dto.ValuationBaseDto.*;
import com.AISA.AISA.analysis.dto.ValuationBaseDto.Summary.AiVerdict;
import com.AISA.AISA.analysis.dto.ValuationBaseDto.Summary.BeginnerVerdict;
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
    private final KisOverseasStockFinancialStatementRepository financialStatementRepository;
    private final KisOverseasStockInformationService overseasService;
    private final KisMacroService kisMacroService;
    private final GeminiService geminiService;
    private final OverseasStockAiSummaryRepository overseasStockAiSummaryRepository;
    private final OverseasStockTradingMultipleRepository tradingMultipleRepository;

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
        Summary summary = generateSummary(srim, perModel, pbrModel);

        return Response.builder().stockCode(stockCode).stockName(stock.getStockName())
                .currentPrice(currentPrice.setScale(2, RoundingMode.HALF_UP).toString())
                .marketCap(marketCapStr)
                .targetReturn(discountRateInfo.getValue())
                .discountRate(discountRateInfo)
                .srim(srim).per(perModel).pbr(pbrModel).band(band).summary(summary)
                .build();
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

        // ROE Sanity Check: 성장률이 높더라도 ROE < COE 이면 성장이 가치를 훼손하는 것으로 간주
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
            return ValuationResult.builder().price("0").gapRate("0").verdict("N/A").description(desc + " (ROE < COE)")
                    .available(false).reason("Value Destruction").build();
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
        BigDecimal targetPrice = eps.multiply(avgPer);
        return buildResult(targetPrice, currentPrice, "PER " + avgPer + "배 (과거평균) 적용", true, "Normal", null);
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

    private ValuationBand calculateBand(BigDecimal currentPrice, ValuationResult srim, ValuationResult per,
            ValuationResult pbr, String stockCode, String stockName) {

        // 업종 성격에 따른 동적 가중치 설정 (무형자산 비중 고려)
        Map<String, Double> dynamicWeights = determineIndustryWeights(stockCode, stockName);

        List<BigDecimal> prices = new java.util.ArrayList<>();
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

        BigDecimal min = prices.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal max = prices.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal avg = weightedSum;
        BigDecimal gap = currentPrice.compareTo(BigDecimal.ZERO) > 0
                ? avg.subtract(currentPrice).divide(currentPrice, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100))
                : BigDecimal.ZERO;

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
                .gapRate(gap.setScale(2, RoundingMode.HALF_UP).toString()).position(position).status(status)
                .weights(finalWeights).build();
    }

    private Map<String, Double> determineIndustryWeights(String stockCode, String stockName) {
        Map<String, Double> weights = new HashMap<>();
        // 미국 주식의 경우 심볼 및 이름을 기반으로 무형자산 중심 섹터(Software, Bio, Platform)인지 판별
        // 실제로는 IndustryCategorizationService 등을 통해 정밀 분류하지만, 여기서는 핵심 로직 구현을 위해 키워드 매핑
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
            // 전통 제조/에너지 등 유형자산 중심
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

    private Summary generateSummary(ValuationResult srim, ValuationResult per, ValuationResult pbr) {
        int score = 0;
        if (srim.isAvailable())
            score += getScore(srim.getVerdict()) * 5;
        if (per.isAvailable())
            score += getScore(per.getVerdict()) * 3;
        if (pbr.isAvailable())
            score += getScore(pbr.getVerdict()) * 2;

        String verdict = score >= 3 ? "BUY" : score <= -3 ? "SELL" : "HOLD";
        String confidence = Math.abs(score) >= 7 ? "HIGH" : "MEDIUM";
        return Summary.builder()
                .overallVerdict(verdict)
                .confidence(confidence)
                .keyInsight("지표 종합 점수: " + score + "점 (AI 정밀 진단 대기 중)")
                .build();
    }

    private int getScore(String verdict) {
        if ("UNDERVALUED".equals(verdict))
            return 1;
        if ("OVERVALUED".equals(verdict))
            return -1;
        return 0;
    }

    @Transactional
    public Response calculateValuationWithAi(String stockCode, Request request) {
        Response baseValuation = calculateValuation(stockCode, request);
        String aiAnalysis = getValuationAnalysis(stockCode, baseValuation);
        return buildResponseWithAi(baseValuation, aiAnalysis);
    }

    @Transactional
    public Response getStandardizedValuationReport(String stockCode, boolean refresh) {
        Request request = Request.builder().userPropensity(UserPropensity.NEUTRAL).forceRefresh(refresh).build();
        return calculateValuationWithAi(stockCode, request);
    }

    @Transactional
    public String getValuationAnalysis(String stockCode, Response val) {
        BigDecimal currentPrice = new BigDecimal(val.getCurrentPrice());
        Optional<com.AISA.AISA.analysis.entity.OverseasStockAiSummary> cachedSummary = overseasStockAiSummaryRepository
                .findByStockCode(stockCode);
        if (cachedSummary.isPresent() && !val.getSummary().getOverallVerdict().equals("N/A")) {
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

        String displayVerdict = val.getSummary().getOverallVerdict();

        // [V4.3 Final Polishing] AI용 가격 전략 사전 계산
        BigDecimal theoreticalMax = new BigDecimal(val.getBand().getMaxPrice().replace(",", ""));
        BigDecimal baseTarget = theoreticalMax.max(currentPrice);
        BigDecimal resPrice = baseTarget.multiply(new BigDecimal("1.05")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal bandMin = new BigDecimal(val.getBand().getMinPrice().replace(",", ""));
        BigDecimal supPrice = bandMin.multiply(new BigDecimal("0.95"))
                .max(currentPrice.multiply(new BigDecimal("0.90"))).setScale(2, RoundingMode.HALF_UP);

        String resistanceStr = "$" + String.format("%,.2f", resPrice);
        String supportStr = "$" + String.format("%,.2f", supPrice);

        String analysis = generateValuationAnalysisText(val, resistanceStr, supportStr);
        AiResponseJson aiJson = parseAiResponse(analysis);
        String displayLabel = mapVerdictToLabel(displayVerdict,
                aiJson.getAiVerdict() != null ? aiJson.getAiVerdict().getStance() : Stance.HOLD);
        String displaySummary = aiJson.getActionPlan() != null ? aiJson.getActionPlan() : "전략적 대응이 필요합니다.";
        String displayRisk = (aiJson.getAiVerdict() != null && aiJson.getAiVerdict().getRiskLevel() != null)
                ? aiJson.getAiVerdict().getRiskLevel().name()
                : "MEDIUM";

        // [V4.3] 캐시 저장 로직 업데이트
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

    private String generateValuationAnalysisText(Response val, String resistance, String support) {
        StringBuilder prompt = new StringBuilder();
        String stockCode = val.getStockCode();
        OverseasStockFinancialRatio latestRatio = financialRatioRepository
                .findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
        List<OverseasStockCashFlowDto> returns = overseasService.getShareholderReturnInfo(stockCode);

        prompt.append("너는 '미국 주식 투자 전략가(Critical Strategist)'이다. 월가(Wall St.)의 시각으로 비판적으로 분석하라.\n");
        prompt.append(String.format("종목: %s (%s), 현재가: $%s\n", val.getStockName(), stockCode, val.getCurrentPrice()));

        if (latestRatio != null) {
            prompt.append(String.format("\n[펀더멘털] PER: %.2f, PBR: %.2f, ROE: %.2f%%, EPS: $%.2f\n",
                    latestRatio.getPer(), latestRatio.getPbr(), latestRatio.getRoe(), latestRatio.getEpsUsd()));
        }

        prompt.append("\n[시스템 Valuation 데이터 (Hard Data)]\n");
        prompt.append(
                String.format("- S-RIM 적정가: $%s (%s)\n", val.getSrim().getPrice(), val.getSrim().getDescription()));
        prompt.append(String.format("- PER 과거평균: $%s (%s)\n", val.getPer().getPrice(), val.getPer().getDescription()));
        prompt.append(String.format("- PBR 과거평균: $%s (%s)\n", val.getPbr().getPrice(), val.getPbr().getDescription()));

        // 가격 위치 context 주입
        if (val.getBand() != null) {
            prompt.append(String.format("- 현재가 밴드 위치: %s (Status: %s)\n", val.getBand().getPosition(),
                    val.getBand().getStatus()));
        }

        // 품질 지표 컨텍스트 주입
        try {
            var multiples = tradingMultipleRepository.findByStockCode(stockCode);
            if (multiples.isPresent()) {
                prompt.append(String.format("- PEG Ratio: %.2f, EV/EBITDA: %.2fx\n", multiples.get().getPegRatio(),
                        multiples.get().getEvEbitda()));
            }
            if (!returns.isEmpty()) {
                prompt.append(String.format("- 주주환원율(Yield): %s\n", shareholderReturnInfo(val, returns)));
            }
        } catch (Exception e) {
        }

        prompt.append("\n[트레이딩 시나리오 분석 미션]\n");
        prompt.append("\n[분석 미션: 5단계 전문 리서치 구조화]\n");
        prompt.append("다음 5단계 구조에 맞춰 `guidance` 필드를 작성하라. 각 단계는 투자자의 사고 흐름을 완벽히 가이드해야 한다.\n");

        prompt.append("1. **[현재 상황 요약]**: 종목의 현재 추세와 밸류에이션 상태를 한 줄로 정의하라. (예: '하락 추세 지속 중이며 저평가 매력 부재')\n");
        prompt.append("2. **[3대 판단 근거]**: 판단의 이유를 다음 3가지 축으로 설명하라.\n");
        prompt.append("   - 밸류에이션: 내재가치 및 PEG 대비 고평가 여부\n");
        prompt.append("   - 모멘텀: 이평선 배열 및 주가 밴드 위치\n");
        prompt.append("   - 리스크: 배당 수익률 및 매크로 민감도\n");
        prompt.append("3. **[향후 시나리오]**: 단기적으로 예상되는 주가 흐름(박스권, 추세 이탈 등)을 제시하라.\n");
        prompt.append("4. **[행동 전략]**: 시스템이 계산한 Resistance(" + resistance + ")와 Support(" + support
                + ") 가격을 인용하여 구체적인 행동 지침을 제시하라.\n");
        prompt.append("   - '반등 시 Resistance 부근 비중 축소'\n");
        prompt.append("   - 'Support 붕괴 시 리스크 관리'\n");
        prompt.append("5. **[판단 변경 조건]**: 현재의 관점이 바뀔 수 있는 트리거(예: '실적 서프라이즈', '저항선 돌파')를 명시하라.\n");

        prompt.append("\n[추가 지침]\n");
        prompt.append("- **Action Plan 도출**: `actionPlan` 필드는 위 [행동 전략]을 1문장으로 요약하여 작성하라.\n");
        prompt.append("- **용어 순화**: 어려운 금융 용어는 투자자가 이해하기 쉬운 표현으로 풀어서 설명하라.\n");

        prompt.append("\nJSON format 가이드:\n");
        prompt.append(
                "```json\n{\n  \"keyInsight\": \"30자 이내의 비판적 핵심 요약\",\n  \"aiVerdict\": {\"stance\": \"BUY/ACCUMULATE/HOLD/REDUCE/SELL\", \"timing\": \"EARLY/MID/LATE\", \"riskLevel\": \"LOW/MEDIUM/HIGH\", \"guidance\": \"[현재 상황] ...\\n\\n[판단 근거] ...\\n\\n[향후 전망] ...\\n\\n[투자 전략] ...\\n\\n[판단 변경 조건] ...\"},\n  \"actionPlan\": \"반등 시 Resistance("
                        + resistance
                        + ") 근처에서 비중 축소 권장\",\n  \"probabilityInfo\": \"상승 확률 예측치 및 근거\",\n  \"catalysts\": [\"호재\"], \"risks\": [\"베타 변동성 등 리스크\"]\n}\n```\n");

        return geminiService.generateAdvice(prompt.toString());
    }

    private Response buildResponseWithAi(Response response, String aiAnalysis) {
        AiResponseJson aiJson = parseAiResponse(aiAnalysis);
        String overallVerdict = (aiJson.getAiVerdict() != null && aiJson.getAiVerdict().getStance() != null)
                ? aiJson.getAiVerdict().getStance().name()
                : "HOLD";

        Summary.Display display = Summary.Display.builder()
                .verdict(overallVerdict)
                .verdictLabel(mapVerdictToLabel(overallVerdict,
                        aiJson.getAiVerdict() != null ? aiJson.getAiVerdict().getStance() : Stance.HOLD))
                .strategy(Summary.Strategy.builder()
                        .actionPlan(aiJson.getActionPlan() != null ? aiJson.getActionPlan() : "전략적 대응이 필요합니다.")
                        .build())
                .risk(aiJson.getAiVerdict() != null && aiJson.getAiVerdict().getRiskLevel() != null
                        ? aiJson.getAiVerdict().getRiskLevel().name()
                        : "MEDIUM")
                .probabilityInfo(aiJson.getProbabilityInfo() != null ? aiJson.getProbabilityInfo() : "상승 확률 분석 중")
                .build();

        Summary newSummary = Summary.builder()
                .overallVerdict(overallVerdict).confidence("HIGH")
                .keyInsight(aiJson.getKeyInsight() != null ? aiJson.getKeyInsight() : "AI 전략 분석 리포트")
                .verdicts(Summary.Verdicts.builder().aiVerdict(aiJson.getAiVerdict()).build())
                .display(display).build();

        // [New Phase 2] 가격 전략 및 품질 지표 연동
        String pegRatioStr = "N/A", evEbitdaStr = "N/A", shareholderYieldStr = "N/A", downsideRiskStr = "N/A";
        BigDecimal pegVal = null, yieldVal = null;
        try {
            var multiples = tradingMultipleRepository.findByStockCode(response.getStockCode());
            if (multiples.isPresent()) {
                if (multiples.get().getPegRatio() != null) {
                    pegVal = BigDecimal.valueOf(multiples.get().getPegRatio());
                    pegRatioStr = String.format("%.2f", multiples.get().getPegRatio());
                }
                if (multiples.get().getEvEbitda() != null)
                    evEbitdaStr = String.format("%.2fx", multiples.get().getEvEbitda());
            }
            List<OverseasStockCashFlowDto> returns = overseasService.getShareholderReturnInfo(response.getStockCode());
            if (!returns.isEmpty() && response.getMarketCap() != null && !response.getMarketCap().equals("N/A")) {
                BigDecimal mktCap = parseMarketCapToBigDecimal(response.getMarketCap());
                if (mktCap.compareTo(BigDecimal.ZERO) > 0 && returns.get(0).getRepurchaseOfCapitalStock() != null) {
                    BigDecimal totalReturn = returns.get(0).getRepurchaseOfCapitalStock()
                            .add(returns.get(0).getCashDividendsPaid());
                    yieldVal = totalReturn.divide(mktCap, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100));
                    shareholderYieldStr = String.format("%.2f%%", yieldVal);
                }
            }
            if (response.getBand() != null && response.getBand().getMinPrice() != null) {
                BigDecimal min = new BigDecimal(response.getBand().getMinPrice());
                BigDecimal cur = new BigDecimal(response.getCurrentPrice().replace(",", ""));
                if (cur.compareTo(min) > 0)
                    downsideRiskStr = String.format("%.2f%%",
                            min.subtract(cur).divide(cur, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100)));
                else
                    downsideRiskStr = "Low Risk";
            }
            if (aiJson.getSuggestedWeights() != null && response.getBand() != null)
                response.getBand().setWeights(aiJson.getSuggestedWeights());

            // 전략 가격 계산 (목표가, 손절가)
            calculatePriceStrategy(response, newSummary, pegVal, yieldVal);

            // [Phase 3] priceModel 수치 정합성 수정 (목표가/손절가 기준)
            if (response.getOverseasAnalysisDetails() != null
                    && response.getOverseasAnalysisDetails().getPriceModel() != null) {
                var pm = response.getOverseasAnalysisDetails().getPriceModel();
                BigDecimal cur = new BigDecimal(response.getCurrentPrice().replace(",", ""));

                if (newSummary.getDisplay().getStrategy() != null
                        && newSummary.getDisplay().getStrategy().getResistanceZone() != null) {
                    BigDecimal tgt = new BigDecimal(
                            newSummary.getDisplay().getStrategy().getResistanceZone().replace("$", "").replace(",",
                                    ""));
                    BigDecimal upside = tgt.subtract(cur).divide(cur, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal(100));
                    pm.setUpsidePotential(String.format("%.2f%%", upside));
                }

                if (newSummary.getDisplay().getStrategy() != null
                        && newSummary.getDisplay().getStrategy().getSupportZone() != null) {
                    BigDecimal sl = new BigDecimal(
                            newSummary.getDisplay().getStrategy().getSupportZone().replace("$", "").replace(",", ""));
                    BigDecimal downside = sl.subtract(cur).divide(cur, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal(100));
                    pm.setDownsideRisk(String.format("%.2f%%", downside));
                }
            }

        } catch (Exception e) {
            log.error("Failed to calculate strategic prices: {}", e.getMessage());
        }

        OverseasAnalysisDetails details = OverseasAnalysisDetails.builder().investmentTerm("6-12 Months")
                .catalysts(aiJson.getCatalysts()).risks(aiJson.getRisks())
                .priceModel(OverseasAnalysisDetails.PriceModel.builder()
                        .upsidePotential(response.getSrim() != null ? response.getSrim().getGapRate() + "%" : "N/A")
                        .downsideRisk(downsideRiskStr).build())
                .qualityMetrics(OverseasAnalysisDetails.QualityMetrics.builder().pegRatio(pegRatioStr)
                        .evEbitda(evEbitdaStr).shareholderYield(shareholderYieldStr).build())
                .build();
        return response.toBuilder().summary(newSummary).overseasAnalysisDetails(details).build();
    }

    private BigDecimal parseMarketCapToBigDecimal(String marketCapStr) {
        try {
            String clean = marketCapStr.replace("$", "").replace(" B", "").trim();
            return new BigDecimal(clean).multiply(new BigDecimal("1000000000"));
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
        private BeginnerVerdict beginnerVerdict;
        private List<String> catalysts;
        private List<String> risks;
        private Map<String, Double> suggestedWeights;
        private String actionPlan;
        private String probabilityInfo;
    }

    private void calculatePriceStrategy(Response response, Summary summary, BigDecimal peg, BigDecimal yield) {
        if (response.getBand() == null || response.getBand().getMinPrice() == null)
            return;

        BigDecimal theoreticalMax = new BigDecimal(response.getBand().getMaxPrice().replace(",", ""));
        BigDecimal currentPrice = new BigDecimal(response.getCurrentPrice().replace(",", ""));

        // [Phase 4] 목표가(Target) 계산 혁신: 현재가와 적정가 중 큰 값을 기저로 사용
        // 이론적 가치를 이미 초과한 모멘텀 구간(ABOVE_BAND)에서도 의미 있는 가이드 제공
        BigDecimal baseForTarget = theoreticalMax.max(currentPrice);

        // 동적 프리미엄 산출
        BigDecimal multiplier = new BigDecimal("1.05"); // 기본 5% 프리미엄
        if (peg != null && peg.compareTo(BigDecimal.ONE) < 0)
            multiplier = multiplier.add(new BigDecimal("0.03")); // PEG < 1: +3%
        if (yield != null && yield.compareTo(new BigDecimal("3.0")) > 0)
            multiplier = multiplier.add(new BigDecimal("0.02")); // Yield > 3%: +2%

        BigDecimal targetPrice = baseForTarget.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);

        // 손절가(Stop Loss) 계산: 밴드 하단의 95% 또는 현재가의 90% 중 높은 쪽
        BigDecimal bandBottom = new BigDecimal(response.getBand().getMinPrice().replace(",", ""))
                .multiply(new BigDecimal("0.95"));
        BigDecimal currentExit = currentPrice.multiply(new BigDecimal("0.90"));
        BigDecimal stopLossPrice = bandBottom.max(currentExit).setScale(2, RoundingMode.HALF_UP);

        if (summary.getDisplay().getStrategy() == null) {
            summary.getDisplay().setStrategy(new Summary.Strategy());
        }
        summary.getDisplay().getStrategy().setResistanceZone("$" + String.format("%,.2f", targetPrice));
        summary.getDisplay().getStrategy().setSupportZone("$" + String.format("%,.2f", stopLossPrice));
    }

    private String shareholderReturnInfo(Response val, List<OverseasStockCashFlowDto> returns) {
        if (returns.isEmpty())
            return "N/A";
        BigDecimal mktCap = parseMarketCapToBigDecimal(val.getMarketCap());
        if (mktCap.compareTo(BigDecimal.ZERO) <= 0)
            return "N/A";

        BigDecimal totalReturn = returns.get(0).getRepurchaseOfCapitalStock() != null
                ? returns.get(0).getRepurchaseOfCapitalStock()
                : BigDecimal.ZERO;
        totalReturn = totalReturn
                .add(returns.get(0).getCashDividendsPaid() != null ? returns.get(0).getCashDividendsPaid()
                        : BigDecimal.ZERO);

        BigDecimal yield = totalReturn.divide(mktCap, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100));
        return String.format("%.2f%%", yield);
    }

    private String mapVerdictToLabel(String modelRating, Stance aiStance) {
        if ("BUY".equals(modelRating))
            return aiStance == Stance.ACCUMULATE ? "분할 매수" : "매수";
        if ("SELL".equals(modelRating))
            return aiStance == Stance.REDUCE ? "비중 축소" : "매도";
        return "관망";
    }

    private AiResponseJson parseAiResponse(String jsonText) {
        try {
            int start = jsonText.indexOf("{"), end = jsonText.lastIndexOf("}");
            if (start >= 0) {
                ObjectMapper mapper = new ObjectMapper();
                mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                AiResponseJson result = mapper.readValue(jsonText.substring(start, end + 1), AiResponseJson.class);
                if (result.getAiVerdict() == null)
                    result.setAiVerdict(AiVerdict.builder().stance(Stance.HOLD).riskLevel(RiskLevel.MEDIUM)
                            .guidance("분석 데이터를 파싱할 수 없습니다.").build());
                if (result.getActionPlan() == null)
                    result.setActionPlan("전략적 대응이 필요합니다.");
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
        fallback.setAiVerdict(AiVerdict.builder().stance(Stance.HOLD).riskLevel(RiskLevel.MEDIUM)
                .guidance("서버 응답 오류로 기본 분석값을 제공합니다.").build());
        fallback.setBeginnerVerdict(
                BeginnerVerdict.builder().summarySentence("잠시 후 다시 시도해 주세요.").build());
        fallback.setCatalysts(List.of("분석 중"));
        fallback.setRisks(List.of("분석 중"));
        return fallback;
    }
}
