package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.dto.OverseasQualityValuationDto.*;
import com.AISA.AISA.analysis.entity.OverseasQualityReport;
import com.AISA.AISA.analysis.repository.OverseasQualityReportRepository;
import com.AISA.AISA.kisOverseasStock.entity.*;
import com.AISA.AISA.kisOverseasStock.repository.*;
import com.AISA.AISA.kisOverseasStock.service.KisOverseasStockInformationService;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class OverseasQualityAnalysisService {

    private final KisOverseasStockRepository overseasStockRepository;
    private final KisOverseasStockFinancialRatioRepository financialRatioRepository;
    private final KisOverseasStockFinancialStatementRepository financialStatementRepository;
    private final KisOverseasStockInformationService overseasService;
    private final GeminiService geminiService;
    private final KisOverseasStockCashFlowRepository cashFlowRepository;
    private final KisOverseasStockBalanceSheetRepository balanceSheetRepository;
    private final OverseasStockTradingMultipleRepository tradingMultipleRepository;
    private final OverseasQualityReportRepository overseasQualityReportRepository;

    public OverseasQualityAnalysisService(
            KisOverseasStockRepository overseasStockRepository,
            KisOverseasStockFinancialRatioRepository financialRatioRepository,
            KisOverseasStockFinancialStatementRepository financialStatementRepository,
            KisOverseasStockInformationService overseasService,
            GeminiService geminiService,
            KisOverseasStockCashFlowRepository cashFlowRepository,
            KisOverseasStockBalanceSheetRepository balanceSheetRepository,
            OverseasStockTradingMultipleRepository tradingMultipleRepository,
            OverseasQualityReportRepository overseasQualityReportRepository) {
        this.overseasStockRepository = overseasStockRepository;
        this.financialRatioRepository = financialRatioRepository;
        this.financialStatementRepository = financialStatementRepository;
        this.overseasService = overseasService;
        this.geminiService = geminiService;
        this.cashFlowRepository = cashFlowRepository;
        this.balanceSheetRepository = balanceSheetRepository;
        this.tradingMultipleRepository = tradingMultipleRepository;
        this.overseasQualityReportRepository = overseasQualityReportRepository;
    }

    @Transactional
    public QualityReportResponse calculateQualityAnalysis(String stockCode, boolean forceRefresh) {
        log.info("Checking quality analysis refresh for stock: {}", stockCode);

        // Fetch early for trigger check
        BigDecimal currentPrice = BigDecimal.ZERO;
        try {
            currentPrice = overseasService.getCurrentPrice(stockCode);
        } catch (Exception e) {
            log.warn("Failed to fetch current price for trigger check: {}", stockCode);
        }

        OverseasStockFinancialRatio latestRatio = financialRatioRepository
                .findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
        String latestStacYymm = latestRatio != null ? latestRatio.getStacYymm() : null;

        if (!forceRefresh) {
            OverseasQualityReport savedReport = overseasQualityReportRepository.findByStockCode(stockCode).orElse(null);
            if (savedReport != null) {
                boolean shouldRefresh = false;

                // 1. Weekly Refresh: 7 days
                if (savedReport.getLastModifiedDate().isBefore(LocalDateTime.now().minusDays(7))) {
                    log.info("Refreshing due to time trigger (>7 days) for {}", stockCode);
                    shouldRefresh = true;
                }
                // 2. Price Trigger: 10% change
                else if (savedReport.getLastPrice() != null && currentPrice.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal lastPrice = savedReport.getLastPrice();
                    BigDecimal changeRatio = currentPrice.subtract(lastPrice).abs()
                            .divide(lastPrice, 4, RoundingMode.HALF_UP);
                    if (changeRatio.compareTo(new BigDecimal("0.10")) >= 0) {
                        log.info("Refreshing due to price trigger (>10% change) for {}: {} -> {}", stockCode, lastPrice,
                                currentPrice);
                        shouldRefresh = true;
                    }
                }
                // 3. Earnings Trigger: stacYymm change
                else if (latestStacYymm != null && !latestStacYymm.equals(savedReport.getLastStacYymm())) {
                    log.info("Refreshing due to earnings trigger (stacYymm change) for {}: {} -> {}", stockCode,
                            savedReport.getLastStacYymm(), latestStacYymm);
                    shouldRefresh = true;
                }

                if (!shouldRefresh) {
                    try {
                        return new ObjectMapper().readValue(savedReport.getFullReportJson(),
                                QualityReportResponse.class);
                    } catch (Exception e) {
                        log.error("Failed to parse saved report JSON for {}", stockCode);
                    }
                }
            }
        }

        log.info("Starting fresh quality analysis for stock: {}", stockCode);

        Stock stock = overseasStockRepository.findByStockCode(stockCode)
                .orElseThrow(() -> new IllegalArgumentException("Overseas Stock not found: " + stockCode));

        // 1. Fetch Fundamental Data
        List<OverseasStockFinancialStatement> stmts = financialStatementRepository
                .findTop5ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
        OverseasStockTradingMultiple multiples = tradingMultipleRepository.findByStockCode(stockCode).orElse(null);
        List<OverseasStockBalanceSheet> balSheets = balanceSheetRepository
                .findByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");

        if (latestRatio == null || stmts.isEmpty()) {
            return QualityReportResponse.builder().stockCode(stockCode).stockName(stock.getStockName()).build();
        }

        String marketCap = getRealTimeMarketCap(stockCode);
        FcfMetrics fcfMetrics = getLatestNonNullFcfMetrics(stockCode, marketCap);

        // 2. Step 1: Business Quality Score (0-100)
        String industryCode = stock.getStockIndustries().stream()
                .filter(si -> si.isPrimary())
                .findFirst()
                .or(() -> stock.getStockIndustries().stream().findFirst())
                .map(si -> si.getSubIndustry().getIndustry().getCode())
                .orElse(null);
        double wacc = estimateWacc(industryCode);
        QualityScoreResult qualityResult = calculateQualityMetrics(stockCode, latestRatio, stmts, balSheets, wacc);
        BusinessQuality quality = qualityResult.getQuality();
        GradeRationale rationale = qualityResult.getRationale();

        // 3. Step 2: Valuation Context
        ValuationContext valContext = calculateValuationContext(stockCode, currentPrice, fcfMetrics, multiples);

        // 4. Generate AI Prompt & Get Report
        String prompt = generateQualityPrompt(stock, quality, valContext, fcfMetrics, rationale);
        String aiAnalysis = geminiService.generateAdvice(prompt);

        // 5. Build Final Response
        QualityReportResponse finalResponse = mapToQualityReport(stockCode, stock.getStockName(),
                currentPrice.toString(), marketCap, quality,
                valContext, aiAnalysis, rationale);

        saveReport(stockCode, finalResponse, currentPrice, latestStacYymm);
        return finalResponse;
    }

    private void saveReport(String stockCode, QualityReportResponse response, BigDecimal currentPrice,
            String latestStacYymm) {
        try {
            OverseasQualityReport report = overseasQualityReportRepository
                    .findByStockCode(stockCode)
                    .orElse(OverseasQualityReport.builder().stockCode(stockCode).build());

            LongTermVerdict verdict = response.getLongTermVerdict();
            report.setStockName(response.getStockName());
            report.setCurrentPrice(response.getCurrentPrice());
            report.setMarketCap(response.getMarketCap());
            report.setQualityGrade(verdict.getQualityGrade());
            report.setQualityScore(response.getBusinessQuality().getScore());
            report.setValuationStatus(verdict.getValuationStatus());
            report.setInvestmentAttractiveness(verdict.getInvestmentAttractiveness());
            report.setFullReportJson(new ObjectMapper().writeValueAsString(response));
            report.setLastModifiedDate(LocalDateTime.now());
            report.setLastPrice(currentPrice);
            report.setLastStacYymm(latestStacYymm);

            overseasQualityReportRepository.save(report);
        } catch (Exception e) {
            log.error("Failed to save quality report: {}", e.getMessage());
        }
    }

    private QualityScoreResult calculateQualityMetrics(String stockCode, OverseasStockFinancialRatio ratio,
            List<OverseasStockFinancialStatement> stmts,
            List<OverseasStockBalanceSheet> balSheets, double wacc) {
        double score = 0;
        List<String> positives = new ArrayList<>();
        List<String> negatives = new ArrayList<>();

        // A. ROIC vs WACC (30%)
        RoicAnalysisResult roicResult = calculateROICDetails(stmts, balSheets);
        double roic = roicResult.getTripleYearAvgRoic();
        double spread = roic - wacc;

        if (spread >= 5.0) {
            score += 30;
            positives.add(String.format("강력한 초과수익 창출 (ROIC-WACC Spread %.1f%%p) (+30점)", spread));
        } else if (spread >= 0.0) {
            score += 22;
            positives.add(String.format("자본비용을 상회하는 초과수익 유지 (Spread %.1f%%p) (+22점)", spread));
        } else if (spread >= -5.0) {
            score += 10;
            positives.add(String.format("수익성이 자본비용에 근접함 (Spread %.1f%%p) (+10점)", spread));
        } else {
            negatives.add(String.format("수익성이 자본비용보다 낮아 가치 파괴적 구조 (Spread %.1f%%p, 0점)", spread));
        }

        // B. FCF Stability (25%)
        var fcfHistoryRaw = cashFlowRepository.findByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
        var validFcfHistory = fcfHistoryRaw.stream()
                .filter(f -> f.getFcf() != null && f.getFcf().compareTo(BigDecimal.ZERO) != 0)
                .toList();

        long positiveCount = validFcfHistory.stream().limit(3)
                .filter(f -> f.getFcf().compareTo(BigDecimal.ZERO) > 0)
                .count();

        if (positiveCount == 3) {
            score += 25;
            positives.add("최근 3년 연속 양(+)의 잉여현금흐름 창출 (+25점)");
        } else if (positiveCount == 2) {
            score += 18;
            positives.add("최근 3년 중 2회 양(+)의 잉여현금흐름 창출 (+18점)");
        } else if (positiveCount == 1) {
            score += 8;
            positives.add("최근 3년 중 1회 양(+)의 잉여현금흐름 창출 (+8점)");
        } else {
            negatives.add("최근 3년 내 현금 창출 능력 부재 또는 데이터 부족 (0점)");
        }

        // C. Growth Sustainability (20%)
        if (stmts.size() >= 3) {
            double revEnd = stmts.get(0).getTotalRevenue().doubleValue();
            double revStart = stmts.get(2).getTotalRevenue().doubleValue();
            if (revStart > 0 && revEnd > 0) {
                double cagr = (Math.pow(revEnd / revStart, 1.0 / 2.0) - 1.0) * 100.0;
                if (cagr >= 10.0) {
                    score += 20;
                    positives.add(String.format("강력한 매출 성장세 (3년 CAGR %.1f%%) (+20점)", cagr));
                } else if (cagr >= 3.0) {
                    score += 15;
                    positives.add(String.format("안정적인 매출 성장 (3년 CAGR %.1f%%) (+15점)", cagr));
                } else if (cagr > 0.0) {
                    score += 8;
                    positives.add(String.format("완만한 매출 성장 (3년 CAGR %.1f%%) (+8점)", cagr));
                } else {
                    negatives.add(String.format("매출 정체 또는 역성장 (3년 CAGR %.1f%%, 0점)", cagr));
                }
            } else {
                negatives.add("매출 데이터 부족으로 성장성 평가 불가 (0점)");
            }
        } else {
            negatives.add("데이터 부족으로 인한 성장성 평가 제외 (0점)");
        }

        // D. Financial Stability (15%)
        double debtRatio = -1.0; // -1 = 데이터 없음
        if (!balSheets.isEmpty()) {
            var bs = balSheets.get(0);
            if (bs.getTotalLiabilities() != null && bs.getTotalCapital() != null
                    && bs.getTotalCapital().compareTo(BigDecimal.ZERO) > 0) {
                debtRatio = bs.getTotalLiabilities().doubleValue() / bs.getTotalCapital().doubleValue();
                if (debtRatio < 1.0) {
                    score += 15;
                    positives.add(String.format("매우 건전한 재무 구조 (부채비율 %.1f%%) (+15점)", debtRatio * 100));
                } else if (debtRatio < 2.0) {
                    score += 10;
                    positives.add(String.format("관리 가능한 부채 수준 (부채비율 %.1f%%) (+10점)", debtRatio * 100));
                } else if (debtRatio < 3.0) {
                    score += 5;
                    positives.add(String.format("부채 비율 다소 높음 (부채비율 %.1f%%) (+5점)", debtRatio * 100));
                } else {
                    negatives.add(String.format("높은 부채 비율로 인한 재무 리스크 존재 (부채비율 %.1f%%, 0점)", debtRatio * 100));
                }
            }
        }

        // E. Dilution (10%)
        double shareGrowth = calculateShareGrowth(stockCode, ratio, stmts);
        if (shareGrowth <= 0.1) { // 0.1% buffer for stability
            score += 10;
            positives.add("발행 주식 수 감소 또는 유지로 주주 가치 보호 (+10점)");
        } else if (shareGrowth < 5.0) {
            score += 6;
            positives.add(String.format("발행 주식 수 증가 제한적 (연간 %.1f%% 증가) (+6점)", shareGrowth));
        } else {
            negatives.add(String.format("잦은 유상증자 또는 주식 보상으로 인한 가치 희석 (연간 %.1f%% 증가, 0점)", shareGrowth));
        }

        BusinessQuality quality = BusinessQuality.builder()
                .score((int) Math.max(0, score))
                .trajectory(calculateTrajectory(roic, stmts, balSheets))
                .roicVsWacc(roicResult.getRoicDisplay() + String.format(" vs WACC %.1f%%", wacc))
                .roicWaccSpread(String.format("%+.1f%%p", spread))
                .sustainabilityWarning(roicResult.getSustainabilityWarning())
                .fcfTrend(positiveCount >= 2 ? "안정적 (최근 3년 중 " + positiveCount + "년 흑자)" : "변동성 있음")
                .balanceSheet(debtRatio < 0 ? "데이터 없음"
                        : debtRatio < 1.0 ? "건전 (순현금 혹은 저부채)"
                        : debtRatio < 2.0 ? "보통"
                        : "주의 (고부채)")
                .dilution(shareGrowth <= 0.1 ? "안정적" : "보통")
                .build();

        return QualityScoreResult.builder()
                .quality(quality)
                .rationale(GradeRationale.builder()
                        .positiveFactors(positives)
                        .negativeFactors(negatives)
                        .build())
                .build();
    }

    private double calculateShareGrowth(String stockCode, OverseasStockFinancialRatio ratio,
            List<OverseasStockFinancialStatement> stmts) {
        if (stmts.size() < 2)
            return 0.0;
        try {
            OverseasStockFinancialStatement currentStmt = stmts.get(0);
            OverseasStockFinancialStatement prevStmt = stmts.get(1);

            // Fetch ratios to get EPS
            List<OverseasStockFinancialRatio> ratios = financialRatioRepository
                    .findTop5ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
            if (ratios.size() < 2)
                return 0.0;

            OverseasStockFinancialRatio currentRatio = ratios.get(0);
            OverseasStockFinancialRatio prevRatio = ratios.get(1);

            if (currentRatio.getEpsUsd() == null || prevRatio.getEpsUsd() == null ||
                    currentRatio.getEpsUsd().compareTo(BigDecimal.ZERO) <= 0 ||
                    prevRatio.getEpsUsd().compareTo(BigDecimal.ZERO) <= 0) {
                return 0.0;
            }

            double currentShares = currentStmt.getNetIncome().doubleValue() / currentRatio.getEpsUsd().doubleValue();
            double prevShares = prevStmt.getNetIncome().doubleValue() / prevRatio.getEpsUsd().doubleValue();

            if (prevShares <= 0)
                return 0.0;

            return (currentShares / prevShares - 1.0) * 100.0;
        } catch (Exception e) {
            log.warn("Failed to calculate share growth for {}: {}", stockCode, e.getMessage());
            return 0.0;
        }
    }

    private String calculateTrajectory(double currentRoic, List<OverseasStockFinancialStatement> stmts,
            List<OverseasStockBalanceSheet> balSheets) {
        if (stmts.size() < 3 || balSheets.size() < 4) // Need i=2(stmt) and i=2,3(bs) for average
            return "Stable";
        try {
            // ROIC from 2 years ago (t-2)
            double oldRoic = computeSingleROIC(stmts.get(2), balSheets.get(2),
                    balSheets.size() > 3 ? balSheets.get(3) : null);
            if (oldRoic < -999.0)
                return "Stable";

            if (currentRoic > oldRoic + 2.0)
                return "Improving";
            if (currentRoic < oldRoic - 2.0)
                return "Deteriorating";
        } catch (Exception e) {
            log.warn("Failed to calculate trajectory (currentRoic={}): {}", currentRoic, e.getMessage());
        }
        return "Stable";
    }

    private ValuationContext calculateValuationContext(String stockCode, BigDecimal currentPrice,
            FcfMetrics fcf,
            OverseasStockTradingMultiple multiples) {
        double fcfYield = parsePercent(fcf.getFcfYield());
        double evEbitda = (multiples != null && multiples.getEvEbitda() != null) ? multiples.getEvEbitda() : -1.0;

        boolean fcfCheap = fcfYield > 6.0;
        boolean fcfExpensive = fcfYield > 0 && fcfYield < 2.5;
        boolean evExpensive = evEbitda > 0 && evEbitda > 35.0;

        String status;
        if (fcfCheap && !evExpensive) {
            status = "저평가";
        } else if (fcfExpensive || evExpensive) {
            status = "고평가";
        } else {
            status = "적정"; // fcfCheap && evExpensive 포함: FCF는 매력적이나 멀티플이 비쌈
        }

        String evDisplay;
        if (multiples != null && multiples.getEvEbitda() != null) {
            String evLabel = evExpensive ? "크게 상회 (고평가 신호)"
                    : (evEbitda > 20 ? "상회" : "정상 범위");
            evDisplay = String.format("%.1fx (%s)", evEbitda, evLabel);
        } else {
            evDisplay = "N/A";
        }

        return ValuationContext.builder()
                .status(status)
                .fcfYield(fcf.getFcfYield())
                .evEbitdaVsHistory(evDisplay)
                .build();
    }

    private String generateQualityPrompt(Stock stock, BusinessQuality quality, ValuationContext val, FcfMetrics fcf,
            GradeRationale rationale) {
        StringBuilder sb = new StringBuilder();
        sb.append("너는 '기업 품질 및 가치 분석가(Quality & Valuation Explainer)'이다.\n");
        sb.append("너의 역할은 판정(Grade, Valuation)을 내리는 것이 아니라, 이미 엔진에 의해 산출된 판정 결과의 '이유'를 투자자에게 데이터 기반으로 설명하는 것이다.\n\n");

        sb.append("### [분석 대상 및 엔진 판정 결과]\n");
        sb.append(String.format("- 기업명: %s (%s)\n", stock.getStockName(), stock.getStockCode()));
        sb.append(String.format("- [Quality Grade]: %d/100 (추세: %s)\n", quality.getScore(), quality.getTrajectory()));
        sb.append(String.format("- [Valuation Status]: %s (FCF Yield: %s)\n", val.getStatus(), val.getFcfYield()));

        sb.append("\n### [엔진 데이터 상세]\n");
        if (quality.getSustainabilityWarning() != null) {
            sb.append(String.format("- 주의/경고: %s\n", quality.getSustainabilityWarning()));
        }
        sb.append(String.format("- ROIC-WACC Spread: %s\n", quality.getRoicWaccSpread()));
        sb.append(String.format("- ROIC/WACC Details: %s\n", quality.getRoicVsWacc()));
        sb.append(String.format("- 현금흐름 추세: %s\n", quality.getFcfTrend()));

        sb.append("\n### [등급 산정 근거 (Scoring Rationale)]\n");
        sb.append("- 긍정적 요인:\n");
        rationale.getPositiveFactors().forEach(f -> sb.append("  * ").append(f).append("\n"));
        sb.append("- 부정적 요인:\n");
        rationale.getNegativeFactors().forEach(f -> sb.append("  * ").append(f).append("\n"));

        sb.append("\n### [분석 가이드: Explainer Mode]\n");
        sb.append("1. **Quality Thesis**: 왜 엔진이 이 기업에게 위와 같은 Quality 등급을 부여했는지 '해자'와 '자본효율성' 관점에서 설명하라.\n");
        sb.append("2. **Valuation Thesis**: 왜 엔진이 현재 가격을 위와 같이 평가했는지 데이터 기반으로 설명하라.\n");
        sb.append("3. **주의**: 판정 결과를 부정하거나 바꾸려 하지 마라. 너는 엔진의 논리를 대변하는 해설가이다.\n");
        sb.append("4. **JSON Output**: 반드시 아래 JSON 형식을 지켜라 (suitability 필드에 위 분석 요약).\n");
        sb.append(
                "5. **Thesis Monitoring & Re-entry**: 투자 가설 붕괴 조건 3가지와, 현재 관망/기다림 단계라면 다시 매수 검토할 수 있는 '재진입 조건(reEntryCondition)'을 명확히 제시하라.\n");

        sb.append("\n[OUTPUT JSON SCHEMA]\n");
        sb.append("{\n");
        sb.append("  \"suitability\": \"장기보유 적합성 핵심 논리 (단호하게)\",\n");
        sb.append("  \"moatDescription\": \"구조적 경쟁 우위 분석 (2-3문장)\",\n");
        sb.append("  \"monitoringPoints\": [\"조건1\", \"조건2\", \"조건3\"],\n");
        sb.append("  \"reEntryCondition\": \"재진입 또는 매수 재검토가 가능한 구체적 조건\"\n");
        sb.append("}\n");

        return sb.toString();
    }

    private String determineAttractiveness(String grade, String valStatus) {
        // A Rank
        if ("A".equals(grade)) {
            if ("저평가".equals(valStatus))
                return "Very Attractive";
            if ("적정".equals(valStatus))
                return "Attractive";
            return "Neutral";
        }
        // B Rank
        if ("B".equals(grade)) {
            if ("저평가".equals(valStatus))
                return "Attractive";
            if ("적정".equals(valStatus))
                return "Neutral";
            return "Low";
        }
        // C Rank
        if ("C".equals(grade)) {
            if ("저평가".equals(valStatus))
                return "Speculative";
            return "Avoid";
        }
        // D Rank or fallback
        return "Avoid";
    }

    private String determineAction(String attractiveness) {
        return switch (attractiveness) {
            case "Very Attractive" -> "Strong Buy";
            case "Attractive" -> "Buy";
            case "Neutral" -> "Hold";
            case "Low" -> "Reduce";
            case "Speculative" -> "Speculative Buy";
            default -> "Sell"; // "Avoid"
        };
    }

    private QualityReportResponse mapToQualityReport(String stockCode, String stockName, String price, String marketCap,
            BusinessQuality quality, ValuationContext val, String aiText,
            GradeRationale rationale) {
        try {
            AiQualityJson json = new ObjectMapper().readValue(extractJsonBlock(aiText), AiQualityJson.class);

            quality.setMoatDescription(json.moatDescription);

            GradeResult gradeRes = calculateGrade(quality.getScore());
            String attractiveness = determineAttractiveness(gradeRes.getGrade(), val.getStatus());

            return QualityReportResponse.builder()
                    .stockCode(stockCode)
                    .stockName(stockName)
                    .currentPrice(price)
                    .marketCap(marketCap)
                    .longTermVerdict(LongTermVerdict.builder()
                            .qualityGrade(gradeRes.getGrade())
                            .qualityDefinition(gradeRes.getDefinition())
                            .valuationStatus(val.getStatus())
                            .investmentAttractiveness(attractiveness)
                            .suitability(json.suitability)
                            .action(determineAction(attractiveness))
                            .reEntryCondition(json.reEntryCondition)
                            .holdingHorizon("3년 이상")
                            .gradeRationale(rationale)
                            .build())
                    .businessQuality(quality)
                    .valuationContext(val)
                    .thesisMonitoring(json.monitoringPoints)
                    .build();
        } catch (Exception e) {
            log.error("AI Parsing Error: {}", e.getMessage());
            return QualityReportResponse.builder().stockCode(stockCode).stockName(stockName).build();
        }
    }

    private String extractJsonBlock(String text) {
        // ```json ... ``` 마크다운 블록 우선 탐색
        int mdStart = text.indexOf("```json");
        if (mdStart >= 0) {
            int contentStart = text.indexOf('\n', mdStart) + 1;
            int mdEnd = text.indexOf("```", contentStart);
            if (contentStart > 0 && mdEnd > contentStart) {
                return text.substring(contentStart, mdEnd).trim();
            }
        }
        // 폴백: 첫 번째 { ~ 마지막 } 추출
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        throw new IllegalArgumentException("No JSON block found in AI response");
    }

    private GradeResult calculateGrade(int score) {
        if (score >= 80)
            return new GradeResult("A", "초과수익 구조가 강력하며 장기 복리 지속 가능성이 매우 높음");
        if (score >= 60)
            return new GradeResult("B", "초과수익 구조를 갖추었으나 특정 리스크 요인에 대한 모니터링 필요");
        if (score >= 40)
            return new GradeResult("C", "수익성 또는 재무 안정성이 미흡하여 장기 투자 매력도가 제한적임");
        return new GradeResult("D", "자본 효율성이 낮거나 재무 리스크가 높아 장기 투자에 부적합함");
    }

    @lombok.Getter
    @lombok.AllArgsConstructor
    private static class GradeResult {
        private final String grade;
        private final String definition;
    }

    // --- Utility methods reused from or inspired by
    // AbstractOverseasValuationService ---

    private String getRealTimeMarketCap(String stockCode) {
        try {
            var detail = overseasService.getPriceDetail(stockCode);
            return detail != null ? detail.getMarketCap() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private FcfMetrics getLatestNonNullFcfMetrics(String stockCode, String marketCapStr) {
        var flows = cashFlowRepository.findByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
        for (var flow : flows) {
            if (flow.getFcf() != null && flow.getFcf().compareTo(BigDecimal.ZERO) != 0) {
                double fcfYield = 0;
                if (marketCapStr != null) {
                    try {
                        double mktCap = Double.parseDouble(marketCapStr);
                        double fcfVal = flow.getFcf().doubleValue();
                        if (mktCap > 0) {
                            fcfYield = (fcfVal * 1000000.0 / mktCap) * 100.0;
                        }
                    } catch (Exception e) {
                    }
                }
                return FcfMetrics.builder()
                        .fcfYield(fcfYield != 0 ? String.format("%.2f%%", fcfYield) : "N/A")
                        .build();
            }
        }
        return FcfMetrics.builder().fcfYield("N/A").build();
    }

    private RoicAnalysisResult calculateROICDetails(List<OverseasStockFinancialStatement> stmts,
            List<OverseasStockBalanceSheet> balSheets) {
        if (stmts.isEmpty() || balSheets.isEmpty())
            return RoicAnalysisResult.builder().roicDisplay("N/A").tripleYearAvgRoic(0.0).build();

        double sumRoic = 0;
        int count = 0;

        for (int i = 0; i < Math.min(3, stmts.size()); i++) {
            OverseasStockFinancialStatement stmt = stmts.get(i);
            OverseasStockBalanceSheet currentBs = (balSheets.size() > i) ? balSheets.get(i) : null;
            OverseasStockBalanceSheet prevBs = (balSheets.size() > i + 1) ? balSheets.get(i + 1) : null;

            double roic = computeSingleROIC(stmt, currentBs, prevBs);
            if (roic > -999.0) {
                sumRoic += roic;
                count++;
            }
        }

        double avgRoic = (count > 0) ? sumRoic / count : 0.0;
        String warning = null;
        if (avgRoic > 60.0) {
            warning = "Extreme Capital Efficiency - Verify Sustainability & Moat Depth";
        }

        return RoicAnalysisResult.builder()
                .tripleYearAvgRoic(avgRoic)
                .roicDisplay(String.format("3yr Avg ROIC %.1f%%", avgRoic))
                .sustainabilityWarning(warning)
                .build();
    }

    private double computeSingleROIC(OverseasStockFinancialStatement stmt,
            OverseasStockBalanceSheet currentBs, OverseasStockBalanceSheet prevBs) {
        try {
            BigDecimal opIncome = stmt.getOperatingIncome();
            BigDecimal pretax = stmt.getPretaxIncome();
            BigDecimal tax = stmt.getIncomeTax();

            if (opIncome == null || pretax == null || tax == null || pretax.compareTo(BigDecimal.ZERO) <= 0)
                return -1000.0;

            // TAX CLAMP: min(max(taxRate, 0.15), 0.30)
            double rawTaxRate = tax.doubleValue() / pretax.doubleValue();
            double clampedTaxRate = Math.min(Math.max(rawTaxRate, 0.15), 0.30);
            BigDecimal nopat = opIncome.multiply(BigDecimal.valueOf(1.0 - clampedTaxRate));

            // AVG IC
            BigDecimal currentIc = calculateInvestedCapital(currentBs);
            BigDecimal prevIc = calculateInvestedCapital(prevBs);
            BigDecimal avgIc = (prevIc.compareTo(BigDecimal.ZERO) > 0)
                    ? currentIc.add(prevIc).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP)
                    : currentIc;

            if (avgIc.compareTo(BigDecimal.ZERO) <= 0)
                return -1000.0;

            return nopat.divide(avgIc, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100)).doubleValue();
        } catch (Exception e) {
            return -1000.0;
        }
    }

    private BigDecimal calculateInvestedCapital(OverseasStockBalanceSheet bs) {
        if (bs == null || bs.getTotalAssets() == null)
            return BigDecimal.ZERO;
        BigDecimal assets = bs.getTotalAssets();
        BigDecimal currentLiab = bs.getCurrentLiabilities() != null ? bs.getCurrentLiabilities() : BigDecimal.ZERO;
        BigDecimal cash = bs.getCashAndEquivalents() != null ? bs.getCashAndEquivalents() : BigDecimal.ZERO;
        return assets.subtract(currentLiab).subtract(cash);
    }

    private double estimateWacc(String industryCode) {
        if (industryCode == null) return 9.5;
        return switch (industryCode.toUpperCase()) {
            case "UTILITIES" -> 7.5;
            case "REAL_ESTATE" -> 8.0;
            case "CONSUMER_STAPLES" -> 8.0;
            case "HEALTHCARE" -> 8.5;
            case "IT", "TECHNOLOGY" -> 9.0;
            case "COMMUNICATION", "COMMUNICATION_SERVICES" -> 9.0;
            case "INDUSTRIALS" -> 9.0;
            case "CONSUMER_DISCRETIONARY" -> 9.5;
            case "MATERIALS" -> 9.5;
            case "ENERGY" -> 10.0;
            default -> 9.5;
        };
    }

    private double parsePercent(String val) {
        if (val == null || "N/A".equals(val))
            return 0.0;
        try {
            return Double.parseDouble(val.replace("%", "").replace(",", "").trim());
        } catch (Exception e) {
            return 0.0;
        }
    }

    @lombok.Builder
    @lombok.Getter
    private static class FcfMetrics {
        private final String fcfYield;
    }

    @lombok.Builder
    @lombok.Getter
    private static class RoicAnalysisResult {
        private final double tripleYearAvgRoic;
        private final String roicDisplay;
        private final String sustainabilityWarning;
    }

    @lombok.Builder
    @lombok.Getter
    private static class QualityScoreResult {
        private final BusinessQuality quality;
        private final GradeRationale rationale;
    }

    private static class AiQualityJson {
        public String suitability;
        public String moatDescription;
        public List<String> monitoringPoints;
        public String reEntryCondition;
    }

    @Transactional
    public void clearQualityReports() {
        long count = overseasQualityReportRepository.count();
        log.info("Clearing all overseas quality reports. Current count: {}", count);
        overseasQualityReportRepository.truncateTable();
        log.info("Overseas quality reports cleared. All records and IDs reset.");
    }
}
