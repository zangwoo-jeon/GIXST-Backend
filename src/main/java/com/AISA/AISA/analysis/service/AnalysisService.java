package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.dto.CorrelationResultDto;
import com.AISA.AISA.analysis.dto.RollingCorrelationDto;
import com.AISA.AISA.kisOverseasStock.entity.OverseasStockDailyData;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockDailyDataRepository;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockDailyData;
import com.AISA.AISA.fred.dto.FredIndexDataDto;
import com.AISA.AISA.fred.enums.FredIndex;
import com.AISA.AISA.fred.service.FredIndexService;
import com.AISA.AISA.kisStock.enums.BondYield;
import com.AISA.AISA.kisStock.kisService.KisMacroService;
import com.AISA.AISA.kisStock.kisService.KisIndexService;
import com.AISA.AISA.kisStock.repository.StockDailyDataRepository;
import com.AISA.AISA.kisStock.repository.StockRepository;
import com.AISA.AISA.portfolio.macro.dto.MacroIndicatorDto;
import com.AISA.AISA.portfolio.macro.service.EcosService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.distribution.TDistribution;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.stat.regression.SimpleRegression;
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
    private final KisOverseasStockDailyDataRepository overseasStockDailyDataRepository;
    private final StockRepository stockRepository;
    private final KisMacroService kisMacroService;
    private final KisIndexService kisIndexService;
    private final FredIndexService fredIndexService;
    private final EcosService ecosService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Transactional(readOnly = true)
    public CorrelationResultDto calculateCorrelation(String asset1Type, String asset1Code, String asset2Type,
            String asset2Code, String startDate, String endDate, String method) {
        Map<LocalDate, Double> series1 = fetchAssetData(asset1Type, asset1Code, startDate, endDate);
        Map<LocalDate, Double> series2 = fetchAssetData(asset2Type, asset2Code, startDate, endDate);

        // AUTO Method Selection Logic
        if ("AUTO".equalsIgnoreCase(method)) {
            if (isMacroIndicator(asset1Type) || isMacroIndicator(asset2Type)) {
                method = "YOY";
            } else {
                method = "AUTO_LAG";
            }
        }

        return calculateCorrelationInternal(series1, asset1Code, series2, asset2Code, method);
    }

    // Overloaded method for custom data injection (e.g., Portfolio Daily Values)
    @Transactional(readOnly = true)
    public CorrelationResultDto calculateCorrelation(Map<LocalDate, Double> customSeries1, String asset1Name,
            String asset2Type, String asset2Code, String startDate, String endDate, String method) {
        Map<LocalDate, Double> series2 = fetchAssetData(asset2Type, asset2Code, startDate, endDate);

        return calculateCorrelationInternal(customSeries1, asset1Name, series2, asset2Code, method);
    }

    private CorrelationResultDto calculateCorrelationInternal(Map<LocalDate, Double> series1, String asset1Name,
            Map<LocalDate, Double> series2, String asset2Name, String method) {
        List<Double> returns1;
        List<Double> returns2;
        List<String> dates;
        int bestLag = 0;

        // AUTO Method Selection Logic (Simplified for custom data)
        if ("AUTO".equalsIgnoreCase(method)) {
            // If asset2 is Macro, use YOY. Otherwise AUTO_LAG.
            // (Assuming customSeries1 is Portfolio, which behaves like Stock)
            if (isMacroIndicator(asset2Name)) { // Check asset2Name (or type if passed) - here using name as proxy or
                                                // need type
                // Actually, isMacroIndicator checks type strings like "CPI".
                // We might need to pass asset2Type to this internal method or infer it.
                // For now, let's assume the caller handles "AUTO" or we check known macro
                // codes.
                // Better approach: Pass asset2Type to internal method.
            }
            // Re-evaluating: The original method had asset types.
            // Let's keep the logic simple here:
            // If method is AUTO, we default to AUTO_LAG unless we know it's macro.
            // But we don't have asset2Type here easily unless passed.
            // Let's stick to the passed method or default to AUTO_LAG if not YOY/MOM.
            // If method is AUTO, we default to AUTO_LAG unless we know it's macro.
            // But we don't have asset2Type here easily unless passed.
            // Let's stick to the passed method or default to AUTO_LAG if not YOY/MOM.
            if ("AUTO".equalsIgnoreCase(method)) {
                method = "AUTO_LAG"; // Default for Portfolio vs Asset
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
                        .asset1Name(asset1Name)
                        .asset2Name(asset2Name)
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
                        .asset1Name(asset1Name)
                        .asset2Name(asset2Name)
                        .coefficient(0)
                        .description("데이터 부족")
                        .build();
            }

            returns1 = calculateLogReturns(series1, commonDates);
            returns2 = calculateLogReturns(series2, commonDates);
            dates = commonDates.stream().skip(1).map(d -> d.format(FORMATTER)).collect(Collectors.toList());

            if ("AUTO_LAG".equalsIgnoreCase(method)) {
                return calculateAutoLagCorrelation(returns1, returns2, dates, asset1Name, asset2Name);
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
                .asset1Name(asset1Name)
                .asset2Name(asset2Name)
                .coefficient(correlation)
                .pValue(pValue)
                .sampleSize(r1.length)
                .bestLag(bestLag)
                .description(interpretCorrelation(correlation))
                .points(points)
                .build();
    }

    /*
     * Calculates Partial Correlation between X and Y while controlling for Z.
     * Formula: r_xy.z = (r_xy - r_xz * r_yz) / sqrt((1 - r_xz^2) * (1 - r_yz^2))
     * This requires finding the common date intersection of all three series.
     */
    public CorrelationResultDto calculatePartialCorrelation(
            Map<LocalDate, Double> seriesX, String nameX,
            Map<LocalDate, Double> seriesY, String nameY,
            Map<LocalDate, Double> seriesZ, String nameZ) {

        // 1. Find Intersection of Dates for X, Y, Z
        List<LocalDate> commonDates = new ArrayList<>();
        for (LocalDate date : seriesX.keySet()) {
            if (seriesY.containsKey(date) && seriesZ.containsKey(date)) {
                commonDates.add(date);
            }
        }
        Collections.sort(commonDates);

        if (commonDates.size() < 5) {
            return CorrelationResultDto.builder()
                    .asset1Name(nameX)
                    .asset2Name(nameY)
                    .coefficient(0)
                    .description("데이터 부족 (Partial Correlation)")
                    .build();
        }

        // 2. Calculate Log Returns for the common period
        List<Double> retX = calculateLogReturns(seriesX, commonDates);
        List<Double> retY = calculateLogReturns(seriesY, commonDates);
        List<Double> retZ = calculateLogReturns(seriesZ, commonDates);

        double[] arrX = retX.stream().mapToDouble(Double::doubleValue).toArray();
        double[] arrY = retY.stream().mapToDouble(Double::doubleValue).toArray();
        double[] arrZ = retZ.stream().mapToDouble(Double::doubleValue).toArray();

        PearsonsCorrelation pc = new PearsonsCorrelation();

        // 3. Calculate Pairwise Correlations
        double r_xy = pc.correlation(arrX, arrY);
        double r_xz = pc.correlation(arrX, arrZ);
        double r_yz = pc.correlation(arrY, arrZ);

        // 4. Calculate Partial Correlation
        double numerator = r_xy - (r_xz * r_yz);
        double denominator = Math.sqrt((1 - Math.pow(r_xz, 2)) * (1 - Math.pow(r_yz, 2)));

        // Prevent division by zero
        if (denominator == 0) {
            return CorrelationResultDto.builder()
                    .asset1Name(nameX)
                    .asset2Name(nameY)
                    .coefficient(0) // Undefined
                    .description("계산 불가 (분모 0)")
                    .build();
        }

        double partialCorr = numerator / denominator;

        // Calculate p-value (degrees of freedom: n - 2 - number of control variables =
        // n - 3)
        double pValue = calculatePValue(partialCorr, arrX.length - 1);

        return CorrelationResultDto.builder()
                .asset1Name(nameX)
                .asset2Name(nameY)
                .coefficient(partialCorr)
                .pValue(pValue)
                .sampleSize(arrX.length)
                .description(interpretCorrelation(partialCorr))
                .build();
    }

    @Transactional(readOnly = true)
    public RollingCorrelationDto calculateRollingCorrelation(String asset1Type, String asset1Code, String asset2Type,
            String asset2Code, String startDate, String endDate, int windowSize) {
        Map<LocalDate, Double> series1 = fetchAssetData(asset1Type, asset1Code, startDate, endDate);
        Map<LocalDate, Double> series2 = fetchAssetData(asset2Type, asset2Code, startDate, endDate);

        return calculateRollingCorrelationInternal(series1, asset1Code, series2, asset2Code, windowSize);
    }

    @Transactional(readOnly = true)
    public RollingCorrelationDto calculateRollingCorrelation(Map<LocalDate, Double> customSeries1, String asset1Name,
            String asset2Type, String asset2Code, String startDate, String endDate, int windowSize) {
        Map<LocalDate, Double> series2 = fetchAssetData(asset2Type, asset2Code, startDate, endDate);

        return calculateRollingCorrelationInternal(customSeries1, asset1Name, series2, asset2Code, windowSize);
    }

    private RollingCorrelationDto calculateRollingCorrelationInternal(Map<LocalDate, Double> series1, String asset1Name,
            Map<LocalDate, Double> series2, String asset2Name, int windowSize) {
        List<LocalDate> commonDates = new ArrayList<>();
        for (LocalDate date : series1.keySet()) {
            if (series2.containsKey(date)) {
                commonDates.add(date);
            }
        }
        Collections.sort(commonDates);

        if (commonDates.size() < windowSize + 1) {
            return RollingCorrelationDto.builder()
                    .asset1Name(asset1Name)
                    .asset2Name(asset2Name)
                    .windowSize(windowSize)
                    .rollingData(new ArrayList<>())
                    .build();
        }

        List<Double> returns1 = calculateLogReturns(series1, commonDates);
        List<Double> returns2 = calculateLogReturns(series2, commonDates);

        List<RollingCorrelationDto.RollingDataPoint> rollingData = new ArrayList<>();
        PearsonsCorrelation pc = new PearsonsCorrelation();

        for (int i = 0; i <= returns1.size() - windowSize; i++) {
            double[] w1 = returns1.subList(i, i + windowSize).stream().mapToDouble(Double::doubleValue).toArray();
            double[] w2 = returns2.subList(i, i + windowSize).stream().mapToDouble(Double::doubleValue).toArray();
            double corr = pc.correlation(w1, w2);
            if (Double.isNaN(corr))
                corr = 0.0;

            String dateStr = commonDates.get(i + windowSize).format(FORMATTER);
            rollingData.add(new RollingCorrelationDto.RollingDataPoint(dateStr, corr));
        }

        return RollingCorrelationDto.builder()
                .asset1Name(asset1Name)
                .asset2Name(asset2Name)
                .windowSize(windowSize)
                .rollingData(rollingData)
                .build();
    }

    public Map<Integer, Double> calculateMultiWindowRollingCorrelation(Map<LocalDate, Double> series1,
            Map<LocalDate, Double> series2, List<Integer> windows) {
        Map<Integer, Double> result = new HashMap<>();
        List<LocalDate> commonDates = new ArrayList<>();
        for (LocalDate date : series1.keySet()) {
            if (series2.containsKey(date)) {
                commonDates.add(date);
            }
        }
        Collections.sort(commonDates);

        if (commonDates.size() < 2) {
            for (int w : windows)
                result.put(w, 0.0);
            return result;
        }

        List<Double> returns1 = calculateLogReturns(series1, commonDates);
        List<Double> returns2 = calculateLogReturns(series2, commonDates);

        PearsonsCorrelation pc = new PearsonsCorrelation();
        int totalPoints = returns1.size();

        for (int window : windows) {
            if (totalPoints < window) {
                result.put(window, 0.0); // Not enough data
                continue;
            }
            // Get last 'window' points
            double[] w1 = returns1.subList(totalPoints - window, totalPoints).stream().mapToDouble(Double::doubleValue)
                    .toArray();
            double[] w2 = returns2.subList(totalPoints - window, totalPoints).stream().mapToDouble(Double::doubleValue)
                    .toArray();
            double corr = pc.correlation(w1, w2);
            result.put(window, Double.isNaN(corr) ? 0.0 : corr);
        }
        return result;
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

    public Map<LocalDate, Double> fetchAssetData(String type, String code, String startDate, String endDate) {
        Map<LocalDate, Double> dataMap = new TreeMap<>(); // Sorted by date

        if ("STOCK".equalsIgnoreCase(type)) {
            Stock stock = stockRepository.findByStockCode(code).orElse(null);
            if (stock != null) {
                if (stock.getStockType() == Stock.StockType.US_STOCK
                        || stock.getStockType() == Stock.StockType.US_ETF
                        || stock.getStockType() == Stock.StockType.FOREIGN_ETF) {
                    List<OverseasStockDailyData> overseasData = overseasStockDailyDataRepository
                            .findByStockAndDateBetween(
                                    stock, LocalDate.parse(startDate, FORMATTER), LocalDate.parse(endDate, FORMATTER));
                    for (OverseasStockDailyData d : overseasData) {
                        dataMap.put(d.getDate(), d.getClosingPrice().doubleValue());
                    }
                } else {
                    List<StockDailyData> stockData = stockDailyDataRepository
                            .findByStock_StockCodeAndDateBetweenOrderByDateAsc(
                                    code, LocalDate.parse(startDate, FORMATTER), LocalDate.parse(endDate, FORMATTER));
                    for (StockDailyData d : stockData) {
                        dataMap.put(d.getDate(), d.getClosingPrice().doubleValue());
                    }
                }
            }
        } else if ("INDEX".equalsIgnoreCase(type)) {
            if ("KOSPI".equalsIgnoreCase(code) || "KOSDAQ".equalsIgnoreCase(code)) {
                var response = kisIndexService.getIndexChart(code, startDate, endDate, "D");
                for (var priceDto : response.getPriceList()) {
                    try {
                        dataMap.put(LocalDate.parse(priceDto.getDate(), FORMATTER),
                                Double.parseDouble(priceDto.getPrice()));
                    } catch (NumberFormatException e) {
                        log.warn("Skipping invalid price data for {} on {}", code, priceDto.getDate());
                    }
                }
            } else {
                FredIndex fredIndex = FredIndex.valueOf(code);
                List<FredIndexDataDto> data = fredIndexService.getFredIndexChart(fredIndex, startDate, endDate);
                for (FredIndexDataDto d : data) {
                    try {
                        dataMap.put(LocalDate.parse(d.getDate(), FORMATTER), Double.parseDouble(d.getPrice()));
                    } catch (NumberFormatException e) {
                        log.warn("Skipping invalid price data for {} on {}", code, d.getDate());
                    }
                }
            }
        } else if ("EXCHANGE".equalsIgnoreCase(type)) {
            List<MacroIndicatorDto> data = kisMacroService.fetchExchangeRate(code, startDate, endDate);
            for (MacroIndicatorDto d : data) {
                try {
                    dataMap.put(LocalDate.parse(d.getDate(), FORMATTER), Double.parseDouble(d.getValue()));
                } catch (NumberFormatException e) {
                    log.warn("Skipping invalid exchange rate data on {}", d.getDate());
                }
            }
        } else if ("BOND".equalsIgnoreCase(type)) {
            BondYield bond = BondYield.valueOf(code);
            List<MacroIndicatorDto> data = kisMacroService.fetchBondYield(bond, startDate, endDate);
            for (MacroIndicatorDto d : data) {
                try {
                    dataMap.put(LocalDate.parse(d.getDate(), FORMATTER), Double.parseDouble(d.getValue()));
                } catch (NumberFormatException e) {
                    log.warn("Skipping invalid bond yield data on {}", d.getDate());
                }
            }
        } else if ("BASE_RATE".equalsIgnoreCase(type)) {
            List<MacroIndicatorDto> data = ecosService.fetchBaseRate(startDate, endDate);
            for (MacroIndicatorDto d : data) {
                try {
                    dataMap.put(LocalDate.parse(d.getDate(), FORMATTER), Double.parseDouble(d.getValue()));
                } catch (NumberFormatException e) {
                    log.warn("Skipping invalid base rate data on {}", d.getDate());
                }
            }
        } else if ("CPI".equalsIgnoreCase(type)) {
            List<MacroIndicatorDto> data = ecosService.fetchCPI(startDate, endDate);
            for (MacroIndicatorDto d : data) {
                try {
                    dataMap.put(LocalDate.parse(d.getDate(), FORMATTER), Double.parseDouble(d.getValue()));
                } catch (NumberFormatException e) {
                    log.warn("Skipping invalid CPI data on {}", d.getDate());
                }
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
            if (previous == 0) {
                returns.add(0.0);
            } else {
                returns.add((current / previous) - 1.0);
            }
        }
        return returns;
    }

    private List<Double> calculateLogReturns(Map<LocalDate, Double> series, List<LocalDate> dates) {
        List<Double> returns = new ArrayList<>();
        for (int i = 1; i < dates.size(); i++) {
            double p_t = series.get(dates.get(i));
            double p_t_1 = series.get(dates.get(i - 1));
            // Log Return: ln(P_t / P_{t-1})
            if (p_t_1 <= 0 || p_t <= 0) {
                returns.add(0.0);
            } else {
                returns.add(Math.log(p_t / p_t_1));
            }
        }
        return returns;
    }

    private double calculatePValue(double r, int n) {
        if (n < 3)
            return 1.0;
        if (Double.isNaN(r) || Double.isInfinite(r))
            return 1.0;
        double rSquared = r * r;
        if (rSquared >= 1.0)
            return 0.0; // Perfect correlation
        double t = r * Math.sqrt((n - 2) / (1 - rSquared));
        TDistribution tDist = new TDistribution(n - 2);
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

    public double calculateVolatility(Map<LocalDate, Double> series) {
        List<LocalDate> dates = new ArrayList<>(series.keySet());
        Collections.sort(dates);
        List<Double> returns = calculateLogReturns(series, dates);

        if (returns.isEmpty()) {
            return 0.0;
        }

        DescriptiveStatistics stats = new DescriptiveStatistics();
        returns.forEach(stats::addValue);

        double stdDev = stats.getStandardDeviation();
        // Annualized Volatility (assuming 252 trading days)
        return stdDev * Math.sqrt(252);
    }

    public double calculateMDD(Map<LocalDate, Double> series) {
        double maxPeak = Double.MIN_VALUE;
        double maxDrawdown = 0.0;

        for (Double value : series.values()) {
            if (value > maxPeak) {
                maxPeak = value;
            }
            double drawdown = (maxPeak - value) / maxPeak;
            if (drawdown > maxDrawdown) {
                maxDrawdown = drawdown;
            }
        }
        return maxDrawdown; // Returns positive value (e.g., 0.20 for -20%)
    }

    public String calculateTrend(List<RollingCorrelationDto.RollingDataPoint> rollingData) {
        if (rollingData.size() < 10) {
            return "데이터 부족";
        }

        // Use recent 3 months (approx 60 days) or available data
        int lookback = Math.min(rollingData.size(), 60);
        List<RollingCorrelationDto.RollingDataPoint> recentData = rollingData.subList(rollingData.size() - lookback,
                rollingData.size());

        SimpleRegression regression = new SimpleRegression();
        for (int i = 0; i < recentData.size(); i++) {
            regression.addData(i, recentData.get(i).getCorrelation());
        }

        double slope = regression.getSlope();

        if (slope > 0.001) {
            return "증가";
        } else if (slope < -0.001) {
            return "감소";
        } else {
            return "보합";
        }
    }

    public double calculatePortfolioVariance(double w1, double var1, double w2, double var2, double correlation) {
        // Portfolio Variance = w1^2 * var1 + w2^2 * var2 + 2 * w1 * w2 * std1 * std2 *
        // correlation
        // Note: Input var1, var2 are Variances (std^2)
        double std1 = Math.sqrt(var1);
        double std2 = Math.sqrt(var2);
        return (w1 * w1 * var1) + (w2 * w2 * var2) + (2 * w1 * w2 * std1 * std2 * correlation);
    }

    private boolean isMacroIndicator(String assetType) {
        return "CPI".equalsIgnoreCase(assetType) ||
                "M2".equalsIgnoreCase(assetType) ||
                "BASE_RATE".equalsIgnoreCase(assetType);
    }

    public double calculateCAGR(Map<LocalDate, Double> series) {
        if (series == null || series.size() < 2)
            return 0.0;

        List<LocalDate> sortedDates = new ArrayList<>(series.keySet());
        Collections.sort(sortedDates);

        LocalDate startDate = sortedDates.get(0);
        LocalDate endDate = sortedDates.get(sortedDates.size() - 1);

        double startValue = series.get(startDate);
        double endValue = series.get(endDate);

        double years = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) / 365.25;
        if (years < 0.1)
            return 0.0; // Too short period
        if (startValue <= 0)
            return 0.0;

        return Math.pow(endValue / startValue, 1.0 / years) - 1.0;
    }

    public double calculateBeta(Map<LocalDate, Double> portfolioSeries, Map<LocalDate, Double> factorSeries) {
        List<LocalDate> commonDates = new ArrayList<>();
        for (LocalDate date : portfolioSeries.keySet()) {
            if (factorSeries.containsKey(date)) {
                commonDates.add(date);
            }
        }
        Collections.sort(commonDates);

        if (commonDates.size() < 30) // Minimum data requirement
            return 0.0;

        List<Double> portReturns = calculateLogReturns(portfolioSeries, commonDates);
        List<Double> factorReturns = calculateLogReturns(factorSeries, commonDates);

        double[] rP = portReturns.stream().mapToDouble(Double::doubleValue).toArray();
        double[] rF = factorReturns.stream().mapToDouble(Double::doubleValue).toArray();

        PearsonsCorrelation pc = new PearsonsCorrelation();
        double correlation = pc.correlation(rP, rF);

        DescriptiveStatistics statsP = new DescriptiveStatistics();
        portReturns.forEach(statsP::addValue);
        double volP = statsP.getStandardDeviation();

        DescriptiveStatistics statsF = new DescriptiveStatistics();
        factorReturns.forEach(statsF::addValue);
        double volF = statsF.getStandardDeviation();

        if (volF == 0)
            return 0.0;

        // Beta = Correlation * (Vol_Portfolio / Vol_Factor)
        return correlation * (volP / volF);
    }

    public double calculateDownsideCorrelation(Map<LocalDate, Double> portfolioSeries,
            Map<LocalDate, Double> factorSeries, double dropThreshold) {
        List<LocalDate> commonDates = new ArrayList<>();
        for (LocalDate date : portfolioSeries.keySet()) {
            if (factorSeries.containsKey(date)) {
                commonDates.add(date);
            }
        }
        Collections.sort(commonDates);

        List<Double> portReturns = calculateLogReturns(portfolioSeries, commonDates);
        List<Double> factorReturns = calculateLogReturns(factorSeries, commonDates);

        List<Double> downP = new ArrayList<>();
        List<Double> downF = new ArrayList<>();

        // Filter: Include ONLY when Factor Return < dropThreshold (e.g. -0.01 for -1%)
        // Or simple 'Negative Returns' if threshold is 0
        for (int i = 0; i < factorReturns.size(); i++) {
            if (factorReturns.get(i) < dropThreshold) {
                downP.add(portReturns.get(i));
                downF.add(factorReturns.get(i));
            }
        }

        if (downF.size() < 5)
            return 0.0; // Not enough downside samples

        double[] dP = downP.stream().mapToDouble(Double::doubleValue).toArray();
        double[] dF = downF.stream().mapToDouble(Double::doubleValue).toArray();

        PearsonsCorrelation pc = new PearsonsCorrelation();
        return pc.correlation(dP, dF);
    }
}
