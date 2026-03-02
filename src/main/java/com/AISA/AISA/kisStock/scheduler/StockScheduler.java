package com.AISA.AISA.kisStock.scheduler;

import com.AISA.AISA.analysis.scheduler.MarketValuationScheduler;
import com.AISA.AISA.kisOverseasStock.service.KisOverseasStockInformationService;
import com.AISA.AISA.kisStock.kisService.KisRankService;
import com.AISA.AISA.kisStock.kisService.KisInvestorService;
import com.AISA.AISA.kisStock.kisService.KisStockService;
import com.AISA.AISA.kisStock.kisService.KisIndexService;
import com.AISA.AISA.kisStock.kisService.KisMacroService;
import com.AISA.AISA.kisStock.enums.ExchangeRateCode;
import com.AISA.AISA.kisStock.enums.BondYield;
import com.AISA.AISA.portfolio.macro.service.EcosService;
import com.AISA.AISA.kisStock.kisService.DividendService;
import com.AISA.AISA.kisStock.kisService.KisInformationService;
import com.AISA.AISA.kisStock.kisService.Auth.KisAuthService;
import com.AISA.AISA.fred.service.FredIndexService;
import com.AISA.AISA.fred.enums.FredIndex;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;

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
    private final KisAuthService kisAuthService;
    private final DataSource dataSource;
    private final KisInvestorService kisInvestorService;
    private final KisOverseasStockInformationService kisOverseasStockInformationService;
    private final MarketValuationScheduler marketValuationScheduler;
    private final KisRankService kisRankService;
    private final FredIndexService fredIndexService;

    // Run at 10 AM every day Mon-Sat (After US market close: ET 4PM = KST next day 6AM)
    @Scheduled(cron = "0 0 10 * * MON-SAT")
    public void updateFredIndexData() {
        log.info("Starting scheduled FRED index data update...");

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(7);
        String endDateStr = endDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String startDateStr = startDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        for (FredIndex index : FredIndex.values()) {
            try {
                fredIndexService.fetchAndSave(index, startDateStr, endDateStr);
                Thread.sleep(500);
            } catch (Exception e) {
                log.error("Failed to update FRED index data for {}: {}", index, e.getMessage());
            }
        }

        log.info("Completed scheduled FRED index data update.");
    }

    // Run at 2 AM every day
    @Scheduled(cron = "0 0 2 * * *")
    public void updateAllStockData() {
        log.info("Starting scheduled stock data update...");

        // Fetch data for the last 1 week to ensure recent data is captured
        LocalDate targetStartDate = LocalDate.now().minusWeeks(1);
        String targetStartDateStr = targetStartDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        kisStockService.fetchAllStocksHistoricalData(targetStartDateStr, "J");

        log.info("Completed scheduled stock data update.");
    }

    // Run at 3 AM every day (Stock List Crawling)
    @Scheduled(cron = "0 0 3 * * *")
    public void crawlAndInsertStocks() {
        log.info("Starting scheduled stock list crawling...");
        Path tempDir = null;
        try {
            // 1. Setup Temp Directory
            tempDir = Files.createTempDirectory("stock_crawler");
            File scriptFile = tempDir.resolve("crawl_stocks.py").toFile();

            // 2. Copy Python Script from Classpath
            try (InputStream is = new ClassPathResource("scripts/crawl_stocks.py").getInputStream()) {
                Files.copy(is, scriptFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            // 3. Run Python Process
            ProcessBuilder pb = new ProcessBuilder("python", scriptFile.getAbsolutePath()); // Expects 'python' in PATH
            pb.directory(tempDir.toFile()); // Run in temp dir so stocks.sql is created there
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Capture logs
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[Python] " + line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.error("Python script failed with exit code: " + exitCode);
                return;
            }

            // 4. Validate Generated SQL File
            File sqlFile = tempDir.resolve("stocks.sql").toFile();
            if (!sqlFile.exists()) {
                log.error("stocks.sql was not generated by the Python script.");
                return;
            }

            // 5. Execute SQL Script
            try (Connection conn = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(conn, new FileSystemResource(sqlFile));
                log.info("Successfully executed stocks.sql script.");
            }

        } catch (Exception e) {
            log.error("Failed to run python crawling task", e);
        } finally {
            // Cleanup
            if (tempDir != null) {
                try {
                    // Simple cleanup - deletes the dir if empty or files within
                    // java.io.File delete() only works for empty dirs.
                    // Recursively delete or just let OS handle temp?
                    // Best effort cleanup
                    Files.walk(tempDir)
                            .sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                } catch (Exception ignored) {
                }
            }
        }
        log.info("Completed scheduled stock list crawling.");
    }


    // Run at 4 PM every weekday (After Korean market close)
    @Scheduled(cron = "0 0 16 * * MON-FRI")
    public void updateExchangeRateData() {
        log.info("Starting scheduled exchange rate data update...");

        // Fetch data for the last 7 days
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(7);
        String endDateStr = endDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String startDateStr = startDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        // Update all exchange rates via ECOS (USD, JPY, EUR, HKD, CNY)
        for (ExchangeRateCode code : ExchangeRateCode.values()) {
            try {
                kisMacroService.fetchAndSaveExchangeRate(code.getSymbol(), startDateStr, endDateStr);
                Thread.sleep(500); // Rate limit
            } catch (Exception e) {
                log.error("Failed to update exchange rate for {}: {}", code, e.getMessage());
            }
        }

        log.info("Completed scheduled exchange rate data update.");
    }

    // Run at 4:10 PM every day (After Korean market close and exchange rate update)
    @Scheduled(cron = "0 10 16 * * MON-FRI")
    public void updateDomesticIndexData() {
        log.info("Starting scheduled domestic index data update...");
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        String[] domesticIndices = { "KOSPI", "KOSDAQ", "VKOSPI" };
        for (String index : domesticIndices) {
            try {
                kisIndexService.saveIndexDailyData(index, today, "D");
                Thread.sleep(500); // Rate limit safety
            } catch (Exception e) {
                log.error("Failed to update domestic index {}: {}", index, e.getMessage());
            }
        }
        log.info("Completed scheduled domestic index data update.");
    }

    // Run at 4:05 PM every weekday (After Exchange Rate update)
    @Scheduled(cron = "0 5 16 * * MON-FRI")
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

    // Run at 5 AM every Friday (For ECOS Bond Yield)
    @Scheduled(cron = "0 0 5 * * FRI")
    public void updateWeeklyEcosData() {
        log.info("Starting scheduled weekly ECOS data (Bond Yield) update...");
        try {
            kisMacroService.fetchAndSaveEcosBondYield();
        } catch (Exception e) {
            log.error("Failed to update weekly ECOS data: {}", e.getMessage());
        }
        log.info("Completed scheduled weekly ECOS data update.");
    }

    // Run at 08:40 every day (Refresh token before market open)
    @Scheduled(cron = "0 40 8 * * *")
    public void refreshKisAccessToken() {
        log.info("Starting scheduled KIS Access Token refresh...");
        try {
            kisAuthService.refreshAccessToken();
        } catch (Exception e) {
            log.error("Failed to refresh KIS Access Token: {}", e.getMessage());
        }
        log.info("Completed scheduled KIS Access Token refresh.");
    }

    // Run at 5 AM every Saturday
    @Scheduled(cron = "0 0 5 * * SAT")
    public void refreshDividendDataAndRank() {
        log.info("Starting scheduled dividend data and rank refresh...");
        try {
            // 1. Fetch latest dividend data (Last 1 Month)
            String endDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String startDate = LocalDate.now().minusMonths(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            dividendService.refreshAllDividends(startDate, endDate);

            // 2. Calculate Rank based on DB data
            dividendService.refreshDividendRank();
        } catch (Exception e) {
            log.error("Failed to refresh dividend data/rank: {}", e.getMessage());
        }
        log.info("Completed scheduled dividend data and rank refresh.");
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

    // Run every 1 minute from 9 AM to 3:59 PM (Market Hours) on Weekdays
    @Scheduled(cron = "0 * 9-15 * * MON-FRI")
    public void warmupMarketCapPrices() {
        log.info("Starting scheduled Top 100 Market Cap price warmup...");
        try {
            kisStockService.refreshTopMarketCapPrices();
        } catch (Exception e) {
            log.error("Failed to warmup market cap prices: {}", e.getMessage());
        }
    }

    // Run at 5 PM every weekday (Investor Trend Data - KRX 마감 후, marketCode=J)
    @Scheduled(cron = "0 0 17 * * MON-FRI")
    public void updateInvestorTrendDataJ() {
        log.info("Starting scheduled investor trend data update (marketCode=J)...");
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        try {
            kisStockService.updateAllInvestorTrends(today, today, "J", true);
        } catch (Exception e) {
            log.error("Failed to update investor trend data (J): {}", e.getMessage());
        }
        log.info("Completed scheduled investor trend data update (J).");
        kisRankService.evictInvestorRankingCache();
    }

    // Run at 9 PM every weekday (Investor Trend Data - NXT 마감 후, marketCode=UN으로 덮어쓰기 -> Market Valuation Full Refresh)
    @Scheduled(cron = "0 0 21 * * MON-FRI")
    public void updateInvestorTrendDataUN() {
        log.info("Starting scheduled investor trend data update (marketCode=UN)...");
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        try {
            kisStockService.updateAllInvestorTrends(today, today, "UN", true);
        } catch (Exception e) {
            log.error("Failed to update investor trend data (UN): {}", e.getMessage());
        }
        log.info("Completed scheduled investor trend data update (UN).");
        kisRankService.evictInvestorRankingCache();

        // NXT 포함 최종 investor trend 저장 완료 후 MarketValuation 전체 갱신 (breadth 포함)
        log.info("Triggering full market valuation refresh after investor trend update (UN)...");
        marketValuationScheduler.refreshDomesticValuationFull();
    }

    // Run at 5:30 PM every weekday (Market Investor Trend Data)
    @Scheduled(cron = "0 30 17 * * MON-FRI")
    public void syncDailyMarketInvestorTrend() {
        log.info("Starting scheduled daily market investor trend data sync...");
        try {
            LocalDate today = LocalDate.now();
            // Sync KOSPI
            kisInvestorService.fetchHistoricalMarketData("0001", today, today);
            Thread.sleep(1000);
            // Sync KOSDAQ
            kisInvestorService.fetchHistoricalMarketData("1001", today, today);
        } catch (Exception e) {
            log.error("Failed to sync daily market investor trend data: {}", e.getMessage());
        }
        log.info("Completed scheduled daily market investor trend data sync.");
    }

    // Run every 5 minutes during US market hours (Hybrid Ranking Refresh)
    // Covers approx. 21:00 - 08:00 KST (inclusive of pre/post market)
    @Scheduled(cron = "0 0/5 0-7,21-23 * * MON-SAT")
    public void warmupOverseasTopMarketCap() {
        log.info("Starting scheduled high-frequency overseas top 100 market cap update...");
        try {
            kisOverseasStockInformationService.updateTopMarketCapPrices(100);
        } catch (Exception e) {
            log.error("Failed to update top overseas market cap data: {}", e.getMessage());
        }
        log.info("Completed scheduled high-frequency overseas top 100 market cap update.");
    }

    // Run at 7 AM every day (Overseas Market Cap Update - Full Sync)
    @Scheduled(cron = "0 0 7 * * *")
    public void syncOverseasMarketCap() {
        log.info("Starting scheduled full overseas market cap data update...");
        try {
            kisOverseasStockInformationService.updateAllMarketCap();
        } catch (Exception e) {
            log.error("Failed to update all overseas market cap data: {}", e.getMessage());
        }
        log.info("Completed scheduled full overseas market cap data update.");
    }
}
