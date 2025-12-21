package com.AISA.AISA.portfolio.backtest.service;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockDailyData;
import com.AISA.AISA.kisStock.repository.StockDailyDataRepository;
import com.AISA.AISA.kisStock.repository.StockRepository;
import com.AISA.AISA.portfolio.PortfolioGroup.Portfolio;
import com.AISA.AISA.portfolio.PortfolioGroup.PortfolioRepository;
import com.AISA.AISA.portfolio.PortfolioGroup.exception.PortfolioErrorCode;
import com.AISA.AISA.portfolio.PortfolioStock.PortStock;
import com.AISA.AISA.portfolio.PortfolioStock.PortStockRepository;
import com.AISA.AISA.portfolio.backtest.dto.*;
import com.AISA.AISA.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestService {

    private final PortfolioRepository portfolioRepository;
    private final PortStockRepository portStockRepository;
    private final StockDailyDataRepository stockDailyDataRepository;
    private final StockRepository stockRepository;

    @Transactional(readOnly = true)
    public BacktestResultDto calculatePortfolioBacktest(UUID portId, String startDateStr, String endDateStr) {
        Portfolio portfolio = portfolioRepository.findById(portId)
                .orElseThrow(() -> new BusinessException(PortfolioErrorCode.PORTFOLIO_NOT_FOUND));

        List<PortStock> portStocks = portStockRepository.findByPortfolio(portfolio);
        if (portStocks.isEmpty()) {
            throw new BusinessException(PortfolioErrorCode.PORTFOLIO_STOCK_NOT_FOUND);
        }

        Map<Stock, BigDecimal> stockQuantityMap = portStocks.stream()
                .collect(Collectors.toMap(PortStock::getStock, ps -> BigDecimal.valueOf(ps.getQuantity())));

        // Note: For existing portfolio backtest, we don't scale by InitialCapital. We
        // use actual quantities.
        // If we want to support "Initial Capital" for existing portfolios, we would
        // need to simulate buying.
        // For now, we keep the original behavior: simulate the *existing* holdings.
        return runBacktestSimulation(stockQuantityMap, startDateStr, endDateStr, portId, null, null);
    }

    @Transactional(readOnly = true)
    public BacktestCompareResultDto comparePortfolioBacktest(BacktestCompareRequestDto requestDto) {
        BacktestResultDto targetResult = calculatePortfolioBacktest(requestDto.getPortId(), requestDto.getStartDate(),
                requestDto.getEndDate());

        Map<Stock, BigDecimal> comparisonStockMap = new HashMap<>();
        for (ComparisonStockDto dto : requestDto.getComparisonStocks()) {
            Stock stock = stockRepository.findByStockCode(dto.getStockCode())
                    .orElseThrow(() -> new BusinessException(PortfolioErrorCode.PORTFOLIO_STOCK_NOT_FOUND));
            comparisonStockMap.put(stock, BigDecimal.valueOf(dto.getQuantity()));
        }

        BacktestResultDto comparisonResult = runBacktestSimulation(comparisonStockMap, requestDto.getStartDate(),
                requestDto.getEndDate(), null, null, null);

        return BacktestCompareResultDto.builder()
                .targetPortfolioResult(targetResult)
                .comparisonGroupResult(comparisonResult)
                .build();
    }

    @Transactional(readOnly = true)
    public MultiStrategyBacktestResultDto calculateMultiStrategyBacktest(MultiStrategyBacktestRequestDto requestDto) {
        List<MultiStrategyBacktestResultDto.StrategyBacktestResultDto> results = new ArrayList<>();
        BigDecimal initialCapital = requestDto.getInitialCapital();
        String startDateStr = requestDto.getStartDate();
        String endDateStr = requestDto.getEndDate();

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        LocalDate startDate = LocalDate.parse(startDateStr, formatter);

        for (StrategyBacktestRequestDto strategyDto : requestDto.getStrategies()) {
            Map<Stock, BigDecimal> strategyQuantities = new HashMap<>();
            Map<String, BigDecimal> allocatedCapitalMap = new HashMap<>();

            // 1. Resolve stocks and weights
            Map<Stock, BigDecimal> weights = new HashMap<>();
            for (StrategyAssetDto assetDto : strategyDto.getAssets()) {
                Stock stock = stockRepository.findByStockCode(assetDto.getSymbol())
                        .orElseThrow(() -> new BusinessException(PortfolioErrorCode.PORTFOLIO_STOCK_NOT_FOUND));
                weights.put(stock, assetDto.getWeight());
            }

            // 2. Pre-allocate Capital (Lazy Buying Mode)
            // Instead of finding price immediately, we allocate capital to each stock
            // and let the simulation loop "buy" when the price first appears.
            for (Map.Entry<Stock, BigDecimal> entry : weights.entrySet()) {
                Stock stock = entry.getKey();
                BigDecimal weightPercent = entry.getValue();

                // Allocated Capital = InitialCapital * (Weight / 100)
                BigDecimal allocatedCapital = initialCapital.multiply(weightPercent).divide(BigDecimal.valueOf(100),
                        4, RoundingMode.HALF_UP);

                // Initial Quantity is 0. Will be bought in simulation.
                strategyQuantities.put(stock, BigDecimal.ZERO);
                allocatedCapitalMap.put(stock.getStockCode(), allocatedCapital);
            }

            // 3. Run Simulation
            BacktestResultDto result = runBacktestSimulation(strategyQuantities, startDateStr, endDateStr, null,
                    initialCapital, allocatedCapitalMap);

            results.add(MultiStrategyBacktestResultDto.StrategyBacktestResultDto.builder()
                    .strategyName(strategyDto.getStrategyName())
                    .result(result)
                    .build());
        }

        return MultiStrategyBacktestResultDto.builder()
                .results(results)
                .build();
    }

    private BacktestResultDto runBacktestSimulation(Map<Stock, BigDecimal> stockQuantityMap, String startDateStr,
            String endDateStr, UUID portId, BigDecimal initialCapitalOverride,
            Map<String, BigDecimal> allocatedCapitalMap) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        LocalDate startDate = LocalDate.parse(startDateStr, formatter);
        LocalDate endDate = LocalDate.parse(endDateStr, formatter);

        List<Stock> stocks = new ArrayList<>(stockQuantityMap.keySet());

        if (stocks.isEmpty()) {
            return buildEmptyResult(portId, startDateStr, endDateStr);
        }

        List<StockDailyData> allDailyData = stockDailyDataRepository.findAllByStockInAndDateBetweenOrderByDateAsc(
                stocks, startDate, endDate);

        Map<LocalDate, List<StockDailyData>> dataByDate = allDailyData.stream()
                .collect(Collectors.groupingBy(StockDailyData::getDate));

        List<LocalDate> sortedDates = new ArrayList<>(dataByDate.keySet());
        Collections.sort(sortedDates);

        if (sortedDates.isEmpty()) {
            return buildEmptyResult(portId, startDateStr, endDateStr);
        }

        List<DailyPortfolioValueDto> dailyValues = new ArrayList<>();
        Map<String, BigDecimal> currentPrices = new HashMap<>();
        Map<String, BigDecimal> prevPrices = new HashMap<>();

        BigDecimal maxTotalValue = BigDecimal.ZERO;
        double maxDrawdown = 0.0;

        // If initialCapitalOverride is provided, we use it to calculate returns
        // relative to the invested capital?
        // Actually, "Total Value" is Sum(Price * Qty).
        // If quantities were calculated from Initial Capital, then Total Value at Day 0
        // should be approx Initial Capital.
        // We track "Total Value" as the primary metric.
        // "Adjusted Value" usually starts at 1000.0 or 100.0 for index comparison.
        BigDecimal currentAdjustedValue = new BigDecimal("1000.0");

        Map<String, BigDecimal> codeQuantityMap = stockQuantityMap.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().getStockCode(), Map.Entry::getValue));

        // Mutable map for remaining allocated capital
        Map<String, BigDecimal> remainingCapitalMap = new HashMap<>();
        if (allocatedCapitalMap != null) {
            remainingCapitalMap.putAll(allocatedCapitalMap);
        }

        for (LocalDate date : sortedDates) {
            List<StockDailyData> dailyDataList = dataByDate.get(date);

            for (StockDailyData data : dailyDataList) {
                currentPrices.put(data.getStock().getStockCode(), data.getClosingPrice());
            }

            BigDecimal currentTotalValue = BigDecimal.ZERO;
            BigDecimal currentBalance = BigDecimal.ZERO;

            for (String stockCode : codeQuantityMap.keySet()) {
                BigDecimal price = currentPrices.get(stockCode);

                // --- Lazy Buying Logic ---
                // If we have 0 quantity but hold allocated capital, and price exists -> BUY
                BigDecimal quantity = codeQuantityMap.get(stockCode);
                if (quantity.compareTo(BigDecimal.ZERO) == 0
                        && remainingCapitalMap.containsKey(stockCode)
                        && price != null && price.compareTo(BigDecimal.ZERO) > 0) {

                    BigDecimal capitalToInvest = remainingCapitalMap.get(stockCode);
                    quantity = capitalToInvest.divide(price, 4, RoundingMode.HALF_UP);

                    codeQuantityMap.put(stockCode, quantity); // Update quantity
                    remainingCapitalMap.remove(stockCode); // Capital used
                }

                // --- Valuation ---
                if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                    currentTotalValue = currentTotalValue.add(price.multiply(codeQuantityMap.get(stockCode)));
                }

                // If still not bought (capital remains), add to balance
                if (remainingCapitalMap.containsKey(stockCode)) {
                    currentBalance = currentBalance.add(remainingCapitalMap.get(stockCode));
                }
            }

            // Total Value = Stock Value + Cash Balance
            currentTotalValue = currentTotalValue.add(currentBalance);

            Double dailyReturnRate = 0.0;
            if (!prevPrices.isEmpty()) {
                BigDecimal sumPrev = BigDecimal.ZERO;
                BigDecimal sumCurr = BigDecimal.ZERO;

                for (String stockCode : codeQuantityMap.keySet()) {
                    if (prevPrices.containsKey(stockCode) && currentPrices.containsKey(stockCode)) {
                        BigDecimal qty = codeQuantityMap.get(stockCode);
                        sumPrev = sumPrev.add(prevPrices.get(stockCode).multiply(qty));
                        sumCurr = sumCurr.add(currentPrices.get(stockCode).multiply(qty));
                    }
                }

                if (sumPrev.compareTo(BigDecimal.ZERO) > 0) {
                    dailyReturnRate = sumCurr.subtract(sumPrev)
                            .divide(sumPrev, 8, RoundingMode.HALF_UP)
                            .doubleValue();
                }
            }

            currentAdjustedValue = currentAdjustedValue.multiply(BigDecimal.valueOf(1.0 + dailyReturnRate));
            Double dailyReturnPercent = dailyReturnRate * 100.0;

            dailyValues.add(new DailyPortfolioValueDto(
                    date.format(formatter),
                    currentTotalValue,
                    dailyReturnPercent,
                    currentAdjustedValue,
                    currentBalance));

            prevPrices = new HashMap<>(currentPrices);

            if (currentTotalValue.compareTo(maxTotalValue) > 0) {
                maxTotalValue = currentTotalValue;
            }

            if (maxTotalValue.compareTo(BigDecimal.ZERO) > 0) {
                double drawdown = maxTotalValue.subtract(currentTotalValue)
                        .divide(maxTotalValue, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal(100))
                        .doubleValue();
                if (drawdown > maxDrawdown) {
                    maxDrawdown = drawdown;
                }
            }
        }

        if (dailyValues.isEmpty())
            return buildEmptyResult(portId, startDateStr, endDateStr);

        BigDecimal initialValue = dailyValues.get(0).getTotalValue(); // First day's market value
        BigDecimal finalValue = dailyValues.get(dailyValues.size() - 1).getTotalValue();

        // If we want to use the explicit InitialCapital as the "Start Value" for Return
        // calculation:
        // usually return is (Final - Initial) / Initial.
        // If Initial Market Value differs from Initial Capital (due to cash drag or
        // price changes on day 0?),
        // usually we care about the positions value.
        // Let's stick to the "Sum of positions" as initial value.

        Double totalReturnRate = 0.0;
        Double cagr = 0.0;

        if (initialValue.compareTo(BigDecimal.ZERO) > 0) {
            totalReturnRate = finalValue.subtract(initialValue)
                    .divide(initialValue, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal(100))
                    .doubleValue();

            long days = java.time.temporal.ChronoUnit.DAYS.between(sortedDates.get(0),
                    sortedDates.get(sortedDates.size() - 1));
            double years = days / 365.0;
            if (years > 0) {
                cagr = (Math.pow(finalValue.doubleValue() / initialValue.doubleValue(), 1.0 / years) - 1) * 100;
            }
        }

        return BacktestResultDto.builder()
                .portId(portId)
                .startDate(startDateStr)
                .endDate(endDateStr)
                .initialValue(initialValue)
                .finalValue(finalValue)
                .totalReturnRate(totalReturnRate)
                .cagr(Math.round(cagr * 100.0) / 100.0)
                .mdd(Math.round(maxDrawdown * 100.0) / 100.0)
                .dailyValues(dailyValues)
                .build();
    }

    private BacktestResultDto buildEmptyResult(UUID portId, String startDate, String endDate) {
        return BacktestResultDto.builder()
                .portId(portId)
                .startDate(startDate)
                .endDate(endDate)
                .initialValue(BigDecimal.ZERO)
                .finalValue(BigDecimal.ZERO)
                .totalReturnRate(0.0)
                .cagr(0.0)
                .mdd(0.0)
                .dailyValues(Collections.emptyList())
                .build();
    }
}
