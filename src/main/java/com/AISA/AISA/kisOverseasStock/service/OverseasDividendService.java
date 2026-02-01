package com.AISA.AISA.kisOverseasStock.service;

import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.kisOverseasStock.dto.GeminiDividendDto;
import com.AISA.AISA.kisOverseasStock.entity.OverseasStockDailyData;
import com.AISA.AISA.kisOverseasStock.exception.GeminiQuotaExhaustedException;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockDailyDataRepository;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockRepository;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockDividend;
import com.AISA.AISA.kisStock.exception.KisApiErrorCode;
import com.AISA.AISA.kisStock.repository.StockDividendRepository;
import com.AISA.AISA.kisStock.dto.Dividend.StockDividendInfoDto;
import com.AISA.AISA.global.util.StockCodeUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OverseasDividendService {

    private final GeminiDividendService geminiDividendService;
    private final StockDividendRepository stockDividendRepository;
    private final KisOverseasStockRepository overseasStockRepository;
    private final KisOverseasStockDailyDataRepository dailyDataRepository;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private OverseasDividendService self;

    private static final DateTimeFormatter RECORD_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    public void refreshDividendInfo(String stockCode) {
        Stock stock = overseasStockRepository.findByStockCodeAndStockType(stockCode, Stock.StockType.US_STOCK)
                .orElseThrow(() -> new BusinessException(KisApiErrorCode.STOCK_NOT_FOUND));
        self.processBatch(List.of(stock));
    }

    public List<StockDividendInfoDto> getDividendInfo(String stockCode, String startDate, String endDate) {
        List<StockDividend> savedDividends = stockDividendRepository
                .findByStock_StockCodeAndRecordDateBetweenOrderByRecordDateDesc(stockCode, startDate, endDate);

        return savedDividends.stream()
                .map(entity -> StockDividendInfoDto.builder()
                        .id(entity.getId())
                        .stockCode(entity.getStock().getStockCode())
                        .stockName(entity.getStock().getStockName())
                        .recordDate(entity.getRecordDate())
                        .paymentDate(entity.getPaymentDate())
                        .dividendAmount(entity.getDividendAmount())
                        .dividendRate(entity.getDividendRate())
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteDividendsInRange(Long startId, Long endId) {
        log.info("Deleting dividends in range ID: {} ~ {}", startId, endId);
        stockDividendRepository.deleteByIdBetween(startId, endId);
    }

    public void refreshAllOverseasDividends() {
        List<Stock> usStocks = overseasStockRepository.findAllByStockType(Stock.StockType.US_STOCK);
        performBulkRefresh(usStocks, usStocks.size());
    }

    public void refreshAllOverseasDividendsFrom(String stockCode) {
        List<Stock> usStocks = overseasStockRepository.findAllByStockType(Stock.StockType.US_STOCK);
        int startIndex = 0;
        for (int i = 0; i < usStocks.size(); i++) {
            if (usStocks.get(i).getStockCode().equalsIgnoreCase(stockCode)) {
                startIndex = i;
                break;
            }
        }

        List<Stock> remainingStocks = usStocks.subList(startIndex, usStocks.size());
        log.info("Resuming bulk dividend refresh from stock {}. {} stocks remaining.", stockCode,
                remainingStocks.size());
        performBulkRefresh(remainingStocks, usStocks.size());
    }

    private void performBulkRefresh(List<Stock> targetStocks, int totalOriginalCount) {
        log.info("Starting bulk refresh of dividends for {} stocks", targetStocks.size());

        java.util.Set<String> successfulTickers = new java.util.HashSet<>();
        int batchSize = 10;

        // 1. First Pass: Batch Processing
        for (int i = 0; i < targetStocks.size(); i += batchSize) {
            int end = Math.min(i + batchSize, targetStocks.size());
            List<Stock> batch = targetStocks.subList(i, end);
            log.info("Processing batch {}/{} ({} stocks)", (i / batchSize) + 1,
                    (targetStocks.size() + batchSize - 1) / batchSize, batch.size());
            try {
                java.util.Set<String> batchSuccess = self.processBatch(batch);
                successfulTickers.addAll(batchSuccess);
                Thread.sleep(1000); // Respect API limits
            } catch (GeminiQuotaExhaustedException e) {
                log.error("CRITICAL: All Gemini API keys are exhausted. Stopping global refresh. Message: {}",
                        e.getMessage());
                break; // Stop all remaining batches
            } catch (Exception e) {
                log.error("Failed to process batch {}: {}", (i / batchSize) + 1, e.getMessage());
            }
        }

        // 2. Second Pass: Individual Retry for missing tickers
        List<Stock> missedStocks = targetStocks.stream()
                .filter(s -> !successfulTickers.contains(s.getStockCode()))
                .toList();

        if (!missedStocks.isEmpty()) {
            log.info("Retrying {} missed stocks individually for higher accuracy...", missedStocks.size());
            for (Stock stock : missedStocks) {
                try {
                    self.processBatch(List.of(stock));
                    Thread.sleep(1500); // Slightly longer delay for individual retries
                } catch (GeminiQuotaExhaustedException e) {
                    break;
                } catch (Exception e) {
                    log.warn("Failed individual retry for {}: {}", stock.getStockCode(), e.getMessage());
                }
            }
        }

        log.info("Completed bulk refresh of overseas dividends. Total success in this run: {}/{}",
                successfulTickers.size(), targetStocks.size());
    }

    @Transactional
    public java.util.Set<String> processBatch(List<Stock> stocks) {
        java.util.Set<String> foundTickers = new java.util.HashSet<>();
        if (stocks.isEmpty())
            return foundTickers;

        String prompt = createBatchDividendPrompt(stocks);
        String response = geminiDividendService.fetchBatchDividendData(prompt);

        if (response == null) {
            log.error("Failed to get dividend data from Gemini for batch: {}",
                    stocks.stream().map(Stock::getStockCode).toList());
            return foundTickers;
        }

        try {
            String cleanedResponse = response.replaceAll("```json", "").replaceAll("```", "").trim();

            // Find the actual JSON object start and end to ignore any conversational text
            int start = cleanedResponse.indexOf("{");
            int end = cleanedResponse.lastIndexOf("}");

            if (start == -1 || end == -1 || start >= end) {
                log.warn("No valid JSON object found in Gemini response for batch.");
                return foundTickers;
            }

            cleanedResponse = cleanedResponse.substring(start, end + 1);

            Map<String, List<GeminiDividendDto>> batchData = objectMapper.readValue(cleanedResponse,
                    new TypeReference<Map<String, List<GeminiDividendDto>>>() {
                    });

            List<StockDividend> entitiesToSave = new ArrayList<>();

            for (Stock stock : stocks) {
                List<GeminiDividendDto> dividendList = batchData.get(stock.getStockCode());
                if (dividendList == null || dividendList.isEmpty())
                    continue;

                foundTickers.add(stock.getStockCode());
                for (GeminiDividendDto dto : dividendList) {
                    if (dto.getRecordDate() == null || dto.getDividendAmount() == null)
                        continue;

                    // Clean date strings
                    String cleanRecordDate = dto.getRecordDate().replaceAll("[^0-9]", ""); // YYYYMMDD
                    String rawPaymentDate = dto.getPaymentDate().replaceAll("[^0-9]", "");
                    String formattedPaymentDate = rawPaymentDate;
                    if (rawPaymentDate.length() == 8) {
                        formattedPaymentDate = rawPaymentDate.substring(0, 4) + "/" +
                                rawPaymentDate.substring(4, 6) + "/" +
                                rawPaymentDate.substring(6, 8); // YYYY/MM/DD
                    }

                    if (stockDividendRepository.existsByStock_StockCodeAndRecordDate(stock.getStockCode(),
                            cleanRecordDate)) {
                        continue;
                    }

                    // Calculate Yield using historical price
                    LocalDate recordDate = LocalDate.parse(cleanRecordDate, RECORD_DATE_FORMATTER);
                    Optional<OverseasStockDailyData> dailyData = dailyDataRepository.findByStockAndDate(stock,
                            recordDate);

                    BigDecimal closePrice = BigDecimal.ZERO;
                    if (dailyData.isPresent()) {
                        closePrice = dailyData.get().getClosingPrice();
                    } else {
                        List<OverseasStockDailyData> previousData = dailyDataRepository.findByStockAndDateBetween(stock,
                                recordDate.minusDays(7), recordDate);
                        if (!previousData.isEmpty()) {
                            closePrice = previousData.get(previousData.size() - 1).getClosingPrice();
                        }
                    }

                    Double yield = 0.0;
                    if (closePrice.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal amount = new BigDecimal(dto.getDividendAmount());
                        yield = amount.divide(closePrice, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100))
                                .doubleValue();
                    }

                    entitiesToSave.add(StockDividend.builder()
                            .stock(stock)
                            .recordDate(cleanRecordDate) // Ensure standardized format
                            .paymentDate(formattedPaymentDate) // Ensure standardized format
                            .dividendAmount(new BigDecimal(dto.getDividendAmount()))
                            .dividendRate(yield)
                            .stockPrice(closePrice)
                            .build());
                }
            }

            if (!entitiesToSave.isEmpty()) {
                stockDividendRepository.saveAll(entitiesToSave);
                log.info("Saved {} new dividend records for current batch", entitiesToSave.size());
            }

            return foundTickers;

        } catch (Exception e) {
            log.error("Error processing batch: {}", e.getMessage());
            throw new RuntimeException("Batch processing failed, rolling back current batch", e);
        }
    }

    @Transactional
    public void calculateMissingRates() {
        log.info("Starting calculation of missing dividend rates and prices for US stocks...");
        List<StockDividend> targetDividends = stockDividendRepository.findUSDividendsWithMissingPrice();
        log.info("Found {} dividend records with missing prices.", targetDividends.size());

        int count = 0;
        for (StockDividend div : targetDividends) {
            try {
                LocalDate recordDate = LocalDate.parse(div.getRecordDate(), RECORD_DATE_FORMATTER);
                Stock stock = div.getStock();

                // Find closing price at record date or most recent before it
                Optional<OverseasStockDailyData> dailyData = dailyDataRepository.findByStockAndDate(stock, recordDate);

                BigDecimal closePrice = BigDecimal.ZERO;
                if (dailyData.isPresent()) {
                    closePrice = dailyData.get().getClosingPrice();
                } else {
                    // Look back up to 10 days to find the last trading day price
                    List<OverseasStockDailyData> previousData = dailyDataRepository.findByStockAndDateBetween(stock,
                            recordDate.minusDays(10), recordDate);
                    if (!previousData.isEmpty()) {
                        closePrice = previousData.get(previousData.size() - 1).getClosingPrice();
                    }
                }

                if (closePrice.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal amount = div.getDividendAmount();
                    double yield = amount.divide(closePrice, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal(100)).doubleValue();

                    // Update entity - we need a setter or use reflection if not available.
                    // StockDividend entity has dividendRate and stockPrice fields.
                    // Based on previous view_file, they don't have public setters, only
                    // updateDates.
                    // However, we can use reflection or add a method.
                    // Let's check StockDividend.java again.
                    div.updatePriceAndRate(closePrice, yield);
                    count++;
                }
            } catch (Exception e) {
                log.warn("Failed to calculate rate for dividend id {}: {}", div.getId(), e.getMessage());
            }
        }
        log.info("Updated {} dividend records with prices and rates.", count);
    }

    /**
     * 기존 배당 데이터의 날짜 형식을 표준화합니다.
     * RecordDate: YYYYMMDD, PaymentDate: YYYY/MM/DD
     */
    @Transactional
    public void standardizeExistingDividends() {
        log.info("Starting standardization of existing dividend date formats...");
        List<StockDividend> allDividends = stockDividendRepository.findAll();
        int count = 0;

        for (StockDividend div : allDividends) {
            if (div.getStock().getStockType() != Stock.StockType.US_STOCK)
                continue;

            String cleanRecordDate = div.getRecordDate().replaceAll("[^0-9]", "");
            String cleanPaymentDate = div.getPaymentDate().replaceAll("[^0-9]", "");

            String formattedPaymentDate = cleanPaymentDate;
            if (cleanPaymentDate.length() == 8) {
                formattedPaymentDate = cleanPaymentDate.substring(0, 4) + "/" +
                        cleanPaymentDate.substring(4, 6) + "/" +
                        cleanPaymentDate.substring(6, 8);
            }

            // Update only if changed
            if (!div.getRecordDate().equals(cleanRecordDate) || !div.getPaymentDate().equals(formattedPaymentDate)) {
                div.updateDates(cleanRecordDate, formattedPaymentDate);
                log.info("Standardized dividend for {}: RecordDate({} -> {}), PaymentDate({} -> {})",
                        div.getStock().getStockCode(),
                        div.getRecordDate(), cleanRecordDate,
                        div.getPaymentDate(), formattedPaymentDate);
                count++;
            }
        }
        log.info("Finished standardization. Total {} records updated.", count);
    }

    private String createBatchDividendPrompt(List<Stock> stocks) {
        StringBuilder sb = new StringBuilder();
        String currentDate = LocalDate.now().toString();
        sb.append(String.format(
                "Today is %s. Please provide the MOST RECENT 5 dividend records (prioritizing 2024 and 2025) for the following US stocks in JSON format by searching reliable financial sources like Yahoo Finance, Nasdaq, or Seeking Alpha.\n",
                currentDate));
        sb.append(
                "The response must be a SINGLE JSON OBJECT where keys are the Tickers and values are ARRAYS of dividend objects.\n");
        sb.append("Each dividend object should contain:\n");
        sb.append("- 'recordDate': The ex-dividend date in 'yyyyMMdd' format (e.g., 20240115).\n");
        sb.append("- 'paymentDate': The dividend payment date in 'yyyy/MM/dd' format (e.g., 2024/02/01).\n");
        sb.append("- 'dividendAmount': The dividend amount per share in USD as a string (e.g., '0.24').\n");
        sb.append("Stock list:\n");
        for (Stock stock : stocks) {
            sb.append(
                    String.format("- %s (%s)\n", stock.getStockName(), stock.getStockCode()));
        }
        sb.append("\nExample format: {\"AAPL\": [{\"recordDate\": \"20240209\", ...}], \"MSFT\": [...]} \n");
        sb.append(
                "ONLY return the JSON object, NO other text. Mandatory: Use ONLY YYYYMMDD style for recordDate (NO dashes, NO slashes). And use YYYY/MM/DD style for paymentDate (WITH slashes). Ensure you include the latest 2024/2025 records if they have been announced.");
        return sb.toString();
    }
}
