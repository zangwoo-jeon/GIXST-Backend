package com.AISA.AISA.kisStock.scheduler;

import com.AISA.AISA.kisStock.kisService.KisStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
@Slf4j
public class StockScheduler {

    private final KisStockService kisStockService;

    // Run at 2 AM every day
    @Scheduled(cron = "0 0 2 * * *")
    public void updateAllStockData() {
        log.info("Starting scheduled stock data update...");

        // Fetch data for the last 3 years (approx 1000 days)
        LocalDate targetStartDate = LocalDate.now().minusYears(3);
        String targetStartDateStr = targetStartDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        kisStockService.fetchAllStocksHistoricalData(targetStartDateStr);

        log.info("Completed scheduled stock data update.");
    }
}
