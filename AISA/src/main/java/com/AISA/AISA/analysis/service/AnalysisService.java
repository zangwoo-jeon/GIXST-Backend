package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.dto.CorrelationResultDto;
import com.AISA.AISA.analysis.dto.RollingCorrelationDto;
import com.AISA.AISA.kisStock.Entity.stock.StockDailyData;
import com.AISA.AISA.kisStock.enums.BondYield;
import com.AISA.AISA.kisStock.enums.OverseasIndex;
import com.AISA.AISA.kisStock.kisService.KisMacroService;
import com.AISA.AISA.kisStock.kisService.KisIndexService;
import com.AISA.AISA.kisStock.repository.StockDailyDataRepository;
import com.AISA.AISA.portfolio.macro.dto.MacroIndicatorDto;
import com.AISA.AISA.portfolio.macro.service.EcosService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalysisService {

    private final StockDailyDataRepository stockDailyDataRepository;
    private final KisMacroService kisMacroService;
    private final KisIndexService kisIndexService; // Inject KisIndexService
    private final EcosService ecosService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Transactional(readOnly = true)
    public CorrelationResultDto calculateCorrelation(String asset1Type, String asset1Code, String asset2Type,
            String asset2Code, String startDate, String endDate, String method) {
        Map<LocalDate, Double> series1 = fetchAssetData(asset1Type, asset1Code, startDate, endDate);
        Map<LocalDate, Double> series2 = fetchAssetData(asset2Type, asset2Code, startDate, endDate);

        List<Double> returns1;
        List<Double> returns2;
        List<String> dates;
        int bestLag = 0;

        // AUTO Method Selection Logic
        if ("AUTO".equalsIgnoreCase(method)) {
            if (isMacroIndicator(asset1Type) || isMacroIndicator(asset2Type)) {
                method = "YOY";
            } else {
                method = "AUTO_LAG";
            }
        }

        if ("YOY".equalsIgnoreCase(method) || "MOM".equalsIgnoreCase(method)) {
            // 1. Resample to Monthly (End of Month)
            Map<String, Double> monthly1 = resampleToMonthly(series1);
            Map<String, Double> monthly2 = resampleToMonthly(series2);

            // 2. Align Monthly Data
            List<String> commonMonths = new ArrayList<>();
            for (String m : monthly1.keySet()) {
                if (monthly2.containsKey(m)) {
                    commonMonths.add(m);
                }
            }
            Collections.sort(commonMonths);

            if (commonMonths.size() < 13) { // Need at least 13 months for YoY
                return CorrelationResultDto.builder()
                        .asset1Name(asset1Code)
                        .asset2Name(asset2Code)
                        .coefficient(0)
                        .description("데이터 부족 (최소 13개월 필요)")
                        .build();
            }

            // 3. Calculate Percentage Change
            int lag = "YOY".equalsIgnoreCase(method) ? 12 : 1; // 12 months for YoY, 1 month for MoM
            returns1 = calculatePercentageChange(monthly1, commonMonths, lag);
            returns2 = calculatePercentageChange(monthly2, commonMonths, lag);
            dates = commonMonths.subList(lag, commonMonths.size());

        } else {
            // DAILY_LOG_RETURN or AUTO_LAG logic (Existing)
            List<LocalDate> commonDates = new ArrayList<>();
            for (LocalDate date : series1.keySet()) {
                if (series2.containsKey(date)) {
                    commonDates.add(date);
                }
            }
            Collections.sort(commonDates);

            if (commonDates.size() < 5) {
                return CorrelationResultDto.builder()
                        .asset1Name(asset1Code)
                        .asset2Name(asset2Code)
                        .coefficient(0)
                        .description("데이터 부족")
                        .build();
            }

            returns1 = calculateLogReturns(series1, commonDates);
            returns2 = calculateLogReturns(series2, commonDates);
            dates = commonDates.stream().skip(1).map(d -> d.format(FORMATTER)).collect(Collectors.toList());

            if ("AUTO_LAG".equalsIgnoreCase(method)) {
                return calculateAutoLagCorrelation(returns1, returns2, dates, asset1Code, asset2Code);
            }
        }

        // Calculate Correlation (No Lag for YoY/MoM/Daily)
        double[] r1 = returns1.stream().mapToDouble(Double::doubleValue).toArray();
        double[] r2 = returns2.stream().mapToDouble(Double::doubleValue).toArray();

        PearsonsCorrelation pc = new PearsonsCorrelation();
        double correlation = pc.correlation(r1, r2);
        double pValue = calculatePValue(correlation, r1.length);

        List<CorrelationResultDto.DataPoint> points = new ArrayList<>();
        for (int i = 0; i < returns1.size(); i++) {
            points.add(new CorrelationResultDto.DataPoint(dates.get(i), returns1.get(i), returns2.get(i)));
        }

        return CorrelationResultDto.builder()
                .asset1Name(asset1Code)
                .asset2Name(asset2Code)
                .coefficient(correlation)
                .pValue(pValue)
                .sampleSize(r1.length)
                .bestLag(bestLag)
                .description(interpretCorrelation(correlation))
                .points(points)
                .build();
    }

    @Transactional(readOnly = true)
    public RollingCorrelationDto calculateRollingCorrelation(String asset1Type, String asset1Code, String asset2Type,
            String asset2Code, String startDate, String endDate, int windowSize) {
        Map<LocalDate, Double> series1 = fetchAssetData(asset1Type, asset1Code, startDate, endDate);
        Map<LocalDate, Double> series2 = fetchAssetData(asset2Type, asset2Code, startDate, endDate);

        List<LocalDate> commonDates = new ArrayList<>();
        for (LocalDate date : series1.keySet()) {
            if (series2.containsKey(date)) {
                commonDates.add(date);
            }
        }
        Collections.sort(commonDates);

        List<Double> returns1 = calculateLogReturns(series1, commonDates);
        List<Double> returns2 = calculateLogReturns(series2, commonDates);
        // Dates for returns correspond to commonDates.subList(1, size)

        List<RollingCorrelationDto.RollingDataPoint> rollingData = new ArrayList<>();
        PearsonsCorrelation pc = new PearsonsCorrelation();

        for (int i = 0; i <= returns1.size() - windowSize; i++) {
            double[] w1 = returns1.subList(i, i + windowSize).stream().mapToDouble(Double::doubleValue).toArray();
            double[] w2 = returns2.subList(i, i + windowSize).stream().mapToDouble(Double::doubleValue).toArray();
            double corr = pc.correlation(w1, w2);

            // Date of the rolling window is usually the last date of the window
            String dateStr = commonDates.get(i + windowSize).format(FORMATTER);
            rollingData.add(new RollingCorrelationDto.RollingDataPoint(dateStr, corr));
        }

        return RollingCorrelationDto.builder()
                .asset1Name(asset1Code)
                .asset2Name(asset2Code)
                .windowSize(windowSize)
                .rollingData(rollingData)
                .build();
    }

    private CorrelationResultDto calculateAutoLagCorrelation(List<Double> returns1, List<Double> returns2,
            List<String> dates, String asset1Code, String asset2Code) {
        int bestLag = 0;
        double maxCorrelation = -2.0;
        double finalPValue = 0.0;
        List<CorrelationResultDto.DataPoint> finalPoints = new ArrayList<>();

        int[] lagsToCheck = { -1, 0, 1 };

        for (int lag : lagsToCheck) {
            List<Double> r1Aligned = new ArrayList<>();
            List<Double> r2Aligned = new ArrayList<>();
            List<String> datesAligned = new ArrayList<>();

            for (int i = 0; i < returns1.size(); i++) {
                int j = i - lag;
                if (j >= 0 && j < returns2.size()) {
                    r1Aligned.add(returns1.get(i));
                    r2Aligned.add(returns2.get(j));
                    datesAligned.add(dates.get(i));
                }
            }

            if (r1Aligned.size() < 3)
                continue;

            double[] arr1 = r1Aligned.stream().mapToDouble(Double::doubleValue).toArray();
            double[] arr2 = r2Aligned.stream().mapToDouble(Double::doubleValue).toArray();

            PearsonsCorrelation pc = new PearsonsCorrelation();
            double corr = pc.correlation(arr1, arr2);

            if (Math.abs(corr) > Math.abs(maxCorrelation) || maxCorrelation == -2.0) {
                maxCorrelation = corr;
                bestLag = lag;
                finalPValue = calculatePValue(corr, arr1.length);

                finalPoints.clear();
                for (int k = 0; k < r1Aligned.size(); k++) {
                    finalPoints.add(new CorrelationResultDto.DataPoint(datesAligned.get(k), r1Aligned.get(k),
                            r2Aligned.get(k)));
                }
            }
        }

        return CorrelationResultDto.builder()
                .asset1Name(asset1Code)
                .asset2Name(asset2Code)
                .coefficient(maxCorrelation)
                .pValue(finalPValue)
                .sampleSize(finalPoints.size())
                .bestLag(bestLag)
                .description(interpretCorrelation(maxCorrelation))
                .points(finalPoints)
                .build();
    }

    private Map<LocalDate, Double> fetchAssetData(String type, String code, String startDate, String endDate) {
        Map<LocalDate, Double> dataMap = new TreeMap<>(); // Sorted by date

        if ("STOCK".equalsIgnoreCase(type)) {
            List<StockDailyData> stockData = stockDailyDataRepository.findByStock_StockCodeAndDateBetweenOrderByDateAsc(
                    code, LocalDate.parse(startDate, FORMATTER), LocalDate.parse(endDate, FORMATTER));
            for (StockDailyData d : stockData) {
                dataMap.put(d.getDate(), d.getClosingPrice().doubleValue());
            }
        } else if ("INDEX".equalsIgnoreCase(type)) {
            if ("KOSPI".equalsIgnoreCase(code) || "KOSDAQ".equalsIgnoreCase(code)) {
                // Domestic Index
                var response = kisIndexService.getIndexChart(code, startDate, endDate, "D");
                for (var priceDto : response.getPriceList()) {
                    dataMap.put(LocalDate.parse(priceDto.getDate(), FORMATTER),
                            Double.parseDouble(priceDto.getPrice()));
                }
            } else {
                // Overseas Index
                OverseasIndex index = OverseasIndex.valueOf(code);
                List<MacroIndicatorDto> data = kisMacroService.fetchOverseasIndex(index, startDate, endDate);
                for (MacroIndicatorDto d : data) {
                    dataMap.put(LocalDate.parse(d.getDate(), FORMATTER), Double.parseDouble(d.getValue()));
                }
            }
        } else if ("EXCHANGE".equalsIgnoreCase(type)) {
            List<MacroIndicatorDto> data = kisMacroService.fetchExchangeRate(code, startDate, endDate);
            for (MacroIndicatorDto d : data) {
                dataMap.put(LocalDate.parse(d.getDate(), FORMATTER), Double.parseDouble(d.getValue()));
            }
        } else if ("BOND".equalsIgnoreCase(type)) {
            BondYield bond = BondYield.valueOf(code);
            List<MacroIndicatorDto> data = kisMacroService.fetchBondYield(bond, startDate, endDate);
            for (MacroIndicatorDto d : data) {
                dataMap.put(LocalDate.parse(d.getDate(), FORMATTER), Double.parseDouble(d.getValue()));
            }
        } else if ("BASE_RATE".equalsIgnoreCase(type)) {
            List<MacroIndicatorDto> data = ecosService.fetchBaseRate(startDate, endDate);
            for (MacroIndicatorDto d : data) {
                dataMap.put(LocalDate.parse(d.getDate(), FORMATTER), Double.parseDouble(d.getValue()));
            }
        } else if ("CPI".equalsIgnoreCase(type)) {
            List<MacroIndicatorDto> data = ecosService.fetchCPI(startDate, endDate);
            for (MacroIndicatorDto d : data) {
                dataMap.put(LocalDate.parse(d.getDate(), FORMATTER), Double.parseDouble(d.getValue()));
            }
        }

        return dataMap;
    }

    private Map<String, Double> resampleToMonthly(Map<LocalDate, Double> dailyData) {
        Map<String, Double> monthlyData = new TreeMap<>();
        // Group by YYYYMM and take the last value (End of Month)
        Map<String, LocalDate> lastDateMap = new HashMap<>();

        for (Map.Entry<LocalDate, Double> entry : dailyData.entrySet()) {
            String yyyymm = entry.getKey().format(DateTimeFormatter.ofPattern("yyyyMM"));
            if (!lastDateMap.containsKey(yyyymm) || entry.getKey().isAfter(lastDateMap.get(yyyymm))) {
                lastDateMap.put(yyyymm, entry.getKey());
                monthlyData.put(yyyymm, entry.getValue());
            }
        }
        return monthlyData;
    }

    private List<Double> calculatePercentageChange(Map<String, Double> series, List<String> months, int lag) {
        List<Double> returns = new ArrayList<>();
        for (int i = lag; i < months.size(); i++) {
            double current = series.get(months.get(i));
            double previous = series.get(months.get(i - lag));
            // Percentage Change: (Current / Previous) - 1
            returns.add((current / previous) - 1.0);
        }
        return returns;
    }

    private List<Double> calculateLogReturns(Map<LocalDate, Double> series, List<LocalDate> dates) {
        List<Double> returns = new ArrayList<>();
        for (int i = 1; i < dates.size(); i++) {
            double p_t = series.get(dates.get(i));
            double p_t_1 = series.get(dates.get(i - 1));
            // Log Return: ln(P_t / P_{t-1})
            returns.add(Math.log(p_t / p_t_1));
        }
        return returns;
    }

    private double calculatePValue(double r, int n) {
        if (n < 3)
            return 1.0;
        double t = r * Math.sqrt((n - 2) / (1 - r * r));
        org.apache.commons.math3.distribution.TDistribution tDist = new org.apache.commons.math3.distribution.TDistribution(
                n - 2);
        return 2.0 * (1.0 - tDist.cumulativeProbability(Math.abs(t)));
    }

    private String interpretCorrelation(double r) {
        if (r >= 0.7)
            return "강한 양의 상관관계";
        if (r >= 0.3)
            return "약한 양의 상관관계";
        if (r > -0.3)
            return "상관관계 거의 없음";
        if (r > -0.7)
            return "약한 음의 상관관계";
        return "강한 음의 상관관계";
    }

    private boolean isMacroIndicator(String assetType) {
        return "CPI".equalsIgnoreCase(assetType) ||
                "M2".equalsIgnoreCase(assetType) ||
                "BASE_RATE".equalsIgnoreCase(assetType);
    }
}
