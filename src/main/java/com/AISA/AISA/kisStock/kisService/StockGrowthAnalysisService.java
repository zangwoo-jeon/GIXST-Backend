package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialRatio;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialStatement;
import com.AISA.AISA.kisStock.Entity.stock.StockGrowthIndicator;
import com.AISA.AISA.kisStock.repository.StockFinancialRatioRepository;
import com.AISA.AISA.kisStock.repository.StockFinancialStatementRepository;
import com.AISA.AISA.kisStock.repository.StockGrowthIndicatorRepository;
import com.AISA.AISA.kisStock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockGrowthAnalysisService {

    private final StockRepository stockRepository;
    private final StockFinancialStatementRepository statementRepository;
    private final StockFinancialRatioRepository ratioRepository;
    private final StockGrowthIndicatorRepository growthIndicatorRepository;

    @Transactional
    @CacheEvict(value = "growthRanking", allEntries = true)
    public void calculateGrowthIndicators(String divCode) {
        log.info("Starting growth indicator calculation for divCode: {}", divCode);
        List<Stock> allStocks = stockRepository.findAll().stream()
                .filter(s -> s.getStockType() == Stock.StockType.DOMESTIC)
                .collect(Collectors.toList());

        // Fetch recent 5 years of data to be safe for 3Y CAGR and YoY
        String minDate = LocalDate.now().minusYears(5).format(java.time.format.DateTimeFormatter.ofPattern("yyyy"))
                + "01";

        List<StockFinancialStatement> allStatements = statementRepository
                .findByDivCodeAndStacYymmGreaterThanEqual(divCode, minDate);
        List<StockFinancialRatio> allRatios = ratioRepository.findByDivCodeAndStacYymmGreaterThanEqual(divCode,
                minDate);

        Map<String, List<StockFinancialStatement>> stmtMap = allStatements.stream()
                .collect(Collectors.groupingBy(StockFinancialStatement::getStockCode));
        Map<String, List<StockFinancialRatio>> ratioMap = allRatios.stream()
                .collect(Collectors.groupingBy(StockFinancialRatio::getStockCode));

        List<StockGrowthIndicator> indicatorsToSave = new ArrayList<>();

        for (Stock stock : allStocks) {
            try {
                String code = stock.getStockCode();
                List<StockFinancialStatement> stmts = stmtMap.getOrDefault(code, new ArrayList<>());
                List<StockFinancialRatio> ratios = ratioMap.getOrDefault(code, new ArrayList<>());

                if (stmts.isEmpty())
                    continue;

                // Sort by date ASC for calculation
                stmts.sort(Comparator.comparing(StockFinancialStatement::getStacYymm));
                ratios.sort(Comparator.comparing(StockFinancialRatio::getStacYymm));

                StockFinancialStatement latestStmt = stmts.get(stmts.size() - 1);
                String latestYymm = latestStmt.getStacYymm();

                // 1. Minimum Scale check (for the LATEST period)
                // Sales >= 1000 (100 billion), OP >= 50 (5 billion)
                if (latestStmt.getSaleAccount().compareTo(new BigDecimal("1000")) < 0 ||
                        latestStmt.getOperatingProfit().compareTo(new BigDecimal("50")) < 0) {
                    continue;
                }

                // Calculate YoY and CAGR
                BigDecimal salesYoY = calculateYoY(stmts, latestYymm, StockFinancialStatement::getSaleAccount);
                BigDecimal salesCAGR = calculateCAGR(stmts, latestYymm, StockFinancialStatement::getSaleAccount);

                BigDecimal opYoY = calculateYoY(stmts, latestYymm, StockFinancialStatement::getOperatingProfit);
                BigDecimal opCAGR = calculateCAGR(stmts, latestYymm, StockFinancialStatement::getOperatingProfit);

                BigDecimal epsYoY = calculateYoYRatio(ratios, latestYymm, StockFinancialRatio::getEps);
                BigDecimal epsCAGR = calculateCAGRRatio(ratios, latestYymm, StockFinancialRatio::getEps);

                // Turnaround check (Latest OP > 0, Previous Year Same Period OP <= 0)
                boolean isTurnaround = checkTurnaround(stmts, latestYymm);

                // Check Listing Date Duration
                LocalDate listingDate = stock.getListingDate();
                if (listingDate != null) {
                    Period period = Period.between(listingDate, LocalDate.now());
                    if (period.getYears() < 1) {
                        // listed < 1y: YoY/CAGR not reliable based on annual data
                    }
                }

                StockGrowthIndicator indicator = growthIndicatorRepository
                        .findByStockCodeAndStacYymmAndDivCode(code, latestYymm, divCode)
                        .orElse(StockGrowthIndicator.builder()
                                .stockCode(code)
                                .stacYymm(latestYymm)
                                .divCode(divCode)
                                .build());

                indicator.updateMetrics(
                        capGrowth(salesYoY), capGrowth(salesCAGR),
                        capGrowth(opYoY), capGrowth(opCAGR),
                        capGrowth(epsYoY), capGrowth(epsCAGR),
                        isTurnaround);

                indicatorsToSave.add(indicator);

            } catch (Exception e) {
                log.error("Failed to calculate growth indicator for {}: {}", stock.getStockCode(), e.getMessage());
            }
        }

        growthIndicatorRepository.saveAll(indicatorsToSave);
        log.info("Successfully calculated and saved {} growth indicators", indicatorsToSave.size());
    }

    private BigDecimal calculateYoY(List<StockFinancialStatement> stmts, String latestYymm,
            java.util.function.Function<StockFinancialStatement, BigDecimal> fieldExtractor) {
        String prevYymm = getPreviousYearYymm(latestYymm);
        BigDecimal current = fieldExtractor.apply(stmts.get(stmts.size() - 1));

        return stmts.stream()
                .filter(s -> s.getStacYymm().equals(prevYymm))
                .findFirst()
                .map(prev -> {
                    BigDecimal prevVal = fieldExtractor.apply(prev);
                    if (prevVal == null || prevVal.compareTo(BigDecimal.ZERO) <= 0)
                        return null;
                    return current.divide(prevVal, 4, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                            .multiply(new BigDecimal("100"));
                })
                .orElse(null);
    }

    private BigDecimal calculateYoYRatio(List<StockFinancialRatio> ratios, String latestYymm,
            java.util.function.Function<StockFinancialRatio, BigDecimal> fieldExtractor) {
        String prevYymm = getPreviousYearYymm(latestYymm);
        if (ratios.isEmpty())
            return null;
        BigDecimal current = fieldExtractor.apply(ratios.get(ratios.size() - 1));

        return ratios.stream()
                .filter(r -> r.getStacYymm().equals(prevYymm))
                .findFirst()
                .map(prev -> {
                    BigDecimal prevVal = fieldExtractor.apply(prev);
                    if (prevVal == null || prevVal.compareTo(BigDecimal.ZERO) <= 0)
                        return null;
                    return current.divide(prevVal, 4, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                            .multiply(new BigDecimal("100"));
                })
                .orElse(null);
    }

    private BigDecimal calculateCAGR(List<StockFinancialStatement> stmts, String latestYymm,
            java.util.function.Function<StockFinancialStatement, BigDecimal> fieldExtractor) {
        String threeYearsAgoYymm = getThreeYearsAgoYymm(latestYymm);
        BigDecimal current = fieldExtractor.apply(stmts.get(stmts.size() - 1));

        return stmts.stream()
                .filter(s -> s.getStacYymm().equals(threeYearsAgoYymm))
                .findFirst()
                .map(base -> {
                    BigDecimal baseVal = fieldExtractor.apply(base);
                    if (baseVal == null || baseVal.compareTo(BigDecimal.ZERO) <= 0)
                        return null;
                    // (Current / Base)^(1/3) - 1
                    double ratio = current.doubleValue() / baseVal.doubleValue();
                    double cagr = Math.pow(ratio, 1.0 / 3.0) - 1.0;
                    return new BigDecimal(cagr * 100).setScale(4, RoundingMode.HALF_UP);
                })
                .orElse(null);
    }

    private BigDecimal calculateCAGRRatio(List<StockFinancialRatio> ratios, String latestYymm,
            java.util.function.Function<StockFinancialRatio, BigDecimal> fieldExtractor) {
        String threeYearsAgoYymm = getThreeYearsAgoYymm(latestYymm);
        if (ratios.isEmpty())
            return null;
        BigDecimal current = fieldExtractor.apply(ratios.get(ratios.size() - 1));

        return ratios.stream()
                .filter(r -> r.getStacYymm().equals(threeYearsAgoYymm))
                .findFirst()
                .map(base -> {
                    BigDecimal baseVal = fieldExtractor.apply(base);
                    if (baseVal == null || baseVal.compareTo(BigDecimal.ZERO) <= 0)
                        return null;
                    double ratio = current.doubleValue() / baseVal.doubleValue();
                    double cagr = Math.pow(ratio, 1.0 / 3.0) - 1.0;
                    return new BigDecimal(cagr * 100).setScale(4, RoundingMode.HALF_UP);
                })
                .orElse(null);
    }

    private boolean checkTurnaround(List<StockFinancialStatement> stmts, String latestYymm) {
        String prevYymm = getPreviousYearYymm(latestYymm);
        BigDecimal currentOP = stmts.get(stmts.size() - 1).getOperatingProfit();

        return stmts.stream()
                .filter(s -> s.getStacYymm().equals(prevYymm))
                .findFirst()
                .map(prev -> currentOP.compareTo(BigDecimal.ZERO) > 0
                        && prev.getOperatingProfit().compareTo(BigDecimal.ZERO) <= 0)
                .orElse(false);
    }

    private BigDecimal capGrowth(BigDecimal value) {
        if (value == null)
            return null;
        BigDecimal cap = new BigDecimal("300");
        if (value.compareTo(cap) > 0)
            return cap;
        if (value.compareTo(cap.negate()) < 0)
            return cap.negate();
        return value;
    }

    private String getPreviousYearYymm(String yymm) {
        int year = Integer.parseInt(yymm.substring(0, 4));
        return (year - 1) + yymm.substring(4);
    }

    private String getThreeYearsAgoYymm(String yymm) {
        int year = Integer.parseInt(yymm.substring(0, 4));
        return (year - 3) + yymm.substring(4);
    }
}
