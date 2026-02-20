package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.dto.DomesticQualityValuationDto.*;
import com.AISA.AISA.analysis.entity.DomesticQualityReport;
import com.AISA.AISA.analysis.repository.DomesticQualityReportRepository;
import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockDividend;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialRatio;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialStatement;
import com.AISA.AISA.kisStock.dto.StockPrice.StockPriceDto;
import com.AISA.AISA.kisStock.enums.BondYield;
import com.AISA.AISA.kisStock.exception.KisApiErrorCode;
import com.AISA.AISA.kisStock.kisService.KisMacroService;
import com.AISA.AISA.kisStock.kisService.KisStockService;
import com.AISA.AISA.kisStock.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DomesticQualityAnalysisService {

    private final StockRepository stockRepository;
    private final StockFinancialRatioRepository ratioRepository;
    private final StockFinancialStatementRepository statementRepository;
    private final StockDividendRepository dividendRepository;
    private final DomesticQualityReportRepository reportRepository;
    private final KisStockService kisStockService;
    private final KisMacroService kisMacroService;
    private final GeminiService geminiService;
    private final ObjectMapper objectMapper;

    private static final Set<String> FINANCIAL_INDUSTRIES = Set.of(
            "FINANCIALS", "BANKS", "INSURANCE", "SECURITIES", "금융", "은행", "보험", "증권"
    );

    // ──────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────

    @Transactional
    public QualityReportResponse analyze(String stockCode, boolean forceRefresh) {
        Stock stock = stockRepository.findByStockCode(stockCode)
                .orElseThrow(() -> new BusinessException(KisApiErrorCode.STOCK_NOT_FOUND));

        // 1. Current price & market cap
        StockPriceDto priceDto = kisStockService.getStockPrice(stockCode);
        BigDecimal currentPrice = new BigDecimal(priceDto.getStockPrice().replace(",", ""));
        String marketCap = formatMarketCap(priceDto.getMarketCap());

        // 2. Cache check
        if (!forceRefresh) {
            QualityReportResponse cached = loadCachedReport(stockCode);
            if (cached != null) {
                DomesticQualityReport saved = reportRepository.findByStockCode(stockCode).orElse(null);
                if (saved != null && !shouldRefresh(saved, currentPrice)) {
                    log.info("[DomesticQuality] Using cached report for {}", stockCode);
                    cached.setCurrentPrice(priceDto.getStockPrice());
                    return cached;
                }
            }
        }

        log.info("[DomesticQuality] Generating new analysis for {}", stockCode);

        // 3. Load financial data
        List<StockFinancialRatio> ratios = ratioRepository
                .findTop5ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0"); // annual
        List<StockFinancialStatement> statements = statementRepository
                .findTop5ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
        StockFinancialRatio latestRatio = ratios.isEmpty() ? null : ratios.get(0);

        if (latestRatio == null) {
            throw new RuntimeException("재무 비율 데이터가 없습니다: " + stockCode);
        }

        // 4. Quality Score calculation
        boolean isFinancial = isFinancialSector(stock);
        QualityScoreResult scoreResult = calculateQualityScore(stockCode, stock, ratios, statements, isFinancial);

        // 5. Valuation Context
        ValuationResult valuationResult = calculateValuationContext(stockCode, ratios, latestRatio, currentPrice);

        // 6. Investment Attractiveness Matrix + EY 스프레드 보정
        String attractiveness = determineAttractiveness(scoreResult.grade, valuationResult.status);
        attractiveness = adjustByEySpread(attractiveness, valuationResult.eySpread, scoreResult.grade);
        String action = determineAction(attractiveness);

        // 7. Build rationale
        GradeRationale rationale = buildRationale(scoreResult, valuationResult, isFinancial);

        // 8. Build DTO
        QualityReportResponse response = QualityReportResponse.builder()
                .stockCode(stockCode)
                .stockName(stock.getStockName())
                .currentPrice(priceDto.getStockPrice())
                .marketCap(marketCap)
                .longTermVerdict(LongTermVerdict.builder()
                        .qualityGrade(scoreResult.grade)
                        .qualityDefinition(gradeDefinition(scoreResult.grade))
                        .valuationStatus(valuationResult.status)
                        .investmentAttractiveness(attractiveness)
                        .action(action)
                        .holdingHorizon("3년 이상")
                        .gradeRationale(rationale)
                        .build())
                .businessQuality(BusinessQuality.builder()
                        .score(scoreResult.totalScore)
                        .trajectory(scoreResult.trajectory)
                        .roeAvg3Y(scoreResult.roeDescription)
                        .operatingProfitStability(scoreResult.opStabilityDescription)
                        .salesGrowth3Y(scoreResult.growthDescription)
                        .growthWarning(scoreResult.growthWarning)
                        .balanceSheet(scoreResult.balanceSheetDescription)
                        .dividendStability(scoreResult.dividendDescription)
                        .build())
                .valuationContext(ValuationContext.builder()
                        .status(valuationResult.status)
                        .perPercentile(valuationResult.perDesc)
                        .pbrPercentile(valuationResult.pbrDesc)
                        .evEbitdaPercentile(valuationResult.evEbitdaDesc)
                        .earningsYieldVsBond(valuationResult.earningsYieldDesc)
                        .build())
                .build();

        // 9. AI Enhancement
        try {
            String prompt = buildAiPrompt(stock, response);
            String aiResponse = geminiService.generateAdvice(prompt);
            parseAiResponse(aiResponse, response);
        } catch (Exception e) {
            log.warn("[DomesticQuality] AI enhancement failed for {}: {}", stockCode, e.getMessage());
        }

        // 10. Save
        saveReport(stockCode, stock.getStockName(), currentPrice, marketCap,
                scoreResult, valuationResult.status, attractiveness,
                latestRatio.getStacYymm(), response);

        return response;
    }

    @Transactional
    public void clearReports() {
        long count = reportRepository.count();
        log.info("[DomesticQuality] Clearing all reports. Current count: {}", count);
        reportRepository.truncateTable();
        log.info("[DomesticQuality] All domestic quality reports cleared.");
    }

    // ──────────────────────────────────────────────
    // Quality Score (100점)
    // ──────────────────────────────────────────────

    private record QualityScoreResult(
            int totalScore, String grade, String trajectory,
            int roeScore, String roeDescription,
            int opScore, String opStabilityDescription,
            int growthScore, String growthDescription, String growthWarning,
            int balanceScore, String balanceSheetDescription,
            int dividendScore, String dividendDescription
    ) {}

    private QualityScoreResult calculateQualityScore(String stockCode, Stock stock,
            List<StockFinancialRatio> ratios, List<StockFinancialStatement> statements, boolean isFinancial) {

        // A. 수익성 - ROE (30점)
        List<StockFinancialRatio> recent3Ratios = ratios.stream().limit(3).collect(Collectors.toList());
        double roeAvg = recent3Ratios.stream()
                .filter(r -> r.getRoe() != null)
                .mapToDouble(r -> r.getRoe().doubleValue())
                .average().orElse(0.0);
        boolean allRoePositive = recent3Ratios.stream()
                .allMatch(r -> r.getRoe() != null && r.getRoe().compareTo(BigDecimal.ZERO) > 0);

        int roeScore;
        if (roeAvg >= 15 && allRoePositive) roeScore = 30;
        else if (roeAvg >= 15) roeScore = 22; // 음수 ROE 존재 시 상한 22
        else if (roeAvg >= 10 && allRoePositive) roeScore = 22;
        else if (roeAvg >= 10) roeScore = 18;
        else if (roeAvg >= 5) roeScore = 12;
        else roeScore = 0;

        String roeDesc = String.format("3년 평균 ROE %.1f%%", roeAvg);
        if (!allRoePositive) roeDesc += " (일부 연도 음수 ROE)";

        // B. 이익 안정성 - 영업이익 (25점)
        List<StockFinancialStatement> recent3Stmts = statements.stream().limit(3).collect(Collectors.toList());
        long opPositiveCount = recent3Stmts.stream()
                .filter(s -> s.getOperatingProfit() != null && s.getOperatingProfit().compareTo(BigDecimal.ZERO) > 0)
                .count();

        int opScore;
        if (opPositiveCount >= 3) opScore = 25;
        else if (opPositiveCount == 2) opScore = 18;
        else if (opPositiveCount == 1) opScore = 8;
        else opScore = 0;

        String opDesc = String.format("최근 3년 중 %d년 영업이익 흑자", opPositiveCount);
        if (opPositiveCount >= 3) opDesc = "안정적 (" + opDesc + ")";
        else if (opPositiveCount == 0) opDesc = "위험 (" + opDesc + ")";

        // C. 성장 지속성 - 매출/EPS 3Y CAGR (20점)
        StockFinancialRatio latestRatio = ratios.get(0);
        double salesCagr = calculateCagr(statements, StockFinancialStatement::getSaleAccount);
        double epsCagr = calculateCagrFromRatios(ratios);

        int growthScore;
        if (salesCagr >= 10) growthScore = 20;
        else if (salesCagr >= 3) growthScore = 15;
        else if (salesCagr > 0) growthScore = 8;
        else growthScore = 0;

        String growthDesc;
        if (Double.isNaN(salesCagr)) {
            growthDesc = "매출 3년 CAGR 데이터 부족";
        } else {
            growthDesc = String.format("매출 3년 CAGR %.1f%%", salesCagr);
        }

        // 성장 경고: 매출 역성장 + EPS 성장
        String growthWarning = null;
        if (!Double.isNaN(salesCagr) && !Double.isNaN(epsCagr)
                && salesCagr < 0 && epsCagr > 0) {
            growthWarning = String.format("매출 역성장(%.1f%%) 대비 EPS 성장(%.1f%%) — 일회성 이익 또는 비용 절감 가능성",
                    salesCagr, epsCagr);
        }

        // D. 재무 안정성 - 부채비율 + 유보율 (15점)
        int balanceScore;
        String balanceDesc;
        if (isFinancial) {
            balanceScore = 10; // 금융업 고정
            balanceDesc = "금융업 (부채비율 별도 평가 불가)";
        } else {
            double debtRatio = (latestRatio.getDebtRatio() != null)
                    ? latestRatio.getDebtRatio().doubleValue() : 999;
            if (debtRatio < 100) balanceScore = 15;
            else if (debtRatio < 200) balanceScore = 10;
            else if (debtRatio < 300) balanceScore = 5;
            else balanceScore = 0;

            balanceDesc = String.format("부채비율 %.0f%%", debtRatio);
            if (debtRatio < 100) balanceDesc = "건전 (" + balanceDesc + ")";
            else if (debtRatio < 200) balanceDesc = "보통 (" + balanceDesc + ")";
            else balanceDesc = "주의 (" + balanceDesc + ")";

            // 유보율 보너스 텍스트
            if (latestRatio.getReserveRatio() != null
                    && latestRatio.getReserveRatio().doubleValue() >= 1000) {
                balanceDesc += " / 유보율 우수";
            }
        }

        // E. 배당 안정성 (10점)
        int dividendScore = calculateDividendScore(stockCode);
        String dividendDesc;
        if (dividendScore == 10) dividendDesc = "안정적 (3년 연속 배당)";
        else if (dividendScore == 6) dividendDesc = "보통 (2년 배당)";
        else if (dividendScore == 3) dividendDesc = "미흡 (1년 배당)";
        else dividendDesc = "무배당";

        int totalScore = Math.max(0, Math.min(100, roeScore + opScore + growthScore + balanceScore + dividendScore));
        String grade;
        if (totalScore >= 80) grade = "A";
        else if (totalScore >= 60) grade = "B";
        else if (totalScore >= 40) grade = "C";
        else grade = "D";

        // ROE 8% 미만 → 등급 상한 B (자본 효율성 부족)
        if (roeAvg < 8.0 && "A".equals(grade)) {
            grade = "B";
        }

        // Trajectory: 최신 ROE vs 2년 전 ROE
        String trajectory = calculateTrajectory(ratios);

        return new QualityScoreResult(
                totalScore, grade, trajectory,
                roeScore, roeDesc,
                opScore, opDesc,
                growthScore, growthDesc, growthWarning,
                balanceScore, balanceDesc,
                dividendScore, dividendDesc
        );
    }

    private int calculateDividendScore(String stockCode) {
        LocalDate now = LocalDate.now();
        int count = 0;
        for (int yearsBack = 1; yearsBack <= 3; yearsBack++) {
            String startDate = String.valueOf(now.getYear() - yearsBack) + "0101";
            String endDate = String.valueOf(now.getYear() - yearsBack) + "1231";
            List<StockDividend> dividends = dividendRepository
                    .findByStock_StockCodeAndRecordDateBetweenOrderByRecordDateDesc(stockCode, startDate, endDate);
            if (!dividends.isEmpty()) count++;
        }
        if (count >= 3) return 10;
        if (count == 2) return 6;
        if (count == 1) return 3;
        return 0;
    }

    private String calculateTrajectory(List<StockFinancialRatio> ratios) {
        if (ratios.size() < 3) return "Stable";
        BigDecimal currentRoe = ratios.get(0).getRoe();
        BigDecimal oldRoe = ratios.get(2).getRoe(); // 2년 전
        if (currentRoe == null || oldRoe == null) return "Stable";
        double diff = currentRoe.subtract(oldRoe).doubleValue();
        if (diff >= 2.0) return "Improving";
        if (diff <= -2.0) return "Deteriorating";
        return "Stable";
    }

    /**
     * StockFinancialStatement 리스트에서 3년 CAGR 계산.
     * statements는 stacYymm DESC 정렬. 최신(index 0)과 3년 전 데이터로 계산.
     */
    private double calculateCagr(List<StockFinancialStatement> statements,
            java.util.function.Function<StockFinancialStatement, BigDecimal> extractor) {
        if (statements.size() < 2) return Double.NaN;

        BigDecimal latest = extractor.apply(statements.get(0));
        // 3년 전 데이터 찾기 (index 3이 있으면 3년 전, 없으면 가장 오래된 데이터)
        int oldIndex = Math.min(3, statements.size() - 1);
        BigDecimal oldest = extractor.apply(statements.get(oldIndex));

        if (latest == null || oldest == null || oldest.compareTo(BigDecimal.ZERO) <= 0) return Double.NaN;

        double years = oldIndex; // 실제 연수 차이
        if (years == 0) return Double.NaN;

        double ratio = latest.doubleValue() / oldest.doubleValue();
        if (ratio <= 0) return Double.NaN;

        return (Math.pow(ratio, 1.0 / years) - 1) * 100.0;
    }

    /**
     * StockFinancialRatio의 EPS로 3년 CAGR 계산.
     */
    private double calculateCagrFromRatios(List<StockFinancialRatio> ratios) {
        if (ratios.size() < 2) return Double.NaN;

        BigDecimal latestEps = ratios.get(0).getEps();
        int oldIndex = Math.min(3, ratios.size() - 1);
        BigDecimal oldestEps = ratios.get(oldIndex).getEps();

        if (latestEps == null || oldestEps == null || oldestEps.compareTo(BigDecimal.ZERO) <= 0) return Double.NaN;

        double years = oldIndex;
        if (years == 0) return Double.NaN;

        double ratio = latestEps.doubleValue() / oldestEps.doubleValue();
        if (ratio <= 0) return Double.NaN;

        return (Math.pow(ratio, 1.0 / years) - 1) * 100.0;
    }

    // ──────────────────────────────────────────────
    // Valuation Context (Percentile 기반 - 전체 시장 대비)
    // ──────────────────────────────────────────────

    private record ValuationResult(
            String status, double valuationScore, double eySpread,
            String perDesc, String pbrDesc, String evEbitdaDesc, String earningsYieldDesc
    ) {}

    private ValuationResult calculateValuationContext(String stockCode, List<StockFinancialRatio> ratios,
            StockFinancialRatio latest, BigDecimal currentPrice) {

        double currentPer = (latest.getPer() != null && latest.getPer().doubleValue() > 0)
                ? latest.getPer().doubleValue() : -1;
        double currentPbr = (latest.getPbr() != null && latest.getPbr().doubleValue() > 0)
                ? latest.getPbr().doubleValue() : -1;
        double currentEv = (latest.getEvEbitda() != null && latest.getEvEbitda().doubleValue() > 0)
                ? latest.getEvEbitda().doubleValue() : -1;

        // 전체 시장 대비 Percentile (같은 결산기의 모든 종목과 비교)
        String latestYymm = latest.getStacYymm();
        List<StockFinancialRatio> allRatios = ratioRepository
                .findAllByDivCodeAndStacYymmAndIsSuspendedFalseOrderByRoeDesc("0", latestYymm);

        List<Double> marketPer = allRatios.stream()
                .filter(r -> r.getPer() != null && r.getPer().doubleValue() > 0)
                .map(r -> r.getPer().doubleValue()).collect(Collectors.toList());
        List<Double> marketPbr = allRatios.stream()
                .filter(r -> r.getPbr() != null && r.getPbr().doubleValue() > 0)
                .map(r -> r.getPbr().doubleValue()).collect(Collectors.toList());
        List<Double> marketEv = allRatios.stream()
                .filter(r -> r.getEvEbitda() != null && r.getEvEbitda().doubleValue() > 0)
                .map(r -> r.getEvEbitda().doubleValue()).collect(Collectors.toList());

        double perPct = calculatePercentile(marketPer, currentPer);
        double pbrPct = calculatePercentile(marketPbr, currentPbr);
        double evPct = calculatePercentile(marketEv, currentEv);

        String perDesc = formatPercentileDesc(perPct, currentPer, "PER", "배");
        String pbrDesc = formatPercentileDesc(pbrPct, currentPbr, "PBR", "배");
        String evEbitdaDesc = formatPercentileDesc(evPct, currentEv, "EV/EBITDA", "배");

        // PBR-ROE 정합성 가감점
        double currentRoe = (latest.getRoe() != null) ? latest.getRoe().doubleValue() : 0;
        int pbrRoeAdj = 0;
        if (currentRoe >= 15 && currentPbr > 0 && currentPbr <= 1.5) pbrRoeAdj = 5;
        else if (currentRoe <= 5 && currentPbr >= 2.0) pbrRoeAdj = -5;
        else if (currentRoe <= 0) pbrRoeAdj = -10;

        // 유효한 percentile만 수집
        List<Double> validPcts = new ArrayList<>();
        if (perPct >= 0) validPcts.add(perPct);
        if (pbrPct >= 0) validPcts.add(pbrPct);
        if (evPct >= 0) validPcts.add(evPct);

        // 판정: 2/3 이상이 하위 35% → 저평가, 2/3 이상이 상위 65% → 고평가
        String status;
        if (validPcts.isEmpty()) {
            status = "적정";
        } else {
            long cheapCount = validPcts.stream().filter(p -> p <= 35.0).count();
            long expensiveCount = validPcts.stream().filter(p -> p >= 65.0).count();

            // PBR-ROE 가감 적용: 점수를 조정해서 경계 케이스 보정
            double avgPct = validPcts.stream().mapToDouble(d -> d).average().orElse(50.0);
            double adjustedAvg = avgPct - pbrRoeAdj; // 가감점이 양수면 avgPct를 낮춤 (더 저평가 방향)

            if (cheapCount >= 2 || adjustedAvg <= 30) {
                status = "저평가";
            } else if (expensiveCount >= 2 || adjustedAvg >= 70) {
                status = "고평가";
            } else {
                status = "적정";
            }
        }

        // Earnings Yield vs 국채 3년
        double eySpread = calculateEySpread(currentPer);
        String earningsYieldDesc = calculateEarningsYieldDesc(currentPer);

        double valuationScore = validPcts.isEmpty() ? 50.0
                : validPcts.stream().mapToDouble(d -> d).average().orElse(50.0);

        return new ValuationResult(status, valuationScore, eySpread, perDesc, pbrDesc, evEbitdaDesc, earningsYieldDesc);
    }

    private double calculatePercentile(List<Double> marketValues, double currentValue) {
        if (marketValues.size() < 10 || currentValue <= 0) return -1;

        // Winsorize: 상하위 5% 제거
        List<Double> sorted = new ArrayList<>(marketValues);
        Collections.sort(sorted);
        int trimCount = (int) (sorted.size() * 0.05);
        if (trimCount > 0) {
            sorted = sorted.subList(trimCount, sorted.size() - trimCount);
        }

        // 순위 기반 percentile: 현재 값보다 작은 종목의 비율
        long belowCount = sorted.stream().filter(v -> v < currentValue).count();
        return (belowCount * 100.0) / sorted.size();
    }

    private String formatPercentileDesc(double percentile, double currentValue, String label, String unit) {
        if (percentile < 0 || currentValue <= 0) return "데이터 부족";
        String position;
        if (percentile <= 25) position = "하위";
        else if (percentile <= 50) position = "중하위";
        else if (percentile <= 75) position = "중상위";
        else position = "상위";
        return String.format("%s %.0f%% (현재 %.1f%s)", position, percentile, currentValue, unit);
    }

    /**
     * EY 스프레드 계산: Earnings Yield - 국채3년 금리
     * 반환: 스프레드 (%), 계산 불가 시 NaN
     */
    private double calculateEySpread(double currentPer) {
        if (currentPer <= 0) return Double.NaN;
        double earningsYield = (1.0 / currentPer) * 100.0;
        try {
            BigDecimal bondYield = kisMacroService.getLatestBondYield(BondYield.KR_3Y);
            if (bondYield != null && bondYield.doubleValue() > 0) {
                return earningsYield - bondYield.doubleValue();
            }
        } catch (Exception e) {
            log.warn("[DomesticQuality] Failed to get bond yield for EY spread: {}", e.getMessage());
        }
        return Double.NaN;
    }

    private String calculateEarningsYieldDesc(double currentPer) {
        if (currentPer <= 0) return "산출 불가 (PER 음수 또는 없음)";
        double earningsYield = (1.0 / currentPer) * 100.0;
        try {
            BigDecimal bondYield = kisMacroService.getLatestBondYield(BondYield.KR_3Y);
            if (bondYield != null && bondYield.doubleValue() > 0) {
                return String.format("Earnings Yield %.1f%% vs 국채3년 %.1f%%",
                        earningsYield, bondYield.doubleValue());
            }
        } catch (Exception e) {
            log.warn("[DomesticQuality] Failed to get bond yield: {}", e.getMessage());
        }
        return String.format("Earnings Yield %.1f%%", earningsYield);
    }

    // ──────────────────────────────────────────────
    // Investment Attractiveness Matrix
    // ──────────────────────────────────────────────

    private String determineAttractiveness(String grade, String valuationStatus) {
        return switch (grade) {
            case "A" -> switch (valuationStatus) {
                case "저평가" -> "Very Attractive";
                case "적정" -> "Attractive";
                default -> "Neutral";
            };
            case "B" -> switch (valuationStatus) {
                case "저평가" -> "Attractive";
                case "적정" -> "Neutral";
                default -> "Low";
            };
            case "C" -> switch (valuationStatus) {
                case "저평가" -> "Neutral";
                case "적정" -> "Low";
                default -> "Avoid";
            };
            default -> switch (valuationStatus) { // D
                case "저평가" -> "Speculative";
                default -> "Avoid";
            };
        };
    }

    private static final List<String> ATTRACTIVENESS_LEVELS = List.of(
            "Avoid", "Low", "Neutral", "Attractive", "Very Attractive"
    );

    /**
     * EY 스프레드에 따른 투자매력도 한 단계 조정.
     * 스프레드는 소수점 1자리로 반올림 후 비교 (0.998% → 1.0%).
     * EY - 국채 >= 1% → 한 단계 상향, < 0% → 한 단계 하향.
     * 단, B등급 이상 기업은 Avoid까지 하락하지 않음 (Low가 하한).
     */
    private String adjustByEySpread(String attractiveness, double eySpread, String qualityGrade) {
        if (Double.isNaN(eySpread) || "Speculative".equals(attractiveness)) return attractiveness;

        int idx = ATTRACTIVENESS_LEVELS.indexOf(attractiveness);
        if (idx < 0) return attractiveness;

        double roundedSpread = Math.round(eySpread * 10.0) / 10.0;

        if (roundedSpread >= 1.0) {
            idx = Math.min(idx + 1, ATTRACTIVENESS_LEVELS.size() - 1);
        } else if (roundedSpread < 0) {
            idx = Math.max(idx - 1, 0);
        }

        // B등급 이상은 Avoid(0)까지 하락 방지 → Low(1)가 하한
        if (("A".equals(qualityGrade) || "B".equals(qualityGrade)) && idx < 1) {
            idx = 1; // Low
        }

        return ATTRACTIVENESS_LEVELS.get(idx);
    }

    private String determineAction(String attractiveness) {
        return switch (attractiveness) {
            case "Very Attractive" -> "Strong Buy";
            case "Attractive" -> "Buy";
            case "Neutral" -> "Hold";
            case "Low" -> "Reduce";
            case "Speculative" -> "Speculative Buy";
            default -> "Sell"; // Avoid
        };
    }

    private String gradeDefinition(String grade) {
        return switch (grade) {
            case "A" -> "우량 기업 — 높은 수익성과 안정적 재무구조";
            case "B" -> "양호 기업 — 대체로 건전하나 일부 개선 여지";
            case "C" -> "보통 기업 — 수익성 또는 안정성에 주의 필요";
            default -> "취약 기업 — 재무적 리스크 존재";
        };
    }

    // ──────────────────────────────────────────────
    // Rationale Builder
    // ──────────────────────────────────────────────

    private GradeRationale buildRationale(QualityScoreResult q, ValuationResult v, boolean isFinancial) {
        List<String> positive = new ArrayList<>();
        List<String> negative = new ArrayList<>();

        // ROE
        if (q.roeScore >= 22) positive.add("수익성 우수 (" + q.roeDescription + ")");
        else if (q.roeScore == 0) negative.add("수익성 미흡 (" + q.roeDescription + ")");

        // 영업이익
        if (q.opScore >= 25) positive.add(q.opStabilityDescription);
        else if (q.opScore <= 8) negative.add(q.opStabilityDescription);

        // 성장
        if (q.growthScore >= 15) positive.add(q.growthDescription);
        else if (q.growthScore == 0) negative.add(q.growthDescription);
        if (q.growthWarning != null) negative.add(q.growthWarning);

        // 재무 안정성
        if (q.balanceScore >= 15) positive.add(q.balanceSheetDescription);
        else if (q.balanceScore <= 5 && !isFinancial) negative.add(q.balanceSheetDescription);

        // 배당
        if (q.dividendScore >= 10) positive.add(q.dividendDescription);
        else if (q.dividendScore == 0) negative.add(q.dividendDescription);

        // 밸류에이션
        if ("저평가".equals(v.status)) positive.add("밸류에이션 저평가 구간");
        else if ("고평가".equals(v.status)) negative.add("밸류에이션 고평가 구간");

        return GradeRationale.builder()
                .positiveFactors(positive)
                .negativeFactors(negative)
                .build();
    }

    // ──────────────────────────────────────────────
    // AI Enhancement
    // ──────────────────────────────────────────────

    private String buildAiPrompt(Stock stock, QualityReportResponse r) {
        StringBuilder sb = new StringBuilder();
        sb.append("너는 '국내 주식 장기 투자 분석 전문가'이다. 아래 분석 결과를 바탕으로 JSON 형식으로 응답하라.\n\n");
        sb.append(String.format("종목: %s (%s)\n", stock.getStockName(), stock.getStockCode()));
        sb.append(String.format("품질 등급: %s (%d점/100점)\n", r.getLongTermVerdict().getQualityGrade(),
                r.getBusinessQuality().getScore()));
        sb.append(String.format("궤적: %s\n", r.getBusinessQuality().getTrajectory()));
        sb.append(String.format("수익성: %s\n", r.getBusinessQuality().getRoeAvg3Y()));
        sb.append(String.format("이익안정성: %s\n", r.getBusinessQuality().getOperatingProfitStability()));
        sb.append(String.format("성장성: %s\n", r.getBusinessQuality().getSalesGrowth3Y()));
        if (r.getBusinessQuality().getGrowthWarning() != null) {
            sb.append(String.format("성장 경고: %s\n", r.getBusinessQuality().getGrowthWarning()));
        }
        sb.append(String.format("재무안정성: %s\n", r.getBusinessQuality().getBalanceSheet()));
        sb.append(String.format("배당: %s\n", r.getBusinessQuality().getDividendStability()));
        sb.append(String.format("밸류에이션: %s\n", r.getValuationContext().getStatus()));
        sb.append(String.format("PER: %s\n", r.getValuationContext().getPerPercentile()));
        sb.append(String.format("PBR: %s\n", r.getValuationContext().getPbrPercentile()));
        sb.append(String.format("EV/EBITDA: %s\n", r.getValuationContext().getEvEbitdaPercentile()));
        sb.append(String.format("Earnings Yield: %s\n", r.getValuationContext().getEarningsYieldVsBond()));
        sb.append(String.format("투자 매력도: %s → %s\n\n",
                r.getLongTermVerdict().getInvestmentAttractiveness(), r.getLongTermVerdict().getAction()));

        sb.append("아래 JSON 형식으로만 응답하라:\n");
        sb.append("{\n");
        sb.append("  \"suitability\": \"이 종목이 장기 투자에 적합한 이유 또는 부적합한 이유 (2~3문장)\",\n");
        sb.append("  \"moatDescription\": \"이 기업의 경쟁 우위 (해자) 분석 (1~2문장)\",\n");
        sb.append("  \"thesisMonitoring\": [\"모니터링 포인트1\", \"모니터링 포인트2\", \"모니터링 포인트3\"],\n");
        sb.append("  \"reEntryCondition\": \"재진입 조건 (1문장)\"\n");
        sb.append("}");
        return sb.toString();
    }

    private void parseAiResponse(String aiText, QualityReportResponse response) {
        try {
            String json = extractJsonBlock(aiText);
            var tree = objectMapper.readTree(json);

            if (tree.has("suitability")) {
                response.getLongTermVerdict().setSuitability(tree.get("suitability").asText());
            }
            if (tree.has("moatDescription")) {
                response.getBusinessQuality().setMoatDescription(tree.get("moatDescription").asText());
            }
            if (tree.has("thesisMonitoring")) {
                List<String> monitoring = new ArrayList<>();
                tree.get("thesisMonitoring").forEach(node -> monitoring.add(node.asText()));
                response.setThesisMonitoring(monitoring);
            }
            if (tree.has("reEntryCondition")) {
                response.getLongTermVerdict().setReEntryCondition(tree.get("reEntryCondition").asText());
            }
        } catch (Exception e) {
            log.warn("[DomesticQuality] Failed to parse AI JSON: {}", e.getMessage());
        }
    }

    private String extractJsonBlock(String text) {
        int mdStart = text.indexOf("```json");
        if (mdStart >= 0) {
            int contentStart = text.indexOf('\n', mdStart) + 1;
            int mdEnd = text.indexOf("```", contentStart);
            if (contentStart > 0 && mdEnd > contentStart)
                return text.substring(contentStart, mdEnd).trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        throw new IllegalArgumentException("No JSON block found in AI response");
    }

    // ──────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────

    private boolean isFinancialSector(Stock stock) {
        if (stock.getStockIndustries() == null) return false;
        return stock.getStockIndustries().stream()
                .anyMatch(si -> {
                    String code = si.getSubIndustry().getIndustry().getCode();
                    String name = si.getSubIndustry().getIndustry().getName();
                    return (code != null && FINANCIAL_INDUSTRIES.contains(code.toUpperCase()))
                            || (name != null && FINANCIAL_INDUSTRIES.stream()
                                    .anyMatch(f -> name.toUpperCase().contains(f)));
                });
    }

    // marketCap 단위: 억원
    private String formatMarketCap(String marketCapRaw) {
        if (marketCapRaw == null || marketCapRaw.isBlank()) return "N/A";
        try {
            BigDecimal capEok = new BigDecimal(marketCapRaw.replace(",", ""));
            // 억원 → 조원: / 10,000
            if (capEok.compareTo(new BigDecimal("10000")) >= 0) {
                return capEok.divide(new BigDecimal("10000"), 2, RoundingMode.HALF_UP) + "조원";
            }
            return capEok.setScale(0, RoundingMode.HALF_UP) + "억원";
        } catch (Exception e) {
            log.warn("[DomesticQuality] Market cap format failed: {}", marketCapRaw);
            return "N/A";
        }
    }

    // ──────────────────────────────────────────────
    // Cache Management
    // ──────────────────────────────────────────────

    private boolean shouldRefresh(DomesticQualityReport saved, BigDecimal currentPrice) {
        // 7일 초과
        if (saved.getLastModifiedDate().isBefore(LocalDateTime.now().minusDays(7))) return true;

        // 10% 이상 가격 변동
        if (saved.getLastPrice() != null && saved.getLastPrice().compareTo(BigDecimal.ZERO) > 0) {
            double change = currentPrice.subtract(saved.getLastPrice()).abs()
                    .divide(saved.getLastPrice(), 4, RoundingMode.HALF_UP).doubleValue();
            if (change >= 0.10) return true;
        }

        // 재무 데이터 변경
        StockFinancialRatio latestRatio = ratioRepository
                .findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(saved.getStockCode(), "0");
        if (latestRatio != null && !latestRatio.getStacYymm().equals(saved.getLastStacYymm())) return true;

        return false;
    }

    private QualityReportResponse loadCachedReport(String stockCode) {
        return reportRepository.findByStockCode(stockCode).map(report -> {
            try {
                return objectMapper.readValue(report.getFullReportJson(), QualityReportResponse.class);
            } catch (Exception e) {
                log.warn("[DomesticQuality] Failed to deserialize cached report for {}", stockCode);
                return null;
            }
        }).orElse(null);
    }

    private void saveReport(String stockCode, String stockName, BigDecimal currentPrice, String marketCap,
            QualityScoreResult scoreResult, String valuationStatus, String attractiveness,
            String stacYymm, QualityReportResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            DomesticQualityReport report = reportRepository.findByStockCode(stockCode)
                    .orElse(DomesticQualityReport.builder().stockCode(stockCode).build());
            report.setStockName(stockName);
            report.setCurrentPrice(currentPrice.toPlainString());
            report.setMarketCap(marketCap);
            report.setQualityGrade(scoreResult.grade);
            report.setQualityScore(scoreResult.totalScore);
            report.setValuationStatus(valuationStatus);
            report.setInvestmentAttractiveness(attractiveness);
            report.setLastPrice(currentPrice);
            report.setLastStacYymm(stacYymm);
            report.setFullReportJson(json);
            reportRepository.save(report);
        } catch (Exception e) {
            log.error("[DomesticQuality] Failed to save report for {}: {}", stockCode, e.getMessage());
        }
    }
}
