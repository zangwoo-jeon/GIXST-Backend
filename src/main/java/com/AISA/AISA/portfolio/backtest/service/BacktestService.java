package com.AISA.AISA.portfolio.backtest.service;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockDailyData;
import com.AISA.AISA.kisOverseasStock.entity.OverseasStockDailyData;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockDailyDataRepository;
import com.AISA.AISA.kisStock.kisService.KisMacroService;
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
        private final KisOverseasStockDailyDataRepository overseasStockDailyDataRepository;
        private final StockRepository stockRepository;
        private final KisMacroService kisMacroService;

        @Transactional(readOnly = true)
        public BacktestResultDto calculatePortfolioBacktest(UUID portId, String startDateStr, String endDateStr,
                        BigDecimal initialCapital) {
                Portfolio portfolio = portfolioRepository.findById(portId)
                                .orElseThrow(() -> new BusinessException(PortfolioErrorCode.PORTFOLIO_NOT_FOUND));

                List<PortStock> portStocks = portStockRepository.findByPortfolioWithStock(portfolio);
                if (portStocks.isEmpty()) {
                        throw new BusinessException(PortfolioErrorCode.PORTFOLIO_STOCK_NOT_FOUND);
                }

                Map<Stock, BigDecimal> stockQuantityMap = portStocks.stream()
                                .collect(Collectors.toMap(PortStock::getStock,
                                                ps -> BigDecimal.valueOf(ps.getQuantity())));

                if (initialCapital != null) {
                        // Capital-based simulation: calculate weights and allocate capital
                        Map<Stock, BigDecimal> strategyQuantities = new HashMap<>();
                        Map<String, BigDecimal> allocatedCapitalMap = new HashMap<>();

                        // 1. Get latest prices for weighting (using the same logic as simulation
                        // fallback)
                        Double latestExchangeRate = kisMacroService.getLatestExchangeRate();
                        BigDecimal exchangeRate = (latestExchangeRate != null) ? BigDecimal.valueOf(latestExchangeRate)
                                        : BigDecimal.valueOf(1350.0);

                        BigDecimal totalCurrentValueKrw = BigDecimal.ZERO;
                        Map<Stock, BigDecimal> positionsValueKrw = new HashMap<>();

                        for (PortStock ps : portStocks) {
                                Stock stock = ps.getStock();
                                BigDecimal latestPrice = BigDecimal.ZERO;

                                if (stock.getStockType() == Stock.StockType.US_STOCK
                                                || stock.getStockType() == Stock.StockType.US_ETF) {
                                        latestPrice = overseasStockDailyDataRepository.findLatestPriceByStock(stock)
                                                        .orElse(BigDecimal.ZERO);
                                        totalCurrentValueKrw = totalCurrentValueKrw
                                                        .add(latestPrice.multiply(BigDecimal.valueOf(ps.getQuantity()))
                                                                        .multiply(exchangeRate));
                                        positionsValueKrw.put(stock,
                                                        latestPrice.multiply(BigDecimal.valueOf(ps.getQuantity()))
                                                                        .multiply(exchangeRate));
                                } else {
                                        latestPrice = stockDailyDataRepository.findLatestPriceByStock(stock)
                                                        .orElse(BigDecimal.ZERO);
                                        totalCurrentValueKrw = totalCurrentValueKrw
                                                        .add(latestPrice.multiply(
                                                                        BigDecimal.valueOf(ps.getQuantity())));
                                        positionsValueKrw.put(stock,
                                                        latestPrice.multiply(BigDecimal.valueOf(ps.getQuantity())));
                                }
                        }

                        // 2. Allocate Capital based on weights
                        if (totalCurrentValueKrw.compareTo(BigDecimal.ZERO) > 0) {
                                for (PortStock ps : portStocks) {
                                        Stock stock = ps.getStock();
                                        BigDecimal weight = positionsValueKrw.get(stock).divide(totalCurrentValueKrw, 8,
                                                        RoundingMode.HALF_UP);
                                        BigDecimal allocatedCapital = initialCapital.multiply(weight);

                                        strategyQuantities.put(stock, BigDecimal.ZERO); // Buying in simulation
                                        allocatedCapitalMap.put(stock.getStockCode(), allocatedCapital);
                                }
                        }

                        return runBacktestSimulation(strategyQuantities, startDateStr, endDateStr, portId,
                                        initialCapital,
                                        allocatedCapitalMap);
                }

                // Existing behavior: simulate the actual quantities
                return runBacktestSimulation(stockQuantityMap, startDateStr, endDateStr, portId, null, null);
        }

        @Transactional(readOnly = true)
        public BacktestCompareResultDto comparePortfolioBacktest(BacktestCompareRequestDto requestDto) {
                BacktestResultDto targetResult = calculatePortfolioBacktest(requestDto.getPortId(),
                                requestDto.getStartDate(),
                                requestDto.getEndDate(), null);

                Map<Stock, BigDecimal> comparisonStockMap = new HashMap<>();
                for (ComparisonStockDto dto : requestDto.getComparisonStocks()) {
                        Stock stock = stockRepository.findByStockCode(dto.getStockCode())
                                        .orElseThrow(() -> new BusinessException(
                                                        PortfolioErrorCode.PORTFOLIO_STOCK_NOT_FOUND));
                        comparisonStockMap.put(stock, BigDecimal.valueOf(dto.getQuantity()));
                }

                BacktestResultDto comparisonResult = runBacktestSimulation(comparisonStockMap,
                                requestDto.getStartDate(),
                                requestDto.getEndDate(), null, null, null);

                return BacktestCompareResultDto.builder()
                                .targetPortfolioResult(targetResult)
                                .comparisonGroupResult(comparisonResult)
                                .build();
        }

        @Transactional(readOnly = true)
        public MultiStrategyBacktestResultDto calculateMultiStrategyBacktest(
                        MultiStrategyBacktestRequestDto requestDto) {
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
                                                .orElseThrow(() -> new BusinessException(
                                                                PortfolioErrorCode.PORTFOLIO_STOCK_NOT_FOUND));
                                weights.put(stock, assetDto.getWeight());
                        }

                        // 2. Pre-allocate Capital (Lazy Buying Mode)
                        // Instead of finding price immediately, we allocate capital to each stock
                        // and let the simulation loop "buy" when the price first appears.
                        for (Map.Entry<Stock, BigDecimal> entry : weights.entrySet()) {
                                Stock stock = entry.getKey();
                                BigDecimal weightPercent = entry.getValue();

                                // Allocated Capital = InitialCapital * (Weight / 100)
                                BigDecimal allocatedCapital = initialCapital.multiply(weightPercent).divide(
                                                BigDecimal.valueOf(100),
                                                4, RoundingMode.HALF_UP);

                                // Initial Quantity is 0. Will be bought in simulation.
                                strategyQuantities.put(stock, BigDecimal.ZERO);
                                allocatedCapitalMap.put(stock.getStockCode(), allocatedCapital);
                        }

                        // 3. Run Simulation
                        BacktestResultDto result = runBacktestSimulation(strategyQuantities, startDateStr, endDateStr,
                                        null,
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

                List<Stock> domesticStocks = stocks.stream()
                                .filter(s -> s.getStockType() == Stock.StockType.DOMESTIC ||
                                                s.getStockType() == Stock.StockType.DOMESTIC_ETF ||
                                                s.getStockType() == Stock.StockType.FOREIGN_ETF)
                                .collect(Collectors.toList());

                List<Stock> overseasStocks = stocks.stream()
                                .filter(s -> s.getStockType() == Stock.StockType.US_STOCK ||
                                                s.getStockType() == Stock.StockType.US_ETF)
                                .collect(Collectors.toList());

                List<StockDailyData> domesticData = domesticStocks.isEmpty() ? Collections.emptyList()
                                : stockDailyDataRepository.findAllByStockInAndDateBetweenOrderByDateAsc(domesticStocks,
                                                startDate,
                                                endDate);

                List<OverseasStockDailyData> overseasData = overseasStocks.isEmpty() ? Collections.emptyList()
                                : overseasStockDailyDataRepository.findAllByStockInAndDateBetweenOrderByDateAsc(
                                                overseasStocks,
                                                startDate, endDate);

                Map<String, Double> exchangeRateMap = kisMacroService.getExchangeRateMap(startDateStr, endDateStr);
                Double latestRate = kisMacroService.getLatestExchangeRate();
                BigDecimal fallbackRate = BigDecimal.valueOf(latestRate != null ? latestRate : 1350.0);

                // Group data by date
                Map<LocalDate, Map<String, BigDecimal>> pricesByDateMap = new HashMap<>();

                for (StockDailyData d : domesticData) {
                        pricesByDateMap.computeIfAbsent(d.getDate(), k -> new HashMap<>())
                                        .put(d.getStock().getStockCode(), d.getClosingPrice());
                }
                for (OverseasStockDailyData d : overseasData) {
                        pricesByDateMap.computeIfAbsent(d.getDate(), k -> new HashMap<>())
                                        .put(d.getStock().getStockCode(), d.getClosingPrice());
                }

                List<LocalDate> sortedDates = new ArrayList<>(pricesByDateMap.keySet());
                Collections.sort(sortedDates);

                if (sortedDates.isEmpty()) {
                        return buildEmptyResult(portId, startDateStr, endDateStr);
                }

                List<DailyPortfolioValueDto> dailyValues = new ArrayList<>();
                Map<String, BigDecimal> currentPrices = new HashMap<>();
                Map<String, BigDecimal> prevPricesKrw = new HashMap<>(); // Track price in KRW

                BigDecimal maxTotalValue = BigDecimal.ZERO;
                double maxDrawdown = 0.0;
                BigDecimal currentAdjustedValue = new BigDecimal("1000.0");

                Map<String, BigDecimal> codeQuantityMap = stockQuantityMap.entrySet().stream()
                                .collect(Collectors.toMap(e -> e.getKey().getStockCode(), Map.Entry::getValue));

                // Map for quick stock lookup
                Map<String, Stock> stockLookup = stocks.stream()
                                .collect(Collectors.toMap(Stock::getStockCode, s -> s));

                Map<String, BigDecimal> remainingCapitalMap = new HashMap<>();
                if (allocatedCapitalMap != null) {
                        remainingCapitalMap.putAll(allocatedCapitalMap);
                }

                for (LocalDate date : sortedDates) {
                        Map<String, BigDecimal> dailyPrices = pricesByDateMap.get(date);
                        currentPrices.putAll(dailyPrices);

                        // Get exchange rate for the day
                        String dateStr = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                        BigDecimal exchangeRate = exchangeRateMap.containsKey(dateStr)
                                        ? BigDecimal.valueOf(exchangeRateMap.get(dateStr))
                                        : fallbackRate;

                        BigDecimal currentTotalValueKrw = BigDecimal.ZERO;
                        BigDecimal currentBalanceKrw = BigDecimal.ZERO;
                        Map<String, BigDecimal> currentPricesKrw = new HashMap<>();

                        for (String stockCode : codeQuantityMap.keySet()) {
                                Stock stock = stockLookup.get(stockCode);
                                BigDecimal rawPrice = currentPrices.get(stockCode);
                                boolean isUsAsset = stock.getStockType() == Stock.StockType.US_STOCK
                                                || stock.getStockType() == Stock.StockType.US_ETF;

                                BigDecimal priceKrw = rawPrice;
                                if (rawPrice != null && isUsAsset) {
                                        priceKrw = rawPrice.multiply(exchangeRate);
                                }
                                currentPricesKrw.put(stockCode, priceKrw);

                                // --- Lazy Buying Logic ---
                                BigDecimal quantity = codeQuantityMap.get(stockCode);
                                if (quantity.compareTo(BigDecimal.ZERO) == 0
                                                && remainingCapitalMap.containsKey(stockCode)
                                                && priceKrw != null && priceKrw.compareTo(BigDecimal.ZERO) > 0) {

                                        BigDecimal capitalToInvestKrw = remainingCapitalMap.get(stockCode);
                                        quantity = capitalToInvestKrw.divide(priceKrw, 4, RoundingMode.HALF_UP);

                                        codeQuantityMap.put(stockCode, quantity);
                                        remainingCapitalMap.remove(stockCode);
                                }

                                // --- Valuation ---
                                if (priceKrw != null && priceKrw.compareTo(BigDecimal.ZERO) > 0) {
                                        currentTotalValueKrw = currentTotalValueKrw
                                                        .add(priceKrw.multiply(codeQuantityMap.get(stockCode)));
                                }

                                if (remainingCapitalMap.containsKey(stockCode)) {
                                        currentBalanceKrw = currentBalanceKrw.add(remainingCapitalMap.get(stockCode));
                                }
                        }

                        currentTotalValueKrw = currentTotalValueKrw.add(currentBalanceKrw);

                        Double dailyReturnRate = 0.0;
                        if (!prevPricesKrw.isEmpty()) {
                                BigDecimal sumPrevKrw = BigDecimal.ZERO;
                                BigDecimal sumCurrKrw = BigDecimal.ZERO;

                                for (String stockCode : codeQuantityMap.keySet()) {
                                        BigDecimal pPrev = prevPricesKrw.get(stockCode);
                                        BigDecimal pCurr = currentPricesKrw.get(stockCode);
                                        if (pPrev != null && pCurr != null) {
                                                BigDecimal qty = codeQuantityMap.get(stockCode);
                                                sumPrevKrw = sumPrevKrw.add(pPrev.multiply(qty));
                                                sumCurrKrw = sumCurrKrw.add(pCurr.multiply(qty));
                                        }
                                }

                                if (sumPrevKrw.compareTo(BigDecimal.ZERO) > 0) {
                                        dailyReturnRate = sumCurrKrw.subtract(sumPrevKrw)
                                                        .divide(sumPrevKrw, 8, RoundingMode.HALF_UP)
                                                        .doubleValue();
                                }
                        }

                        prevPricesKrw.putAll(currentPricesKrw);
                        currentAdjustedValue = currentAdjustedValue.multiply(BigDecimal.valueOf(1.0 + dailyReturnRate));

                        dailyValues.add(DailyPortfolioValueDto.builder()
                                        .date(date.format(formatter))
                                        .totalValue(currentTotalValueKrw.setScale(0, RoundingMode.HALF_UP))
                                        .dailyReturnRate(Math.round(dailyReturnRate * 100.0 * 1000.0) / 1000.0)
                                        .adjustedValue(currentAdjustedValue.setScale(3, RoundingMode.HALF_UP))
                                        .balance(currentBalanceKrw.setScale(3, RoundingMode.HALF_UP))
                                        .build());

                        if (currentTotalValueKrw.compareTo(maxTotalValue) > 0) {
                                maxTotalValue = currentTotalValueKrw;
                        }

                        if (maxTotalValue.compareTo(BigDecimal.ZERO) > 0) {
                                double drawdown = maxTotalValue.subtract(currentTotalValueKrw)
                                                .divide(maxTotalValue, 8, RoundingMode.HALF_UP)
                                                .multiply(new BigDecimal(100))
                                                .doubleValue();
                                if (drawdown > maxDrawdown) {
                                        maxDrawdown = drawdown;
                                }
                        }
                }

                if (dailyValues.isEmpty())
                        return buildEmptyResult(portId, startDateStr, endDateStr);

                BigDecimal initialValue = dailyValues.get(0).getTotalValue().setScale(0, RoundingMode.HALF_UP);
                BigDecimal finalValue = dailyValues.get(dailyValues.size() - 1).getTotalValue().setScale(0,
                                RoundingMode.HALF_UP);

                Double totalReturnRate = 0.0;
                Double cagr = 0.0;

                if (initialValue.compareTo(BigDecimal.ZERO) > 0) {
                        totalReturnRate = finalValue.subtract(initialValue)
                                        .divide(initialValue, 8, RoundingMode.HALF_UP)
                                        .multiply(new BigDecimal(100))
                                        .doubleValue();

                        long days = java.time.temporal.ChronoUnit.DAYS.between(sortedDates.get(0),
                                        sortedDates.get(sortedDates.size() - 1));
                        double years = days / 365.0;
                        if (years > 0) {
                                cagr = (Math.pow(finalValue.doubleValue() / initialValue.doubleValue(), 1.0 / years)
                                                - 1) * 100;
                        }
                }

                return BacktestResultDto.builder()
                                .portId(portId)
                                .startDate(startDateStr)
                                .endDate(endDateStr)
                                .initialValue(initialValue)
                                .finalValue(finalValue)
                                .totalReturnRate(Math.round(totalReturnRate * 1000.0) / 1000.0)
                                .cagr(Math.round(cagr * 1000.0) / 1000.0)
                                .mdd(Math.round(maxDrawdown * 1000.0) / 1000.0)
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
