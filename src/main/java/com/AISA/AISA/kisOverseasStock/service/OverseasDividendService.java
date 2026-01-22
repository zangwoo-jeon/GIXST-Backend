package com.AISA.AISA.kisOverseasStock.service;

import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.kisOverseasStock.dto.GeminiDividendDto;
import com.AISA.AISA.kisOverseasStock.entity.OverseasStockDailyData;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockDailyDataRepository;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockRepository;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockDividend;
import com.AISA.AISA.kisStock.exception.KisApiErrorCode;
import com.AISA.AISA.kisStock.repository.StockDividendRepository;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class OverseasDividendService {

    private final GeminiDividendService geminiDividendService;
    private final StockDividendRepository stockDividendRepository;
    private final KisOverseasStockRepository overseasStockRepository;
    private final KisOverseasStockDailyDataRepository dailyDataRepository;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter RECORD_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter PAYMENT_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    @Transactional
    public void refreshDividendInfo(String stockCode) {
        Stock stock = overseasStockRepository.findByStockCodeAndStockType(stockCode, Stock.StockType.US_STOCK)
                .orElseThrow(() -> new BusinessException(KisApiErrorCode.STOCK_NOT_FOUND));
        processBatch(List.of(stock));
    }

    @Transactional
    public void refreshAllOverseasDividends() {
        List<Stock> usStocks = overseasStockRepository.findAllByStockType(Stock.StockType.US_STOCK);
        log.info("Starting batch refresh of dividends for {} US stocks", usStocks.size());

        int batchSize = 20;
        for (int i = 0; i < usStocks.size(); i += batchSize) {
            int end = Math.min(i + batchSize, usStocks.size());
            List<Stock> batch = usStocks.subList(i, end);
            log.info("Processing batch {}/{} ({} stocks)", (i / batchSize) + 1,
                    (usStocks.size() + batchSize - 1) / batchSize, batch.size());
            processBatch(batch);
            try {
                Thread.sleep(1000); // Respect API limits
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Completed batch refresh of overseas dividends");
    }

    private void processBatch(List<Stock> stocks) {
        if (stocks.isEmpty())
            return;

        String prompt = createBatchDividendPrompt(stocks);
        String response = geminiDividendService.fetchBatchDividendData(prompt);

        if (response == null) {
            log.error("Failed to get dividend data from Gemini for batch: {}",
                    stocks.stream().map(Stock::getStockCode).toList());
            return;
        }

        try {
            String cleanedResponse = response.replaceAll("```json", "").replaceAll("```", "").trim();
            Map<String, List<GeminiDividendDto>> batchData = objectMapper.readValue(cleanedResponse,
                    new TypeReference<Map<String, List<GeminiDividendDto>>>() {
                    });

            List<StockDividend> entitiesToSave = new ArrayList<>();

            for (Stock stock : stocks) {
                List<GeminiDividendDto> dividendList = batchData.get(stock.getStockCode());
                if (dividendList == null || dividendList.isEmpty())
                    continue;

                for (GeminiDividendDto dto : dividendList) {
                    if (stockDividendRepository.existsByStock_StockCodeAndRecordDate(stock.getStockCode(),
                            dto.getRecordDate())) {
                        continue;
                    }

                    // Calculate Yield using historical price
                    LocalDate recordDate = LocalDate.parse(dto.getRecordDate(), RECORD_DATE_FORMATTER);
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
                            .recordDate(dto.getRecordDate())
                            .paymentDate(dto.getPaymentDate())
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

        } catch (Exception e) {
            log.error("Error processing batch: {}", e.getMessage());
        }
    }

    private String createBatchDividendPrompt(List<Stock> stocks) {
        StringBuilder sb = new StringBuilder();
        sb.append("Please provide the last 5 dividend records for the following US stocks in JSON format.\n");
        sb.append(
                "The response must be a SINGLE JSON OBJECT where keys are the Tickers and values are ARRAYS of dividend objects.\n");
        sb.append("Each dividend object should contain:\n");
        sb.append("- 'recordDate': The ex-dividend date in 'yyyyMMdd' format (e.g., 20240115).\n");
        sb.append("- 'paymentDate': The dividend payment date in 'yyyy/MM/dd' format (e.g., 2024/02/01).\n");
        sb.append("- 'dividendAmount': The dividend amount per share in USD as a string (e.g., '0.24').\n");
        sb.append("Stock list:\n");
        for (Stock stock : stocks) {
            sb.append(String.format("- %s (%s)\n", stock.getStockName(), stock.getStockCode()));
        }
        sb.append("\nExample format: {\"AAPL\": [{\"recordDate\": \"20240209\", ...}], \"MSFT\": [...]} \n");
        sb.append("ONLY return the JSON object, no other text.");
        return sb.toString();
    }
}
