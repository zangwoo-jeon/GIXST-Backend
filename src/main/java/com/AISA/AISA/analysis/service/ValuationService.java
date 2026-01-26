package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.dto.ValuationDto;
import com.AISA.AISA.analysis.dto.ValuationDto.*;
import com.AISA.AISA.analysis.dto.ValuationDto.Summary.AiVerdict;
import com.AISA.AISA.analysis.entity.StockAiSummary;
import com.AISA.AISA.analysis.entity.StockStaticAnalysis;
import com.AISA.AISA.analysis.repository.StockAiSummaryRepository;
import com.AISA.AISA.analysis.repository.StockStaticAnalysisRepository;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockBalanceSheet;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialRatio;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialStatement;
import com.AISA.AISA.kisStock.kisService.KisStockService;
import com.AISA.AISA.kisStock.dto.StockPrice.StockPriceDto;
import com.AISA.AISA.kisStock.dto.InvestorTrend.InvestorTrendDto;
import com.AISA.AISA.kisStock.repository.StockFinancialRatioRepository;
import com.AISA.AISA.kisStock.repository.StockFinancialStatementRepository;
import com.AISA.AISA.kisStock.repository.StockRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private final KisStockService kisStockService;
    private final GeminiService geminiService;
    private final StockAiSummaryRepository stockAiSummaryRepository;
    private final StockStaticAnalysisRepository stockStaticAnalysisRepository;

    @Autowired
    public ValuationService(StockRepository stockRepository,
            StockFinancialRatioRepository stockFinancialRatioRepository,
            StockFinancialStatementRepository stockFinancialStatementRepository,
            KisStockService kisStockService,
            GeminiService geminiService,
            StockAiSummaryRepository stockAiSummaryRepository,
            StockStaticAnalysisRepository stockStaticAnalysisRepository) {
        this.stockRepository = stockRepository;
        this.stockFinancialRatioRepository = stockFinancialRatioRepository;
        this.stockFinancialStatementRepository = stockFinancialStatementRepository;
        this.kisStockService = kisStockService;
        this.geminiService = geminiService;
        this.stockAiSummaryRepository = stockAiSummaryRepository;
        this.stockStaticAnalysisRepository = stockStaticAnalysisRepository;
    }

    @Transactional
    public Response getStandardizedValuationReport(String stockCode) {
        Request standardRequest = Request.builder().userPropensity(UserPropensity.NEUTRAL).build();
        return calculateValuationWithAi(stockCode, standardRequest);
    }

    @Transactional
    public Response calculateValuationWithAi(String stockCode, Request request) {
        Response response = calculateValuation(stockCode, request);
        String aiAnalysis = getValuationAnalysis(stockCode, response);
        InvestorTrendDto trend = null;
        try {
            trend = kisStockService.getInvestorTrend(stockCode);
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

        ValuationDto.DiscountRateInfo discountRateInfo = determineCOE(request);
        BigDecimal coe = new BigDecimal(discountRateInfo.getValue().replace("%", "")).divide(new BigDecimal(100));

        ValuationResult srim = calculateSRIM(latestRatio, history, coe, currentPrice);
        ValuationResult perModel = calculatePERModel(latestRatio, history, currentPrice);
        ValuationResult pbrModel = calculatePBRModel(latestRatio, history, coe, currentPrice);
        ValuationBand band = calculateBand(currentPrice, srim, perModel, pbrModel, stockCode, stock);
        Summary summary = generateSummary(srim, perModel, pbrModel);

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

        return Response.builder().stockCode(stockCode).stockName(stock.getStockName())
                .currentPrice(currentPrice.setScale(0, RoundingMode.HALF_UP).toString()).marketCap(marketCapStr)
                .targetReturn(discountRateInfo.getValue()).discountRate(discountRateInfo).srim(srim).per(perModel)
                .pbr(pbrModel).band(band).summary(summary).build();
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

    private ValuationDto.DiscountRateInfo determineCOE(Request request) {
        if (request != null && request.getExpectedTotalReturn() != null) {
            String value = BigDecimal.valueOf(request.getExpectedTotalReturn()).multiply(new BigDecimal(100))
                    .setScale(1, RoundingMode.HALF_UP) + "%";
            return ValuationDto.DiscountRateInfo.builder().profile("CUSTOM").value(value)
                    .basis("User defined custom override").source("USER_OVERRIDE").build();
        }
        if (request != null && request.getUserPropensity() != null) {
            switch (request.getUserPropensity()) {
                case CONSERVATIVE:
                    return ValuationDto.DiscountRateInfo.builder().profile("CONSERVATIVE").value("10.0%")
                            .basis("Safety First").source("PROFILE_DEFAULT").build();
                case AGGRESSIVE:
                    return ValuationDto.DiscountRateInfo.builder().profile("AGGRESSIVE").value("6.0%")
                            .basis("Growth Focused").source("PROFILE_DEFAULT").build();
                default:
                    break;
            }
        }
        return ValuationDto.DiscountRateInfo.builder().profile("NEUTRAL").value("8.0%").basis("Market Reference")
                .source("PROFILE_DEFAULT").build();
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
        if (targetPrice.compareTo(BigDecimal.ZERO) <= 0 || roeDecimal.compareTo(coe) < 0) {
            return ValuationResult.builder().price("0").gapRate("0").verdict("N/A").description(desc + " (ROE < COE)")
                    .available(false).reason("ROE below cost of equity").roeType(roeType).build();
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
    public String getValuationAnalysis(String stockCode, Response val) {
        BigDecimal cur = new BigDecimal(val.getCurrentPrice().replace(",", ""));
        Optional<StockAiSummary> cached = stockAiSummaryRepository.findByStockCode(stockCode);
        if (cached.isPresent() && !cached.get().isExpired(720)) {
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
        }
        String analysis = generateValuationAnalysisText(val, ratio, null, null, trend);

        AiResponseJson aiJson = parseAiResponse(analysis);
        String displayVerdict = val.getSummary().getOverallVerdict(), displayLabel = mapVerdictToLabel(displayVerdict,
                aiJson.getAiVerdict() != null ? aiJson.getAiVerdict().getStance() : null);
        if (cached.isPresent()) {
            cached.get().updateValuationAnalysis(analysis, cur, displayVerdict, displayLabel,
                    aiJson.getBeginnerVerdict().getSummarySentence(), aiJson.getAiVerdict().getRiskLevel().name(),
                    trend != null ? trend.getSupplyScore() : 0, trend != null ? trend.getForeignerZScore() : 0);
            stockAiSummaryRepository.save(cached.get());
        } else {
            stockAiSummaryRepository.save(StockAiSummary.builder().stockCode(stockCode).valuationAnalysis(analysis)
                    .referencePrice(cur).displayVerdict(displayVerdict).displayLabel(displayLabel)
                    .displaySummary(aiJson.getBeginnerVerdict().getSummarySentence())
                    .displayRisk(aiJson.getAiVerdict().getRiskLevel().name()).lastModifiedDate(LocalDateTime.now())
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
            InvestorTrendDto investorTrend) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("너는 '대한민국 주식 투자 전략가(Critical Strategist)'이다. 국내 시장 특수성을 고려하여 비판적으로 분석하라.\n");
        prompt.append(String.format("종목: %s (%s), 현재가: %s원\n", val.getStockName(), val.getStockCode(),
                val.getCurrentPrice()));
        if (latestRatio != null)
            prompt.append(String.format("[펀더멘털] PER: %.2f, PBR: %.2f, ROE: %.2f%%, EV/EBITDA: %s\n",
                    latestRatio.getPer(), latestRatio.getPbr(), latestRatio.getRoe(),
                    latestRatio.getEvEbitda() != null ? latestRatio.getEvEbitda() + "x" : "N/A"));
        prompt.append("\n[시스템 Valuation 데이터 (Hard Data)]\n");
        prompt.append(
                String.format("- S-RIM 적정가: %s원 (%s)\n", val.getSrim().getPrice(), val.getSrim().getDescription()));
        prompt.append(String.format("- PER 과거평균: %s원 (%s)\n", val.getPer().getPrice(), val.getPer().getDescription()));
        prompt.append(String.format("- PBR 과거평균: %s원 (%s)\n", val.getPbr().getPrice(), val.getPbr().getDescription()));

        // 가격 위치 context 주입
        if (val.getBand() != null) {
            prompt.append(String.format("- 현재가 밴드 위치: %s (Status: %s)\n", val.getBand().getPosition(),
                    val.getBand().getStatus()));
        }

        // 품질 지표 컨텍스트 주입 (국내)
        if (latestRatio != null) {
            BigDecimal peg = BigDecimal.ZERO;
            if (latestRatio.getPer() != null && latestRatio.getSalesGrowth() != null
                    && latestRatio.getSalesGrowth().compareTo(BigDecimal.ZERO) > 0) {
                peg = latestRatio.getPer().divide(latestRatio.getSalesGrowth(), 2, RoundingMode.HALF_UP).abs();
            }
            prompt.append(String.format("- PEG (추정): %.2f, EV/EBITDA: %s\n", peg,
                    latestRatio.getEvEbitda() != null ? latestRatio.getEvEbitda() + "x" : "N/A"));
        }

        prompt.append("\n[전략가 미션]\n");
        prompt.append(
                "1. **지표 등급 요약**: PEG, EV/EBITDA, 배당성향 등을 종합하여 '가성비' 및 '투자 매력도' 관점에서 등급화(우수/보통/주의)하여 1줄 평을 작성하라.\n");
        prompt.append(
                "2. **격차 분석 및 프리미엄 해석**: 시스템 S-RIM 가격과 현재가 사이의 괴리를 해석하라. 만약 Status가 ABOVE_BAND라면 '비싸다'는 표현 대신 '시장의 미래 성장 프리미엄이 X% 추가 반영된 상태'라고 전문적으로 브리핑하라.\n");
        prompt.append(String.format("3. **생애주기 해석**: 시스템이 판정한 [%s] 티어의 타당성을 브리핑하라.\n",
                val.getSrim().getDescription().split("]")[0] + "]"));
        prompt.append("4. **지표 크로스 체크**: EV/EBITDA 등과 비교하여 가장 적합한 지표를 논하라.\n");
        prompt.append("5. **정당성 검증**: 데이터 한계를 인정하되 전문가다운 전략적 매수/매도 시점(Timing) 제안.\n");
        prompt.append("\n[출력 포맷 (JSON)]\n");
        prompt.append("응답은 오직 순수한 JSON 형식이어야 하며, 필드명은 정확히 일치해야 합니다.\n");
        prompt.append(
                "```json\n{\n  \"keyInsight\": \"30자 이내 핵심 요약\",\n  \"aiVerdict\": {\"stance\": \"BUY/ACCUMULATE/HOLD/REDUCE/SELL\", \"timing\": \"EARLY/MID/LATE\", \"riskLevel\": \"LOW/MEDIUM/HIGH\", \"guidance\": \"분석 의견\"},\n  \"suggestedWeights\": {\"srim\": 0.15, \"per\": 0.55, \"pbr\": 0.3},\n  \"beginnerVerdict\": {\"summarySentence\": \"PEG와 지표 등급을 포함한 친근한 투자 포인트 요약\"},\n  \"catalysts\": [\"호재 1\"], \"risks\": [\"악재 1\"]\n}\n```\n");
        return geminiService.generateAdvice(prompt.toString());
    }

    private Response buildResponseWithAi(Response response, String aiAnalysis, InvestorTrendDto trend) {
        AiResponseJson aiJson = parseAiResponse(aiAnalysis);
        String overallVerdict = (aiJson.getAiVerdict() != null && aiJson.getAiVerdict().getStance() != null)
                ? aiJson.getAiVerdict().getStance().name()
                : "HOLD";
        Summary.Display display = Summary.Display.builder().verdict(overallVerdict)
                .verdictLabel(overallVerdict.contains("BUY") ? "매수" : overallVerdict.contains("SELL") ? "매도" : "관망")
                .summary(aiJson.getBeginnerVerdict() != null ? aiJson.getBeginnerVerdict().getSummarySentence()
                        : "분석 완료")
                .risk(aiJson.getAiVerdict() != null && aiJson.getAiVerdict().getRiskLevel() != null
                        ? aiJson.getAiVerdict().getRiskLevel().name()
                        : "MEDIUM")
                .build();
        Summary newSummary = Summary.builder().overallVerdict(overallVerdict).confidence("HIGH")
                .keyInsight(aiJson.getKeyInsight() != null ? aiJson.getKeyInsight() : "AI 분석 리포트")
                .verdicts(Summary.Verdicts.builder().aiVerdict(aiJson.getAiVerdict()).build())
                .beginnerVerdict(aiJson.getBeginnerVerdict()).display(display).build();

        // [New Phase 2] 가격 전략 및 품질 지표 연동
        BigDecimal pegVal = null;
        BigDecimal yieldVal = BigDecimal.ZERO;

        StockFinancialRatio latestRatio = null;
        try {
            latestRatio = stockFinancialRatioRepository
                    .findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(response.getStockCode(), "0");
        } catch (Exception e) {
            log.error("Failed to fetch latest financial ratio for stockCode: {}", response.getStockCode(), e);
        }

        if (latestRatio != null) {
            if (latestRatio.getPer() != null && latestRatio.getSalesGrowth() != null
                    && latestRatio.getSalesGrowth().compareTo(BigDecimal.ZERO) > 0) {
                pegVal = latestRatio.getPer().divide(latestRatio.getSalesGrowth(), 2, RoundingMode.HALF_UP).abs();
            }
        }

        // 전략 가격 계산 (목표가, 손절가)
        calculatePriceStrategy(response, newSummary, pegVal, yieldVal);

        String evEbitdaStr = "N/A", downsideStr = "N/A";
        try {
            if (latestRatio != null && latestRatio.getEvEbitda() != null)
                evEbitdaStr = latestRatio.getEvEbitda() + "x";
            if (response.getBand() != null && response.getBand().getMinPrice() != null) {
                BigDecimal min = new BigDecimal(response.getBand().getMinPrice()),
                        cur = new BigDecimal(response.getCurrentPrice().replace(",", ""));
                if (cur.compareTo(BigDecimal.ZERO) > 0)
                    downsideStr = min.subtract(cur).divide(cur, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100))
                            .setScale(2, RoundingMode.HALF_UP).toString() + "%";
            }
        } catch (Exception e) {
        }

        AnalysisDetails.PriceModel pmDto = AnalysisDetails.PriceModel.builder()
                .upsidePotential("0%")
                .downsideRisk(downsideStr)
                .build();

        // [Phase 3] priceModel 수치 정합성 수정 (목표가/손절가 기준)
        try {
            BigDecimal cur = new BigDecimal(response.getCurrentPrice().replace(",", ""));
            if (newSummary.getDisplay().getTargetPrice() != null) {
                BigDecimal tgt = new BigDecimal(newSummary.getDisplay().getTargetPrice().replaceAll("[^0-9]", ""));
                BigDecimal upside = tgt.subtract(cur).divide(cur, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal(100));
                pmDto.setUpsidePotential(String.format("%.2f%%", upside));
            }
            if (newSummary.getDisplay().getStopLossPrice() != null) {
                BigDecimal sl = new BigDecimal(newSummary.getDisplay().getStopLossPrice().replaceAll("[^0-9]", ""));
                BigDecimal downside = sl.subtract(cur).divide(cur, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal(100));
                pmDto.setDownsideRisk(String.format("%.2f%%", downside));
            }
        } catch (Exception e) {
            log.warn("Failed to refine price model: {}", e.getMessage());
        }

        AnalysisDetails details = AnalysisDetails.builder().investmentTerm("6-12 Months")
                .catalysts(aiJson.getCatalysts()).risks(aiJson.getRisks())
                .priceModel(pmDto)
                .qualityMetrics(AnalysisDetails.QualityMetrics.builder().evEbitda(evEbitdaStr).build())
                .investorTrend(trend).build();

        if (aiJson.getSuggestedWeights() != null && response.getBand() != null)
            response.getBand().setWeights(aiJson.getSuggestedWeights());

        return response.toBuilder().summary(newSummary).analysisDetails(details).build();
    }

    private String mapVerdictToLabel(String modelRating, ValuationDto.Stance aiStance) {
        if ("BUY".equals(modelRating))
            return aiStance == ValuationDto.Stance.ACCUMULATE ? "분할 매수" : "매수";
        if ("SELL".equals(modelRating))
            return aiStance == ValuationDto.Stance.REDUCE ? "비중 축소" : "매도";
        return "관망";
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
        private Map<String, Double> suggestedWeights;
    }

    private void calculatePriceStrategy(Response response, Summary summary, BigDecimal peg, BigDecimal yield) {
        if (response.getBand() == null || response.getBand().getMinPrice() == null)
            return;

        BigDecimal theoreticalMax = new BigDecimal(response.getBand().getMaxPrice().replace(",", ""));
        BigDecimal currentPrice = new BigDecimal(response.getCurrentPrice().replace(",", ""));

        // [Phase 4] 목표가(Target) 계산 혁신: 현재가와 적정가 중 큰 값을 기저로 사용
        BigDecimal baseForTarget = theoreticalMax.max(currentPrice);

        // 동적 프리미엄 산출
        BigDecimal multiplier = new BigDecimal("1.05"); // 기본 5% 프리미엄
        if (peg != null && peg.compareTo(BigDecimal.ONE) < 0)
            multiplier = multiplier.add(new BigDecimal("0.02")); // PEG < 1: +2%

        BigDecimal targetPrice = baseForTarget.multiply(multiplier).setScale(0, RoundingMode.HALF_UP);

        // 손절가(Stop Loss) 계산: 밴드 하단의 95% 또는 현재가의 90% 중 높은 쪽
        BigDecimal bandBottom = new BigDecimal(response.getBand().getMinPrice().replace(",", ""))
                .multiply(new BigDecimal("0.95"));
        BigDecimal currentExit = currentPrice.multiply(new BigDecimal("0.90"));
        BigDecimal stopLossPrice = bandBottom.max(currentExit).setScale(0, RoundingMode.HALF_UP);

        summary.getDisplay().setTargetPrice(String.format("%,d원", targetPrice.longValue()));
        summary.getDisplay().setStopLossPrice(String.format("%,d원", stopLossPrice.longValue()));
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
                ValuationDto.Summary.BeginnerVerdict.builder().summarySentence("잠시 후 다시 시도해 주세요.").build());
        fallback.setCatalysts(List.of("분석 중"));
        fallback.setRisks(List.of("분석 중"));
        return fallback;
    }

    private boolean isValidPrice(String price) {
        return price != null && !price.equals("0");
    }
}
