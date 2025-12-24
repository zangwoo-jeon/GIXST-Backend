package com.AISA.AISA.kisStock.scheduler;

import com.AISA.AISA.kisStock.enums.OverseasIndex;
import com.AISA.AISA.kisStock.kisService.KisStockService;
import com.AISA.AISA.kisStock.kisService.KisIndexService;
import com.AISA.AISA.kisStock.kisService.KisMacroService;
import com.AISA.AISA.kisStock.enums.ExchangeRateCode;
import com.AISA.AISA.kisStock.enums.BondYield;
import com.AISA.AISA.portfolio.macro.service.EcosService;
import com.AISA.AISA.kisStock.kisService.DividendService;
import com.AISA.AISA.kisStock.kisService.KisInformationService;
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
    private final KisIndexService kisIndexService;
    private final KisMacroService kisMacroService;
    private final EcosService ecosService;
    private final DividendService dividendService;
    private final KisInformationService kisInformationService;

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

    // Run at 10 AM every day (After US market close)
    @Scheduled(cron = "0 0 10 * * *")
    public void updateOverseasIndexData() {
        log.info("Starting scheduled overseas index data update...");

        // Fetch data for the last 7 days to ensure recent data is captured
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(7);
        String endDateStr = endDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String startDateStr = startDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        for (OverseasIndex index : OverseasIndex.values()) {
            try {
                kisIndexService.fetchAndSaveOverseasIndex(index, startDateStr, endDateStr);
                Thread.sleep(1000); // Reduce API load
            } catch (Exception e) {
                log.error("Failed to update overseas index: {}", index, e);
            }
        }
        log.info("Completed scheduled overseas index data update.");
    }

    // Run at 4 PM every day (After Korean market close)
    @Scheduled(cron = "0 0 16 * * *")
    public void updateExchangeRateData() {
        log.info("Starting scheduled exchange rate data update...");

        // Fetch data for the last 7 days
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(7);
        String endDateStr = endDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String startDateStr = startDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        // 1. Update Basic Rates (USD, JPY, HKD, EUR)
        // JPY, HKD, EUR will be fetched as defined in ExchangeRateCode
        for (ExchangeRateCode code : ExchangeRateCode.values()) {
            if (code == ExchangeRateCode.HKD_KRW || code == ExchangeRateCode.EUR_KRW) {
                continue; // Skip calculated rates for now
            }
            try {
                kisMacroService.fetchAndSaveExchangeRate(code.getSymbol(), startDateStr, endDateStr);
                Thread.sleep(500); // Rate limit
            } catch (Exception e) {
                log.error("Failed to update exchange rate for {}: {}", code, e.getMessage());
            }
        }

        // 2. Update Calculated Rates (HKD/KRW, EUR/KRW)
        try {
            kisMacroService.calcAndSaveHkdKrwRate(startDateStr, endDateStr);
            Thread.sleep(100);
            kisMacroService.calcAndSaveEurKrwRate(startDateStr, endDateStr);
        } catch (Exception e) {
            log.error("Failed to calculate cross rates: {}", e.getMessage());
        }

        log.info("Completed scheduled exchange rate data update.");
    }

    // Run at 4:05 PM every day (After Exchange Rate update)
    @Scheduled(cron = "0 5 16 * * *")
    public void updateBondYieldData() {
        log.info("Starting scheduled bond yield data update...");

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(7);
        String endDateStr = endDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String startDateStr = startDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        for (BondYield bond : BondYield.values()) {
            try {
                kisMacroService.fetchAndSaveBondYield(bond, startDateStr, endDateStr);
                Thread.sleep(500); // Rate limit
            } catch (Exception e) {
                log.error("Failed to update bond yield for {}: {}", bond, e.getMessage());
            }
        }
        log.info("Completed scheduled bond yield data update.");
    }

    // Run at 9 AM on the 1st of every month
    @Scheduled(cron = "0 0 9 1 * *")
    public void updateMonthlyMacroData() {
        log.info("Starting scheduled monthly macro data update...");

        // Fetch data for the last 2 months to ensure recent data is captured
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusMonths(2);
        String endDateStr = endDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String startDateStr = startDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        try {
            ecosService.saveM2Data(startDateStr, endDateStr);
            Thread.sleep(500);
            ecosService.saveBaseRate(startDateStr, endDateStr);
            Thread.sleep(500);
            ecosService.saveCPI(startDateStr, endDateStr);
        } catch (Exception e) {
            log.error("Failed to update monthly macro data: {}", e.getMessage());
        }

        log.info("Completed scheduled monthly macro data update.");
    }

    // Run at 5 AM every Saturday
    @Scheduled(cron = "0 0 5 * * SAT")
    public void refreshDividendRank() {
        log.info("Starting scheduled dividend rank refresh...");
        try {
            dividendService.refreshDividendRank();
        } catch (Exception e) {
            log.error("Failed to refresh dividend rank: {}", e.getMessage());
        }
        log.info("Completed scheduled dividend rank refresh.");
    }

    // Run at 4 AM every Sunday (Financial Statements & Ratios)
    @Scheduled(cron = "0 0 4 * * SUN")
    public void updateWeeklyFinancialData() {
        log.info("Starting scheduled weekly financial data update...");
        try {
            // 1. Balance Sheet (Yearly)
            kisInformationService.refreshAllBalanceSheets("0");
            Thread.sleep(1000);

            // 2. Income Statement (Yearly)
            kisInformationService.refreshAllIncomeStatements("0");
            Thread.sleep(1000);

            // 3. Financial Ratios (Yearly)
            kisInformationService.refreshAllFinancialRatios("0");
            Thread.sleep(1000);

            // 4. Update Financial Rank (Based on updated Income Statements)
            kisInformationService.refreshFinancialRank();

        } catch (Exception e) {
            log.error("Failed to update weekly financial data: {}", e.getMessage());
        }
        log.info("Completed scheduled weekly financial data update.");
    }
}
