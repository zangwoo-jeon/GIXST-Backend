package com.AISA.AISA.kisOverseasStock.service;

import com.AISA.AISA.kisOverseasStock.dto.KisOverseasPriceApiResponse;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockRepository;
import com.AISA.AISA.kisOverseasStock.config.KisOverseasApiProperties;
import com.AISA.AISA.kisOverseasStock.entity.OverseasStockDailyData;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockDailyDataRepository;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.config.KisApiProperties;
import com.AISA.AISA.kisStock.dto.StockPrice.StockPriceDto;
import com.AISA.AISA.kisStock.dto.StockSimpleSearchResponseDto;
import com.AISA.AISA.kisStock.exception.KisApiErrorCode;
import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.global.util.StockCodeUtils;
import com.AISA.AISA.kisStock.kisService.KisApiClient;
import com.AISA.AISA.kisOverseasStock.dto.KisOverseasChartApiResponse;
import com.AISA.AISA.kisOverseasStock.dto.KisOverseasStockChartDto;
import com.AISA.AISA.kisStock.kisService.KisMacroService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class KisOverseasStockService {

    private final KisOverseasStockRepository overseasStockRepository;
    private final KisOverseasStockDailyDataRepository overseasStockDailyDataRepository;
    private final WebClient webClient;
    private final KisApiProperties kisApiProperties;
    private final KisOverseasApiProperties overseasApiProperties;
    private final KisApiClient kisApiClient;
    private final KisMacroService kisMacroService;

    @Transactional(readOnly = true)
    public List<StockSimpleSearchResponseDto> searchOverseasStocks(String keyword) {
        List<Stock> stocks = overseasStockRepository.findByKeyword(keyword);

        return stocks.stream()
                .map(stock -> StockSimpleSearchResponseDto.builder()
                        .stockCode(stock.getStockCode())
                        .stockName(stock.getStockName())
                        .marketName(stock.getMarketName())
                        .build())
                .collect(Collectors.toList());
    }

    public StockPriceDto getOverseasStockPrice(String stockCode) {
        Stock stock = overseasStockRepository.findByStockCode(stockCode)
                .filter(s -> s.getStockType() == Stock.StockType.US_STOCK || s.getStockType() == Stock.StockType.US_ETF)
                .orElseGet(() -> {
                    try {
                        Long stockId = Long.parseLong(stockCode);
                        return overseasStockRepository.findById(stockId).orElse(null);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                });

        if (stock == null) {
            throw new BusinessException(KisApiErrorCode.STOCK_NOT_FOUND);
        }

        KisOverseasPriceApiResponse response;
        try {
            response = kisApiClient.fetch(token -> webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(overseasApiProperties.getOverseaPriceUrl())
                            .queryParam("AUTH", "")
                            .queryParam("EXCD", stock.getMarketName().getExchangeCode())
                            .queryParam("SYMB", StockCodeUtils.toKisCode(stock.getStockCode()))
                            .build())
                    .header("Authorization", token)
                    .header("appKey", kisApiProperties.getAppkey())
                    .header("appSecret", kisApiProperties.getAppsecret())
                    .header("tr_id", "HHDFS76200200"), KisOverseasPriceApiResponse.class);
        } catch (Exception e) {
            log.error("Failed to fetch overseas stock price for {}: {}", stockCode, e.getMessage());
            throw new BusinessException(KisApiErrorCode.STOCK_PRICE_FETCH_FAILED);
        }

        KisOverseasPriceApiResponse.KisOverseasPriceOutput output = response.getOutput();

        String priceChange = output.getPriceChange();
        String changeRate = output.getChangeRate();

        if (priceChange == null || changeRate == null) {
            try {
                if (output.getPrice() != null && output.getPrevClose() != null) {
                    double current = Double.parseDouble(output.getPrice());
                    double base = Double.parseDouble(output.getPrevClose());
                    double change = current - base;
                    double rate = (change / base) * 100.0;

                    if (priceChange == null) {
                        priceChange = String.format("%.4f", change);
                    }
                    if (changeRate == null) {
                        changeRate = String.format("%.2f", rate);
                    }
                }
            } catch (NumberFormatException e) {
                log.warn("Failed to calculate price change for {}: {}", stockCode, e.getMessage());
            }
        }

        return new StockPriceDto(
                stock.getStockCode(),
                stock.getStockName(),
                stock.getMarketName(),
                output.getPrice(),
                priceChange,
                changeRate,
                output.getVolume(),
                output.getPrice(),
                output.getPrice(),
                output.getExchangeRate(),
                "0",
                output.getMarketCap());
    }

    public List<KisOverseasStockChartDto> getOverseasStockChart(String stockCode, String startDate, String endDate,
            String periodType) {
        Stock stock = overseasStockRepository.findByStockCode(stockCode)
                .filter(s -> s.getStockType() == Stock.StockType.US_STOCK || s.getStockType() == Stock.StockType.US_ETF)
                .orElseGet(() -> {
                    try {
                        Long stockId = Long.parseLong(stockCode);
                        return overseasStockRepository.findById(stockId).orElse(null);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                });

        if (stock == null) {
            throw new BusinessException(KisApiErrorCode.STOCK_NOT_FOUND);
        }

        if (!"D".equalsIgnoreCase(periodType)) {
            return fetchAndMapFromApi(stock, startDate, endDate, periodType);
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        LocalDate start = LocalDate.parse(startDate, formatter);
        LocalDate end = LocalDate.parse(endDate, formatter);

        // 1. Sync Recent Data (Try to fetch today's/recent data to ensure DB is up to
        // date)
        // We sync if the request endDate is near today (or just always sync recent for
        // consistency)
        LocalDate today = LocalDate.now();
        if (!end.isBefore(today.minusDays(5))) { // If endDate is within last 5 days
            syncRecentData(stock);
        }

        // 2. Fetch from DB
        List<OverseasStockDailyData> dbData = overseasStockDailyDataRepository.findByStockAndDateBetween(stock, start,
                end);

        // 3. Map to DTO
        java.util.Map<String, Double> exchangeRateMap = kisMacroService.getExchangeRateMap(startDate, endDate);
        Double latestRate = kisMacroService.getLatestExchangeRate();
        Double finalLatestRate = latestRate != null ? latestRate : 1300.0;

        return dbData.stream()
                .map(entity -> {
                    String dateStr = entity.getDate().format(formatter);
                    Double rate = exchangeRateMap.getOrDefault(dateStr, finalLatestRate);

                    return KisOverseasStockChartDto.builder()
                            .date(dateStr)
                            .openPrice(entity.getOpeningPrice().toString())
                            .highPrice(entity.getHighPrice().toString())
                            .lowPrice(entity.getLowPrice().toString())
                            .closePrice(entity.getClosingPrice().toString())
                            .diff(entity.getPriceChange() != null ? entity.getPriceChange().toString() : "0")
                            .rate(entity.getChangeRate() != null ? String.valueOf(entity.getChangeRate()) : "0")
                            .openPriceKrw(calculateKrw(entity.getOpeningPrice().toString(), rate))
                            .highPriceKrw(calculateKrw(entity.getHighPrice().toString(), rate))
                            .lowPriceKrw(calculateKrw(entity.getLowPrice().toString(), rate))
                            .closePriceKrw(calculateKrw(entity.getClosingPrice().toString(), rate))
                            .exchangeRate(String.valueOf(rate))
                            .build();
                })
                .collect(Collectors.toList());
    }

    private void syncRecentData(Stock stock) {
        try {
            // Fetch using Today's date to get the most recent data
            String todayStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            KisOverseasChartApiResponse response = fetchChartFromApi(stock, "0", todayStr);

            if (response != null && response.getOutput2() != null) {
                saveChartDataToDb(stock, response.getOutput2());
            }
        } catch (Exception e) {
            log.warn("Failed to sync recent data for {}: {}", stock.getStockCode(), e.getMessage());
            // Proceed without throwing, relying on existing DB data
        }
    }

    @Transactional
    public void fetchAndSaveHistoricalOverseasStockData(String stockIdOrCode, String startDateStr) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        LocalDate targetStartDate = LocalDate.parse(startDateStr, formatter);
        LocalDate currentDate = LocalDate.now();

        Stock stock = overseasStockRepository.findByStockCode(stockIdOrCode)
                .filter(s -> s.getStockType() == Stock.StockType.US_STOCK || s.getStockType() == Stock.StockType.US_ETF)
                .orElseGet(() -> {
                    try {
                        Long stockId = Long.parseLong(stockIdOrCode);
                        return overseasStockRepository.findById(stockId).orElse(null);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                });

        if (stock == null) {
            log.warn("Stock not found for code/ID: {}. Skipping historical data fetch.", stockIdOrCode);
            return;
        }

        log.info("Starting historical overseas stock data fetch for {} from {} to {}", stock.getStockCode(),
                currentDate,
                targetStartDate);

        int consecutiveFailures = 0;
        final int MAX_CONSECUTIVE_FAILURES = 5;

        while (currentDate.isAfter(targetStartDate)) {
            String currentDateStr = currentDate.format(formatter);
            // API call gets data ENDING at currentDateStr.
            // Overseas API returns up to 100 records (adjustable? usually 100).

            try {
                KisOverseasChartApiResponse response = fetchChartFromApi(stock, "0", currentDateStr);

                if (response == null || response.getOutput2() == null || response.getOutput2().isEmpty()) {
                    log.warn("No data returned for {} at {}. Stopping or retrying.", stock.getStockCode(),
                            currentDateStr);
                    consecutiveFailures++;
                    if (consecutiveFailures > MAX_CONSECUTIVE_FAILURES)
                        break;
                    currentDate = currentDate.minusDays(1);
                    continue;
                }

                consecutiveFailures = 0;
                saveChartDataToDb(stock, response.getOutput2());

                // Find the oldest date in this batch
                String oldestDateStr = response.getOutput2().get(response.getOutput2().size() - 1).getDate();
                LocalDate oldestDate = LocalDate.parse(oldestDateStr, formatter);

                if (!oldestDate.isBefore(currentDate)) {
                    // Logic prevent infinite loop if API returns same data
                    currentDate = currentDate.minusDays(100);
                } else {
                    currentDate = oldestDate.minusDays(1);
                }

                Thread.sleep(100); // Rate limit protection

            } catch (Exception e) {
                log.error("Error fetching historical data for {}: {}", stock.getStockCode(), e.getMessage());
                currentDate = currentDate.minusDays(1);
            }
        }
        log.info("Finished historical overseas stock data fetch for {}", stock.getStockCode());
    }

    public void fetchAllOverseasStocksHistoricalData(String startDateStr) {
        log.info("Starting batch historical overseas stock data fetch from {}", startDateStr);
        List<Stock> allStocks = overseasStockRepository.findAll().stream()
                .filter(s -> s.getStockType() == Stock.StockType.US_STOCK || s.getStockType() == Stock.StockType.US_ETF)
                .collect(Collectors.toList());

        for (Stock stock : allStocks) {
            try {
                log.info("Updating data for stock: {} ({})", stock.getStockName(), stock.getStockCode());
                fetchAndSaveHistoricalOverseasStockData(stock.getStockCode(), startDateStr);
                Thread.sleep(500); // Rate limit protection
            } catch (Exception e) {
                log.error("Failed to update stock: {} ({}) - {}", stock.getStockName(), stock.getStockCode(),
                        e.getMessage());
            }
        }
        log.info("Completed batch historical overseas stock data fetch.");
    }

    public void fetchAllEtfHistoricalData(String startDateStr) {
        log.info("Starting batch historical US ETF data fetch from {}", startDateStr);
        List<Stock> etfStocks = overseasStockRepository.findAll().stream()
                .filter(s -> s.getStockType() == Stock.StockType.US_ETF)
                .collect(Collectors.toList());

        for (Stock stock : etfStocks) {
            try {
                log.info("Updating data for ETF: {} ({})", stock.getStockName(), stock.getStockCode());
                fetchAndSaveHistoricalOverseasStockData(stock.getStockCode(), startDateStr);
                Thread.sleep(500); // Rate limit protection
            } catch (Exception e) {
                log.error("Failed to update ETF: {} ({}) - {}", stock.getStockName(), stock.getStockCode(),
                        e.getMessage());
            }
        }
        log.info("Completed batch historical US ETF data fetch.");
    }

    public void fetchOverseasStocksHistoricalDataFromStockId(Long stockId, String startDateStr) {
        log.info("Starting partial batch historical overseas stock data fetch from stockId: {} and date: {}", stockId,
                startDateStr);
        List<Stock> stocks = overseasStockRepository
                .findAllByStockTypeInAndStockIdGreaterThanEqual(
                        List.of(Stock.StockType.US_STOCK, Stock.StockType.US_ETF), stockId);

        for (Stock stock : stocks) {
            try {
                log.info("Updating data for stock: {} ({}) - ID: {}", stock.getStockName(), stock.getStockCode(),
                        stock.getStockId());
                fetchAndSaveHistoricalOverseasStockData(stock.getStockCode(), startDateStr);
                Thread.sleep(500); // Rate limit protection
            } catch (Exception e) {
                log.error("Failed to update stock: {} ({}) - {}", stock.getStockName(), stock.getStockCode(),
                        e.getMessage());
            }
        }
        log.info("Completed partial batch historical overseas stock data fetch.");
    }

    private void saveChartDataToDb(Stock stock, List<KisOverseasChartApiResponse.KisOverseasChartOutput2> items) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        for (KisOverseasChartApiResponse.KisOverseasChartOutput2 item : items) {
            try {
                LocalDate date = LocalDate.parse(item.getDate(), formatter);

                // Upsert or Ignore? Repository save will update if ID exists, but we don't have
                // explicit ID here other than generated.
                // We should check existence.
                if (!overseasStockDailyDataRepository.existsByStockAndDate(stock, date)) {
                    BigDecimal close = parseBigDecimal(item.getClose());
                    BigDecimal open = parseBigDecimal(item.getOpen());
                    BigDecimal high = parseBigDecimal(item.getHigh());
                    BigDecimal low = parseBigDecimal(item.getLow());
                    BigDecimal diff = parseBigDecimal(item.getDiff());
                    Double rate = parseDouble(item.getRate());

                    OverseasStockDailyData entity = OverseasStockDailyData.builder()
                            .stock(stock)
                            .date(date)
                            .closingPrice(close)
                            .openingPrice(open)
                            .highPrice(high)
                            .lowPrice(low)
                            .priceChange(diff)
                            .changeRate(rate)
                            .build();

                    overseasStockDailyDataRepository.save(entity);
                }
            } catch (Exception e) {
                log.warn("Error processing item date {}: {}", item.getDate(), e.getMessage());
            }
        }
    }

    private KisOverseasChartApiResponse fetchChartFromApi(Stock stock, String gubn, String endDate) {
        try {
            return kisApiClient.fetch(token -> webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(overseasApiProperties.getOverseaChartPriceUrl())
                            .queryParam("AUTH", "")
                            .queryParam("EXCD", stock.getMarketName().getExchangeCode())
                            .queryParam("SYMB", StockCodeUtils.toKisCode(stock.getStockCode()))
                            .queryParam("GUBN", gubn)
                            .queryParam("BYMD", endDate)
                            .queryParam("MODP", "1")
                            .build())
                    .header("Authorization", token)
                    .header("appKey", kisApiProperties.getAppkey())
                    .header("appSecret", kisApiProperties.getAppsecret())
                    .header("tr_id", "HHDFS76240000"), KisOverseasChartApiResponse.class);
        } catch (Exception e) {
            log.error("Failed API call for {}: {}", stock.getStockCode(), e.getMessage());
            throw new RuntimeException("API Call Failed");
        }
    }

    private List<KisOverseasStockChartDto> fetchAndMapFromApi(Stock stock, String startDate, String endDate,
            String periodType) {
        String gubn = "0";
        if ("W".equalsIgnoreCase(periodType)) {
            gubn = "1";
        } else if ("M".equalsIgnoreCase(periodType)) {
            gubn = "2";
        }

        KisOverseasChartApiResponse response;
        try {
            response = fetchChartFromApi(stock, gubn, endDate);
        } catch (Exception e) {
            log.error("Failed to fetch overseas stock chart for {}: {}", stock.getStockCode(), e.getMessage());
            throw new BusinessException(KisApiErrorCode.STOCK_PRICE_FETCH_FAILED);
        }

        if (response == null || response.getOutput2() == null) {
            return List.of();
        }

        java.util.Map<String, Double> exchangeRateMap = kisMacroService.getExchangeRateMap(startDate, endDate);
        Double latestRate = kisMacroService.getLatestExchangeRate();
        Double finalLatestRate = latestRate != null ? latestRate : 1300.0;

        return response.getOutput2().stream()
                .filter(item -> item.getDate().compareTo(startDate) >= 0)
                .map(item -> {
                    Double rate = exchangeRateMap.getOrDefault(item.getDate(), finalLatestRate);
                    // Calculate KRW
                    String openKrw = calculateKrw(item.getOpen(), rate);
                    String highKrw = calculateKrw(item.getHigh(), rate);
                    String lowKrw = calculateKrw(item.getLow(), rate);
                    String closeKrw = calculateKrw(item.getClose(), rate);

                    return KisOverseasStockChartDto.builder()
                            .date(item.getDate())
                            .openPrice(item.getOpen())
                            .highPrice(item.getHigh())
                            .lowPrice(item.getLow())
                            .closePrice(item.getClose())
                            .diff(item.getDiff())
                            .rate(item.getRate())
                            .openPriceKrw(openKrw)
                            .highPriceKrw(highKrw)
                            .lowPriceKrw(lowKrw)
                            .closePriceKrw(closeKrw)
                            .exchangeRate(String.valueOf(rate))
                            .build();
                })
                .collect(Collectors.toList());
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null)
            return BigDecimal.ZERO;
        try {
            return new BigDecimal(value);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private Double parseDouble(String value) {
        if (value == null)
            return 0.0;
        try {
            return Double.parseDouble(value);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private String calculateKrw(String usdPrice, Double rate) {
        if (usdPrice == null || rate == null)
            return "0";
        try {
            double usd = Double.parseDouble(usdPrice);
            return String.format("%.0f", usd * rate);
        } catch (NumberFormatException e) {
            return "0";
        }
    }
}
