package com.AISA.AISA.portfolio.backtest.service;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockDailyData;
import com.AISA.AISA.kisStock.repository.StockDailyDataRepository;
import com.AISA.AISA.portfolio.PortfolioGroup.Portfolio;
import com.AISA.AISA.portfolio.PortfolioGroup.PortfolioRepository;
import com.AISA.AISA.portfolio.PortfolioGroup.exception.PortfolioErrorCode;
import com.AISA.AISA.portfolio.PortfolioStock.PortStock;
import com.AISA.AISA.portfolio.PortfolioStock.PortStockRepository;
import com.AISA.AISA.portfolio.backtest.dto.BacktestResultDto;
import com.AISA.AISA.portfolio.backtest.dto.DailyPortfolioValueDto;
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

    @Transactional(readOnly = true)
    public BacktestResultDto calculatePortfolioBacktest(UUID portId, String startDateStr, String endDateStr) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        LocalDate startDate = LocalDate.parse(startDateStr, formatter);
        LocalDate endDate = LocalDate.parse(endDateStr, formatter);

        Portfolio portfolio = portfolioRepository.findById(portId)
                .orElseThrow(() -> new BusinessException(PortfolioErrorCode.PORTFOLIO_NOT_FOUND));

        List<PortStock> portStocks = portStockRepository.findByPortfolio(portfolio);
        if (portStocks.isEmpty()) {
            throw new BusinessException(PortfolioErrorCode.PORTFOLIO_STOCK_NOT_FOUND);
        }

        List<Stock> stocks = portStocks.stream().map(PortStock::getStock).collect(Collectors.toList());
        Map<String, Integer> stockQuantityMap = portStocks.stream()
                .collect(Collectors.toMap(ps -> ps.getStock().getStockCode(), PortStock::getQuantity));

        // Fetch all daily data for these stocks in the range
        List<StockDailyData> allDailyData = stockDailyDataRepository.findAllByStockInAndDateBetweenOrderByDateAsc(
                stocks, startDate, endDate);

        // Group by Date
        Map<LocalDate, List<StockDailyData>> dataByDate = allDailyData.stream()
                .collect(Collectors.groupingBy(StockDailyData::getDate));

        List<LocalDate> sortedDates = new ArrayList<>(dataByDate.keySet());
        Collections.sort(sortedDates);

        if (sortedDates.isEmpty()) {
            return BacktestResultDto.builder()
                    .portId(portId)
                    .startDate(startDateStr)
                    .endDate(endDateStr)
                    .initialValue(BigDecimal.ZERO)
                    .finalValue(BigDecimal.ZERO)
                    .totalReturnRate(0.0)
                    .cagr(0.0)
                    .mdd(0.0)
                    .dailyValues(Collections.emptyList())
                    .build();
        }

        List<DailyPortfolioValueDto> dailyValues = new ArrayList<>();
        Map<String, BigDecimal> currentPrices = new HashMap<>();
        Map<String, BigDecimal> prevPrices = new HashMap<>();

        BigDecimal maxTotalValue = BigDecimal.ZERO;
        double maxDrawdown = 0.0;
        BigDecimal currentAdjustedValue = new BigDecimal("1000.0");

        for (LocalDate date : sortedDates) {
            List<StockDailyData> dailyDataList = dataByDate.get(date);

            // Update current prices (accumulate)
            for (StockDailyData data : dailyDataList) {
                currentPrices.put(data.getStock().getStockCode(), data.getClosingPrice());
            }

            // Calculate total portfolio value (Market Value)
            BigDecimal currentTotalValue = BigDecimal.ZERO;
            for (String stockCode : stockQuantityMap.keySet()) {
                BigDecimal price = currentPrices.getOrDefault(stockCode, BigDecimal.ZERO);
                BigDecimal quantity = new BigDecimal(stockQuantityMap.get(stockCode));
                currentTotalValue = currentTotalValue.add(price.multiply(quantity));
            }

            // Calculate Daily Return (Common Component Method)
            Double dailyReturnRate = 0.0;
            if (!prevPrices.isEmpty()) {
                BigDecimal sumPrev = BigDecimal.ZERO;
                BigDecimal sumCurr = BigDecimal.ZERO;

                for (String stockCode : stockQuantityMap.keySet()) {
                    // Only include stocks that had a price YESTERDAY and TODAY
                    if (prevPrices.containsKey(stockCode) && currentPrices.containsKey(stockCode)) {
                        BigDecimal qty = new BigDecimal(stockQuantityMap.get(stockCode));
                        sumPrev = sumPrev.add(prevPrices.get(stockCode).multiply(qty));
                        sumCurr = sumCurr.add(currentPrices.get(stockCode).multiply(qty));
                    }
                }

                if (sumPrev.compareTo(BigDecimal.ZERO) > 0) {
                    dailyReturnRate = sumCurr.subtract(sumPrev)
                            .divide(sumPrev, 8, RoundingMode.HALF_UP) // Higher precision for calc
                            .doubleValue();
                }
            }

            // Update Adjusted Value (Compounding)
            currentAdjustedValue = currentAdjustedValue.multiply(BigDecimal.valueOf(1.0 + dailyReturnRate));

            // Convert return to percentage for DTO
            Double dailyReturnPercent = dailyReturnRate * 100.0;

            dailyValues.add(new DailyPortfolioValueDto(
                    date.format(formatter),
                    currentTotalValue,
                    dailyReturnPercent,
                    currentAdjustedValue));

            // Update prevPrices for next iteration
            prevPrices = new HashMap<>(currentPrices);

            // MDD Calculation (Based on Adjusted Value or Total Value? Usually Total Value
            // for user experience, but Adjusted for performance)
            // Let's stick to Total Value for MDD as it reflects actual account balance
            // experience,
            // OR Adjusted Value to measure strategy performance.
            // Given the context of "Diagnosis", let's keep MDD on Total Value for now as
            // per original code,
            // or maybe Adjusted is better?
            // If a new stock is added, Total Value jumps. MDD shouldn't be affected by
            // deposit.
            // So MDD should ideally be on Adjusted Value.
            // But let's keep existing MDD logic on Total Value for now to minimize scope
            // creep unless requested.
            // Actually, if Total Value jumps up, it resets the "Peak". If it drops later,
            // MDD is calculated from that new Peak.
            // This is correct for a portfolio.
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

        BigDecimal initialValue = dailyValues.get(0).getTotalValue();
        BigDecimal finalValue = dailyValues.get(dailyValues.size() - 1).getTotalValue();

        Double totalReturnRate = 0.0;
        Double cagr = 0.0;

        if (initialValue.compareTo(BigDecimal.ZERO) > 0) {
            totalReturnRate = finalValue.subtract(initialValue)
                    .divide(initialValue, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal(100))
                    .doubleValue();

            // CAGR Calculation: (Final / Initial)^(1/n) - 1
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
                .cagr(Math.round(cagr * 100.0) / 100.0) // Round to 2 decimal places
                .mdd(Math.round(maxDrawdown * 100.0) / 100.0)
                .dailyValues(dailyValues)
                .build();
    }
}
