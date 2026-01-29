package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.config.KisApiProperties;
import com.AISA.AISA.kisStock.dto.FinancialRank.BalanceSheetDto;
import com.AISA.AISA.kisStock.dto.FinancialRank.FinancialStatementDto;
import com.AISA.AISA.kisStock.dto.FinancialRank.FinancialRankDto;
import com.AISA.AISA.kisStock.dto.FinancialRank.InvestmentMetricDto;
import com.AISA.AISA.kisStock.dto.FinancialRank.KisBalanceSheetApiResponse;
import com.AISA.AISA.kisStock.dto.FinancialRank.KisIncomeStatementApiResponse;
import com.AISA.AISA.kisStock.exception.KisApiErrorCode;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialRank;
import com.AISA.AISA.kisStock.repository.StockFinancialRankRepository;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialStatement;
import com.AISA.AISA.kisStock.repository.StockFinancialStatementRepository;
import com.AISA.AISA.kisStock.Entity.stock.StockBalanceSheet;
import com.AISA.AISA.kisStock.repository.StockBalanceSheetRepository;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialRatio;
import com.AISA.AISA.kisStock.repository.StockFinancialRatioRepository;
import com.AISA.AISA.kisStock.dto.FinancialRank.KisFinancialRatioApiResponse;
import com.AISA.AISA.kisStock.dto.FinancialRank.KisStockSearchInfoApiResponse;
import com.AISA.AISA.kisStock.dto.FinancialRank.FinancialRatioRankDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import com.AISA.AISA.kisStock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.Comparator;

@Slf4j
@Service
@RequiredArgsConstructor
public class KisInformationService {

    private final WebClient webClient;
    private final KisApiProperties kisApiProperties;
    private final StockRepository stockRepository;
    private final StockBalanceSheetRepository stockBalanceSheetRepository;
    private final StockFinancialRankRepository stockFinancialRankRepository;
    private final StockFinancialRatioRepository stockFinancialRatioRepository;
    private final KisApiClient kisApiClient;
    private final StockFinancialStatementRepository stockFinancialStatementRepository;

    public List<BalanceSheetDto> getBalanceSheet(String stockCode, String divCode) {
        validateDomesticStock(stockCode);
        // 1. Check DB (Read-Only)
        List<StockBalanceSheet> dbStatements = stockBalanceSheetRepository
                .findByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, divCode);

        return dbStatements.stream()
                .map(entity -> BalanceSheetDto.builder()
                        .stacYymm(entity.getStacYymm())
                        .totalAssets(entity.getTotalAssets() != null ? String.valueOf(entity.getTotalAssets()) : "0")
                        .totalLiabilities(
                                entity.getTotalLiabilities() != null ? String.valueOf(entity.getTotalLiabilities())
                                        : "0")
                        .totalCapital(entity.getTotalCapital() != null ? String.valueOf(entity.getTotalCapital()) : "0")
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional
    public List<BalanceSheetDto> refreshBalanceSheet(String stockCode, String divCode) {
        validateDomesticStock(stockCode);
        try {
            KisBalanceSheetApiResponse response = kisApiClient.fetch(token -> webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(kisApiProperties.getBalanceSheetUrl())
                            .queryParam("FID_DIV_CLS_CODE", divCode)
                            .queryParam("fid_cond_mrkt_div_code", "J")
                            .queryParam("fid_input_iscd", stockCode)
                            .build())
                    .header("authorization", token)
                    .header("appkey", kisApiProperties.getAppkey())
                    .header("appsecret", kisApiProperties.getAppsecret())
                    .header("tr_id", "FHKST66430100")
                    .header("custtype", "P"), KisBalanceSheetApiResponse.class);

            if (response == null || !"0".equals(response.getRtCd()) || response.getOutput() == null) {
                log.error("Balance Sheet API Error for {}. RtCd: {}, Msg: {}",
                        stockCode,
                        response != null ? response.getRtCd() : "null",
                        response != null ? response.getMsg1() : "null response");
                throw new BusinessException(KisApiErrorCode.STOCK_PRICE_FETCH_FAILED);
            }

            // 2. Save to DB (StockBalanceSheet)
            List<StockBalanceSheet> entitiesToSave = response.getOutput().stream()
                    .map(item -> StockBalanceSheet.builder()
                            .stockCode(stockCode)
                            .stacYymm(item.getStacYymm())
                            .divCode(divCode)
                            .totalAssets(new java.math.BigDecimal(parseLongSafe(item.getTotalAssets())))
                            .totalLiabilities(new java.math.BigDecimal(parseLongSafe(item.getTotalLiabilities())))
                            .totalCapital(new java.math.BigDecimal(parseLongSafe(item.getTotalCapital())))
                            .build())
                    .collect(Collectors.toList());

            if (entitiesToSave.isEmpty()) {
                throw new Exception("Empty KIS response");
            }

            try {
                // Ignore duplicates using simple save logic.
                stockBalanceSheetRepository.saveAll(entitiesToSave);
            } catch (Exception e) {
                log.warn("Duplicate entry or save error for balance sheet {}: {}", stockCode, e.getMessage());
            }

            return entitiesToSave.stream()
                    .map(entity -> BalanceSheetDto.builder()
                            .stacYymm(entity.getStacYymm())
                            .totalAssets(String.valueOf(entity.getTotalAssets()))
                            .totalLiabilities(String.valueOf(entity.getTotalLiabilities()))
                            .totalCapital(String.valueOf(entity.getTotalCapital()))
                            .build())
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Failed to fetch balance sheet from KIS for {}: {}", stockCode, e.getMessage());
            throw new BusinessException(KisApiErrorCode.STOCK_PRICE_FETCH_FAILED);
        }
    }

    public void refreshAllBalanceSheets(String divCode) {
        List<Stock> stocks = stockRepository.findAll();
        log.info("Starting Batch Balance Sheet Refresh for {} stocks (divCode={})", stocks.size(), divCode);

        for (Stock stock : stocks) {
            try {
                refreshBalanceSheet(stock.getStockCode(), divCode);
                Thread.sleep(100);
            } catch (Exception e) {
                log.error("Failed to refresh balance sheet for {}: {}", stock.getStockCode(), e.getMessage());
            }
        }
        log.info("Completed Batch Balance Sheet Refresh");
    }

    @CacheEvict(value = "financialRank", allEntries = true)
    public void refreshAllFinancialRatios(String divCode) {
        List<Stock> stocks = stockRepository.findAll();
        log.info("Starting Batch Financial Ratio Refresh for {} stocks (divCode={})", stocks.size(), divCode);

        for (Stock stock : stocks) {
            try {
                fetchAndSaveFinancialRatio(stock.getStockCode(), divCode);
                Thread.sleep(100);
            } catch (Exception e) {
                log.error("Failed to refresh financial ratio for {}: {}", stock.getStockCode(), e.getMessage());
            }
        }
        log.info("Completed Batch Financial Ratio Refresh");
    }

    @Cacheable(value = "financialRank", key = "#sort + '-' + #divCode + '-' + #direction", sync = true)
    public FinancialRatioRankDto getFinancialRatioRank(String sort, String divCode, String direction) {
        String finalDivCode = (divCode == null || divCode.isEmpty()) ? "0" : divCode;

        // 1. Fetch Windowed Ratios (Last 3 Years)
        String minDate = LocalDate.now().minusYears(3).format(DateTimeFormatter.ofPattern("yyyy"))
                + "01";
        List<StockFinancialRatio> allRatios = stockFinancialRatioRepository
                .findByDivCodeAndStacYymmGreaterThanEqual(finalDivCode, minDate);

        // 2. Group by StockCode & Find Latest for Each
        Map<String, List<StockFinancialRatio>> groupedByStock = allRatios.stream()
                .collect(Collectors.groupingBy(StockFinancialRatio::getStockCode));

        List<StockFinancialRatio> latestRatios = new ArrayList<>();
        for (List<StockFinancialRatio> ratios : groupedByStock.values()) {
            // Sort by Date Descending
            ratios.sort((o1, o2) -> o2.getStacYymm().compareTo(o1.getStacYymm()));

            // Find first record with valid PBR (or PER/ROE) > 0 to avoid empty future data
            // If all are empty, fall back to the absolute latest
            StockFinancialRatio validRatio = ratios.stream()
                    .filter(r -> r.getPbr() != null && r.getPbr().compareTo(BigDecimal.ZERO) > 0)
                    .findFirst()
                    .orElse(ratios.get(0)); // Fallback to latest

            latestRatios.add(validRatio);
        }

        // 3. Sort Logic
        Comparator<StockFinancialRatio> comparator;
        boolean defaultAsc = false;

        if ("eps".equalsIgnoreCase(sort)) {
            comparator = Comparator.comparing(StockFinancialRatio::getEps);
        } else if ("roe".equalsIgnoreCase(sort)) {
            comparator = Comparator.comparing(StockFinancialRatio::getRoe);
        } else if ("debt".equalsIgnoreCase(sort) || "debtratio".equalsIgnoreCase(sort)) {
            comparator = Comparator.comparing(StockFinancialRatio::getDebtRatio);
            defaultAsc = true;
        } else if ("per".equalsIgnoreCase(sort)) {
            comparator = Comparator.comparing(StockFinancialRatio::getPer);
            defaultAsc = true;
        } else if ("pbr".equalsIgnoreCase(sort)) {
            comparator = Comparator.comparing(StockFinancialRatio::getPbr);
            defaultAsc = true;
        } else if ("psr".equalsIgnoreCase(sort)) {
            comparator = Comparator.comparing(StockFinancialRatio::getPsr);
            defaultAsc = true;
        } else {
            comparator = Comparator.comparing(StockFinancialRatio::getRoe);
        }

        // Determine final sorting order
        boolean isAsc;
        if (direction != null && !direction.isEmpty()) {
            isAsc = "asc".equalsIgnoreCase(direction);
        } else {
            isAsc = defaultAsc;
        }

        // 4. Filter & Apply Sort
        List<StockFinancialRatio> filteredRanks = latestRatios.stream()
                .filter(r -> {
                    // Filter Out Suspended Stocks
                    if (Boolean.TRUE.equals(r.getIsSuspended())) {
                        return false;
                    }

                    // Filter out zeros/nulls for valuation ratios if sorting ASC (low PBR != 0 PBR)
                    if ("per".equalsIgnoreCase(sort) || "pbr".equalsIgnoreCase(sort) || "psr".equalsIgnoreCase(sort)) {
                        BigDecimal val = "per".equalsIgnoreCase(sort) ? r.getPer()
                                : "pbr".equalsIgnoreCase(sort) ? r.getPbr() : r.getPsr();
                        return val != null && val.compareTo(BigDecimal.ZERO) > 0;
                    }
                    return true;
                })
                .collect(Collectors.toList());

        if (isAsc) {
            filteredRanks.sort(comparator);
        } else {
            filteredRanks.sort(comparator.reversed());
        }

        // 5. Map to DTO (Top 20)
        Map<String, String> stockMap = stockRepository.findAll().stream()
                .collect(Collectors.toMap(Stock::getStockCode, Stock::getStockName));

        List<FinancialRatioRankDto.FinancialRatioEntry> entries = filteredRanks.stream()
                .limit(20)
                .map(ratio -> FinancialRatioRankDto.FinancialRatioEntry.builder()
                        // Use updated list index for rank
                        .rank(String.valueOf(filteredRanks.indexOf(ratio) + 1))
                        .stockCode(ratio.getStockCode())
                        .stockName(stockMap.getOrDefault(ratio.getStockCode(), "Unknown"))
                        .stacYymm(ratio.getStacYymm())
                        .roe(ratio.getRoe() != null ? ratio.getRoe().toString() : "0")
                        .eps(ratio.getEps() != null ? ratio.getEps().toString() : "0")
                        .debtRatio(ratio.getDebtRatio() != null ? ratio.getDebtRatio().toString() : "0")
                        .reserveRatio(ratio.getReserveRatio() != null ? ratio.getReserveRatio().toString() : "0")
                        .salesGrowth(ratio.getSalesGrowth() != null ? ratio.getSalesGrowth().toString() : "0")
                        .operatingProfitGrowth(
                                ratio.getOperatingProfitGrowth() != null ? ratio.getOperatingProfitGrowth().toString()
                                        : "0")
                        .netIncomeGrowth(
                                ratio.getNetIncomeGrowth() != null ? ratio.getNetIncomeGrowth().toString() : "0")
                        .per(ratio.getPer() != null ? ratio.getPer().toString() : "0")
                        .pbr(ratio.getPbr() != null ? ratio.getPbr().toString() : "0")
                        .psr(ratio.getPsr() != null ? ratio.getPsr().toString() : "0")
                        .build())
                .collect(Collectors.toList());

        // Re-assign strict ordinal ranks in case of gaps (though limits above handles
        // it effectively)
        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).setRank(String.valueOf(i + 1));
        }

        return FinancialRatioRankDto.builder().ranks(entries).build();
    }

    @Transactional
    public List<StockFinancialRatio> fetchAndSaveFinancialRatio(String stockCode, String divCode) {
        validateDomesticStock(stockCode);
        // 1. Fetch Current Price & Status FIRST (Needed for both API calculation and
        // fallback)
        StockPriceInfo priceInfo = fetchCurrentPrice(stockCode);
        BigDecimal currentPrice = priceInfo.price;

        try {
            // 2. Fetch Financial Ratio (EPS, BPS, SPS, etc.)
            KisFinancialRatioApiResponse response = kisApiClient.fetch(token -> webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(kisApiProperties.getFinancialRatioUrl())
                            .queryParam("FID_DIV_CLS_CODE", divCode)
                            .queryParam("fid_cond_mrkt_div_code", "J")
                            .queryParam("fid_input_iscd", stockCode)
                            .build())
                    .header("authorization", token)
                    .header("appkey", kisApiProperties.getAppkey())
                    .header("appsecret", kisApiProperties.getAppsecret())
                    .header("tr_id", "FHKST66430300")
                    .header("custtype", "P"), KisFinancialRatioApiResponse.class);

            if (response == null || !"0".equals(response.getRtCd()) || response.getOutput() == null) {
                log.warn("Financial Ratio API Error for {}. RtCd: {}, Msg: {}. Attempting local calculation.",
                        stockCode,
                        response != null ? response.getRtCd() : "null",
                        response != null ? response.getMsg1() : "null response");
                return calculateRatiosLocally(stockCode, divCode, priceInfo);
            }

            // 3. Check if API returned mostly zeros (common for REITs)
            boolean isAllZeros = response.getOutput().stream().allMatch(
                    item -> "0".equals(parseRateSafe(item.getEps())) && "0".equals(parseRateSafe(item.getRoeVal())));

            if (isAllZeros) {
                log.info("Financial Ratio API returned zeros for {}. Attempting local calculation.", stockCode);
                return calculateRatiosLocally(stockCode, divCode, priceInfo);
            }

            List<StockFinancialRatio> entitiesToSave = new ArrayList<>();

            for (KisFinancialRatioApiResponse.FinancialRatioOutput item : response.getOutput()) {
                // Check if exists to Update (Upsert)
                StockFinancialRatio existing = stockFinancialRatioRepository
                        .findByStockCodeAndStacYymmAndDivCode(stockCode, item.getStacYymm(), divCode)
                        .orElse(null);

                StockFinancialRatio.StockFinancialRatioBuilder builder = (existing != null)
                        ? existing.toBuilder()
                        : StockFinancialRatio.builder()
                                .stockCode(stockCode)
                                .stacYymm(item.getStacYymm())
                                .divCode(divCode)
                                .isSuspended(priceInfo.isSuspended); // Set Suspension Status

                // Set/Update Financials
                BigDecimal eps = new BigDecimal(parseRateSafe(item.getEps()));
                BigDecimal bps = new BigDecimal(parseRateSafe(item.getBps()));
                BigDecimal sps = new BigDecimal(parseRateSafe(item.getSps()));

                builder.roe(new BigDecimal(parseRateSafe(item.getRoeVal())))
                        .isSuspended(priceInfo.isSuspended) // Ensure update on existing
                        .eps(eps)
                        .bps(bps)
                        .debtRatio(new BigDecimal(parseRateSafe(item.getLbltRate())))
                        .reserveRatio(new BigDecimal(parseRateSafe(item.getRsrvRate())))
                        .salesGrowth(new BigDecimal(parseRateSafe(item.getGrs())))
                        .operatingProfitGrowth(new BigDecimal(parseRateSafe(item.getBsopPrfiInrt())))
                        .netIncomeGrowth(new BigDecimal(parseRateSafe(item.getNtinInrt())));

                // Set/Update Valuations (PER, PBR, PSR)
                if (currentPrice.compareTo(BigDecimal.ZERO) > 0) {
                    if (eps.compareTo(BigDecimal.ZERO) != 0) {
                        builder.per(currentPrice.divide(eps, 2, RoundingMode.HALF_UP));
                    }
                    if (bps.compareTo(BigDecimal.ZERO) != 0) {
                        builder.pbr(currentPrice.divide(bps, 2, RoundingMode.HALF_UP));
                    }
                    if (sps.compareTo(BigDecimal.ZERO) != 0) {
                        builder.psr(currentPrice.divide(sps, 2, RoundingMode.HALF_UP));
                    }
                }

                entitiesToSave.add(builder.build());
            }

            try {
                stockFinancialRatioRepository.saveAll(entitiesToSave);
            } catch (Exception e) {
                log.warn("Save error for financial ratio {}: {}", stockCode, e.getMessage());
            }

            return entitiesToSave;

        } catch (Exception e) {
            log.error("Failed to fetch financial ratio for {}: {}", stockCode, e.getMessage());
            // Attempt fallback on exception
            return calculateRatiosLocally(stockCode, divCode, priceInfo);
        }
    }

    private List<StockFinancialRatio> calculateRatiosLocally(String stockCode, String divCode,
            StockPriceInfo priceInfo) {
        if (priceInfo.listedShares <= 0) {
            log.warn("Cannot calculate ratios locally for {}: No listed shares entries found.", stockCode);
            return new ArrayList<>();
        }

        // Fetch Local Financial Data
        List<StockFinancialStatement> statements = stockFinancialStatementRepository
                .findByStockCodeAndDivCodeOrderByStacYymmAsc(stockCode, divCode);
        if (statements.isEmpty()) {
            return new ArrayList<>();
        }

        // Fetch Balance Sheets (for BPS, Debt Ratio, ROE)
        List<StockBalanceSheet> balanceSheets = stockBalanceSheetRepository
                .findByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, divCode);

        List<StockFinancialRatio> results = new ArrayList<>();
        BigDecimal shares = new BigDecimal(priceInfo.listedShares);

        for (StockFinancialStatement stmt : statements) {
            String yymm = stmt.getStacYymm();
            StockBalanceSheet sheet = balanceSheets.stream()
                    .filter(bs -> bs.getStacYymm().equals(yymm))
                    .findFirst().orElse(null);

            BigDecimal unitMultiplier = new BigDecimal("100000000"); // 1억 단위 보정

            // Basic Metrics from Income Statement
            BigDecimal netIncome = stmt.getNetIncome();
            BigDecimal sales = stmt.getSaleAccount();

            // KIS 재무데이터는 보통 '억 원' 단위이므로 보정 필요
            BigDecimal eps = netIncome.multiply(unitMultiplier).divide(shares, 0, RoundingMode.HALF_UP);
            BigDecimal sps = sales.multiply(unitMultiplier).divide(shares, 0, RoundingMode.HALF_UP);

            BigDecimal totalCapital = BigDecimal.ZERO;
            BigDecimal totalLiabilities = BigDecimal.ZERO;
            if (sheet != null) {
                totalCapital = sheet.getTotalCapital() != null ? sheet.getTotalCapital() : BigDecimal.ZERO;
                totalLiabilities = sheet.getTotalLiabilities() != null ? sheet.getTotalLiabilities() : BigDecimal.ZERO;
            }

            BigDecimal bps = (totalCapital.compareTo(BigDecimal.ZERO) != 0)
                    ? totalCapital.multiply(unitMultiplier).divide(shares, 0, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            BigDecimal roe = BigDecimal.ZERO;
            if (totalCapital.compareTo(BigDecimal.ZERO) != 0) {
                // ROE는 비율이므로 단위 보정 불필요 (분자/분모 동일 단위)
                roe = netIncome.divide(totalCapital, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal(100)).setScale(2, RoundingMode.HALF_UP);
            }

            BigDecimal debtRatio = BigDecimal.ZERO;
            if (totalCapital.compareTo(BigDecimal.ZERO) != 0 && totalLiabilities.compareTo(BigDecimal.ZERO) != 0) {
                debtRatio = totalLiabilities.divide(totalCapital, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal(100)).setScale(2, RoundingMode.HALF_UP);
            }

            // Valuations
            BigDecimal per = BigDecimal.ZERO;
            BigDecimal pbr = BigDecimal.ZERO;
            BigDecimal psr = BigDecimal.ZERO;
            BigDecimal price = priceInfo.price;

            if (price.compareTo(BigDecimal.ZERO) > 0) {
                if (eps.compareTo(BigDecimal.ZERO) != 0) {
                    per = price.divide(eps, 2, RoundingMode.HALF_UP);
                }
                if (bps.compareTo(BigDecimal.ZERO) != 0) {
                    pbr = price.divide(bps, 2, RoundingMode.HALF_UP);
                }
                if (sps.compareTo(BigDecimal.ZERO) != 0) {
                    psr = price.divide(sps, 2, RoundingMode.HALF_UP);
                }
            }

            // Build Entity
            StockFinancialRatio existing = stockFinancialRatioRepository
                    .findByStockCodeAndStacYymmAndDivCode(stockCode, yymm, divCode)
                    .orElse(null);

            StockFinancialRatio ratio = (existing != null ? existing.toBuilder() : StockFinancialRatio.builder())
                    .stockCode(stockCode)
                    .stacYymm(yymm)
                    .divCode(divCode)
                    .isSuspended(priceInfo.isSuspended)
                    .eps(eps)
                    .bps(bps)
                    .roe(roe)
                    .per(per)
                    .pbr(pbr)
                    .psr(psr)
                    .debtRatio(debtRatio)
                    .build();

            results.add(ratio);
        }

        if (!results.isEmpty()) {
            stockFinancialRatioRepository.saveAll(results);
            log.info("Calculated and saved {} financial ratios locally for {}", results.size(), stockCode);
        }

        return results;
    }

    private StockPriceInfo fetchCurrentPrice(String stockCode) {
        try {
            String responseBody = kisApiClient.fetch(token -> webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/uapi/domestic-stock/v1/quotations/inquire-price") // Standard Price URL
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                            .queryParam("FID_INPUT_ISCD", stockCode)
                            .build())
                    .header("authorization", token)
                    .header("appkey", kisApiProperties.getAppkey())
                    .header("appsecret", kisApiProperties.getAppsecret())
                    .header("tr_id", "FHKST01010100"), String.class);

            if (responseBody != null) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(responseBody);
                com.fasterxml.jackson.databind.JsonNode output = root.path("output");

                String priceStr = output.path("stck_prpr").asText();
                String statusCode = output.path("iscd_stat_cls_code").asText(); // 종목상태구분코드

                BigDecimal price = BigDecimal.ZERO;
                if (priceStr != null && !priceStr.isEmpty()) {
                    price = new BigDecimal(priceStr);
                }

                // Check for Suspension (58: 거래정지)
                boolean isSuspended = "58".equals(statusCode);

                String sharesStr = output.path("lstn_stcn").asText(); // Listed Shares
                long listedShares = 0;
                if (sharesStr != null && !sharesStr.isEmpty()) {
                    listedShares = parseLongSafe(sharesStr);
                }

                return new StockPriceInfo(price, isSuspended, listedShares);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch price for {}: {}", stockCode, e.getMessage());
        }
        return new StockPriceInfo(BigDecimal.ZERO, false, 0);
    }

    private static class StockPriceInfo {
        BigDecimal price;
        boolean isSuspended;
        long listedShares;

        StockPriceInfo(BigDecimal price, boolean isSuspended, long listedShares) {
            this.price = price;
            this.isSuspended = isSuspended;
            this.listedShares = listedShares;
        }
    }

    private String parseRateSafe(String value) {
        if (value == null || value.trim().isEmpty() || "-".equals(value.trim())) {
            return "0";
        }
        return value.trim();
    }

    @Cacheable(value = "stockFinancial", key = "#stockCode + '-' + #divCode", sync = true)
    public List<FinancialStatementDto> getIncomeStatement(String stockCode, String divCode) {
        validateDomesticStock(stockCode);
        // 1. Check DB
        List<StockFinancialStatement> dbData = stockFinancialStatementRepository
                .findByStockCodeAndDivCodeOrderByStacYymmAsc(stockCode, divCode);

        if (!dbData.isEmpty()) {
            return convertToFinancialStatementDto(dbData, divCode);
        }

        // 2. Fetch from API and Save
        return refreshIncomeStatement(stockCode, divCode);
    }

    @Transactional
    public List<FinancialStatementDto> refreshIncomeStatement(String stockCode, String divCode) {
        validateDomesticStock(stockCode);
        try {
            KisIncomeStatementApiResponse response = kisApiClient.fetch(token -> webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(kisApiProperties.getIncomeStatementUrl())
                            .queryParam("FID_DIV_CLS_CODE", divCode)
                            .queryParam("fid_cond_mrkt_div_code", "J")
                            .queryParam("fid_input_iscd", stockCode)
                            .build())
                    .header("authorization", token)
                    .header("appkey", kisApiProperties.getAppkey())
                    .header("appsecret", kisApiProperties.getAppsecret())
                    .header("tr_id", "FHKST66430200")
                    .header("custtype", "P"), KisIncomeStatementApiResponse.class);

            if (response == null || !"0".equals(response.getRtCd()) || response.getOutput() == null
                    || response.getOutput().isEmpty()) {
                throw new Exception("Invalid KIS response or empty data");
            }

            // Fetch Current Price & Status
            StockPriceInfo priceInfo = fetchCurrentPrice(stockCode);

            // Save to DB with Upsert Logic
            List<StockFinancialStatement> entitiesToSave = new ArrayList<>();
            for (KisIncomeStatementApiResponse.IncomeStatementOutput item : response.getOutput()) {
                StockFinancialStatement existing = stockFinancialStatementRepository
                        .findByStockCodeAndStacYymmAndDivCode(stockCode, item.getStacYymm(), divCode)
                        .orElse(null);

                StockFinancialStatement entity;
                if (existing != null) {
                    entity = StockFinancialStatement.builder()
                            .id(existing.getId()) // Keep ID for update
                            .stockCode(stockCode)
                            .stacYymm(item.getStacYymm())
                            .divCode(divCode)
                            .isSuspended(priceInfo.isSuspended)
                            .saleAccount(new BigDecimal(parseLongSafe(item.getSaleAccount())))
                            .operatingProfit(new BigDecimal(parseLongSafe(item.getBsopPrti())))
                            .netIncome(new BigDecimal(parseLongSafe(item.getThtrNtin())))
                            .build();
                } else {
                    entity = StockFinancialStatement.builder()
                            .stockCode(stockCode)
                            .stacYymm(item.getStacYymm())
                            .divCode(divCode)
                            .isSuspended(priceInfo.isSuspended)
                            .saleAccount(new BigDecimal(parseLongSafe(item.getSaleAccount())))
                            .operatingProfit(new BigDecimal(parseLongSafe(item.getBsopPrti())))
                            .netIncome(new BigDecimal(parseLongSafe(item.getThtrNtin())))
                            .build();
                }
                entitiesToSave.add(entity);
            }

            try {
                stockFinancialStatementRepository.saveAll(entitiesToSave);
            } catch (Exception e) {
                log.warn("Save error for income statement {}: {}", stockCode, e.getMessage());
            }

            // Sort ASC for calculation
            entitiesToSave.sort((o1, o2) -> o1.getStacYymm().compareTo(o2.getStacYymm()));
            return convertToFinancialStatementDto(entitiesToSave, divCode);

        } catch (Exception e) {
            log.warn("Failed to fetch income statement from KIS for {}: {}", stockCode, e.getMessage());
            return new ArrayList<>();
        }
    }

    public void refreshAllIncomeStatements(String divCode) {
        List<Stock> stocks = stockRepository.findAll();
        log.info("Starting Batch Income Statement Refresh for {} stocks (divCode={})", stocks.size(), divCode);

        for (Stock stock : stocks) {
            try {
                refreshIncomeStatement(stock.getStockCode(), divCode);
                Thread.sleep(100);
            } catch (Exception e) {
                log.error("Failed to refresh income statement for {}: {}", stock.getStockCode(), e.getMessage());
            }
        }
        log.info("Completed Batch Income Statement Refresh");
    }

    private List<FinancialStatementDto> convertToFinancialStatementDto(List<StockFinancialStatement> entities,
            String divCode) {
        List<FinancialStatementDto> result = new ArrayList<>();

        // entities are assumed to be sorted ASC by date
        for (int i = 0; i < entities.size(); i++) {
            StockFinancialStatement current = entities.get(i);
            String date = current.getStacYymm();

            long sales = current.getSaleAccount().longValue();
            long operatingProfit = current.getOperatingProfit().longValue();
            long netIncome = current.getNetIncome().longValue();

            // Accumulated Quarter Logic
            if ("1".equals(divCode) && date.length() == 6 && !date.endsWith("03")) {
                if (i > 0) {
                    StockFinancialStatement prev = entities.get(i - 1);
                    if (prev.getStacYymm().startsWith(date.substring(0, 4))) {
                        sales -= prev.getSaleAccount().longValue();
                        operatingProfit -= prev.getOperatingProfit().longValue();
                        netIncome -= prev.getNetIncome().longValue();
                    }
                }
            }

            result.add(FinancialStatementDto.builder()
                    .stacYymm(date)
                    .saleAccount(String.valueOf(sales))
                    .operatingProfit(String.valueOf(operatingProfit))
                    .netIncome(String.valueOf(netIncome))
                    .build());
        }

        // Final output: Sort DESC
        result.sort((o1, o2) -> o2.getStacYymm().compareTo(o1.getStacYymm()));
        return result;
    }

    @Cacheable(value = "financialRank", key = "#sort", sync = true)
    public FinancialRankDto getFinancialRank(String sort) {
        // 1. Fetch from Consolidated Rank Table
        List<StockFinancialRank> allRanks = stockFinancialRankRepository.findAll();

        // 2. Map to DTO
        List<FinancialRankDto.FinancialRankEntry> calculatedRanks = allRanks.stream()
                .map(rank -> FinancialRankDto.FinancialRankEntry.builder()
                        .stockCode(rank.getStockCode())
                        .stockName(rank.getStockName())
                        .stacYymm(rank.getStacYymm())
                        .saleTotalProfit(rank.getSaleAccount() != null ? rank.getSaleAccount().toString() : "0")
                        .operatingProfit(rank.getOperatingProfit() != null ? rank.getOperatingProfit().toString() : "0")
                        .netIncome(rank.getNetIncome() != null ? rank.getNetIncome().toString() : "0")
                        .totalAssets(rank.getTotalAssets() != null ? rank.getTotalAssets().toString() : "0")
                        .totalLiabilities(
                                rank.getTotalLiabilities() != null ? rank.getTotalLiabilities().toString() : "0")
                        .totalCapital(rank.getTotalCapital() != null ? rank.getTotalCapital().toString() : "0")
                        .operatingRate(calculateOperatingRate(rank.getOperatingProfit(), rank.getSaleAccount()))
                        .build())
                .collect(Collectors.toList());

        // 3. Sort
        Comparator<FinancialRankDto.FinancialRankEntry> comparator;
        if ("operating".equalsIgnoreCase(sort)) {
            comparator = Comparator.comparing(e -> new BigDecimal(e.getOperatingProfit()));
        } else if ("netincome".equalsIgnoreCase(sort)) {
            comparator = Comparator.comparing(e -> new BigDecimal(e.getNetIncome()));
        } else if ("margin".equalsIgnoreCase(sort) || "operatingmargin".equalsIgnoreCase(sort)) {
            comparator = Comparator.comparing(e -> new BigDecimal(e.getOperatingRate()));
        } else if ("assets".equalsIgnoreCase(sort)) {
            comparator = Comparator.comparing(e -> new BigDecimal(e.getTotalAssets()));
        } else if ("liabilities".equalsIgnoreCase(sort)) {
            comparator = Comparator.comparing(e -> new BigDecimal(e.getTotalLiabilities()));
        } else if ("capital".equalsIgnoreCase(sort)) {
            comparator = Comparator.comparing(e -> new BigDecimal(e.getTotalCapital()));
        } else {
            comparator = Comparator.comparing(e -> new BigDecimal(e.getSaleTotalProfit()));
        }

        List<FinancialRankDto.FinancialRankEntry> top20 = calculatedRanks.stream()
                .sorted(comparator.reversed())
                .limit(20)
                .collect(Collectors.toList());

        // 4. Assign strict rank and Fetch Current Price
        for (int i = 0; i < top20.size(); i++) {
            FinancialRankDto.FinancialRankEntry entry = top20.get(i);
            entry.setRank(String.valueOf(i + 1));

            // Fetch current price for TOP 20
            try {
                StockPriceInfo priceInfo = fetchCurrentPrice(entry.getStockCode());
                if (priceInfo != null && priceInfo.price != null) {
                    entry.setCurrentPrice(priceInfo.price.toString());
                } else {
                    entry.setCurrentPrice("0");
                }
            } catch (Exception e) {
                log.warn("Failed to fetch current price for rank entry {}: {}", entry.getStockCode(), e.getMessage());
                entry.setCurrentPrice("0");
            }
        }

        return FinancialRankDto.builder().ranks(top20).build();
    }

    private long getMonthsDifference(String yyyyMM_prev, String yyyyMM_curr) {
        try {
            int y1 = Integer.parseInt(yyyyMM_prev.substring(0, 4));
            int m1 = Integer.parseInt(yyyyMM_prev.substring(4, 6));
            int y2 = Integer.parseInt(yyyyMM_curr.substring(0, 4));
            int m2 = Integer.parseInt(yyyyMM_curr.substring(4, 6));
            return (y2 - y1) * 12 + (m2 - m1);
        } catch (Exception e) {
            return 12; // Default to annual if parse fails
        }
    }

    // Removed @Transactional to prevent rollback loop
    public void fetchAndSaveAllFinancialStatements() {
        log.info("Starting fetchAndSaveAllFinancialStatements (Migration to refreshAllIncomeStatements)");
        // Previously used for init ranking, now use refactored method
        refreshAllIncomeStatements("0");
        refreshFinancialRank();
    }

    @Transactional
    @CacheEvict(value = "financialRank", allEntries = true)
    public void refreshFinancialRank() {
        // 1. Fetch Windowed Data for all stocks to avoid OOM
        String minDate = LocalDate.now().minusYears(3).format(DateTimeFormatter.ofPattern("yyyy"))
                + "01";

        List<StockFinancialStatement> allStatements = stockFinancialStatementRepository
                .findByDivCodeAndStacYymmGreaterThanEqual("0", minDate);
        List<StockBalanceSheet> allBalanceSheets = stockBalanceSheetRepository
                .findByDivCodeAndStacYymmGreaterThanEqual("0", minDate);
        List<Stock> allStocks = stockRepository.findAll();

        Map<String, List<StockFinancialStatement>> groupedStatements = allStatements.stream()
                .collect(Collectors.groupingBy(StockFinancialStatement::getStockCode));
        Map<String, List<StockBalanceSheet>> groupedBalanceSheets = allBalanceSheets.stream()
                .collect(Collectors.groupingBy(StockBalanceSheet::getStockCode));

        List<StockFinancialRank> newRanks = new ArrayList<>();

        for (Stock stock : allStocks) {
            String code = stock.getStockCode();
            List<StockFinancialStatement> stmts = groupedStatements.getOrDefault(code, new ArrayList<>());
            List<StockBalanceSheet> sheets = groupedBalanceSheets.getOrDefault(code, new ArrayList<>());

            if (stmts.isEmpty())
                continue;

            // Sort Descending
            stmts.sort((o1, o2) -> o2.getStacYymm().compareTo(o1.getStacYymm()));
            sheets.sort((o1, o2) -> o2.getStacYymm().compareTo(o1.getStacYymm()));

            StockFinancialStatement latest = stmts.get(0);
            BigDecimal sale = latest.getSaleAccount();
            BigDecimal op = latest.getOperatingProfit();
            BigDecimal ni = latest.getNetIncome();

            // TTM Logic
            if (stmts.size() >= 2) {
                long monthsDiff = getMonthsDifference(stmts.get(1).getStacYymm(), latest.getStacYymm());
                if (monthsDiff <= 3) { // Quarterly
                    int count = Math.min(stmts.size(), 4);
                    sale = BigDecimal.ZERO;
                    op = BigDecimal.ZERO;
                    ni = BigDecimal.ZERO;
                    for (int i = 0; i < count; i++) {
                        sale = sale.add(stmts.get(i).getSaleAccount());
                        op = op.add(stmts.get(i).getOperatingProfit());
                        ni = ni.add(stmts.get(i).getNetIncome());
                    }
                } else if (monthsDiff <= 6) { // Semi-Annual
                    sale = sale.add(stmts.get(1).getSaleAccount());
                    op = op.add(stmts.get(1).getOperatingProfit());
                    ni = ni.add(stmts.get(1).getNetIncome());
                }
            }

            // Latest Balance Sheet
            BigDecimal totalAssets = BigDecimal.ZERO;
            BigDecimal totalLiabilities = BigDecimal.ZERO;
            BigDecimal totalCapital = BigDecimal.ZERO;
            if (!sheets.isEmpty()) {
                totalAssets = sheets.get(0).getTotalAssets();
                totalLiabilities = sheets.get(0).getTotalLiabilities();
                totalCapital = sheets.get(0).getTotalCapital();
            }

            newRanks.add(StockFinancialRank.builder()
                    .stockCode(code)
                    .stockName(stock.getStockName())
                    .stacYymm(latest.getStacYymm())
                    .saleAccount(sale)
                    .operatingProfit(op)
                    .netIncome(ni)
                    .totalAssets(totalAssets)
                    .totalLiabilities(totalLiabilities)
                    .totalCapital(totalCapital)
                    .build());
        }

        stockFinancialRankRepository.deleteAll();
        stockFinancialRankRepository.saveAll(newRanks);
        log.info("Refreshed consolidated Financial Ranks for {} stocks", newRanks.size());
    }

    private String calculateOperatingRate(BigDecimal operatingProfit, BigDecimal saleAccount) {
        if (saleAccount == null || operatingProfit == null || saleAccount.compareTo(BigDecimal.ZERO) == 0) {
            return "0";
        }
        return operatingProfit.divide(saleAccount, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal(100))
                .setScale(2, RoundingMode.HALF_UP)
                .toString();
    }

    private long parseLongSafe(String value) {
        if (value == null || value.isEmpty())
            return 0;
        try {
            if (value.contains("."))
                return (long) Double.parseDouble(value);
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Cacheable(value = "stockMetrics", key = "#stockCode + '-v4'", sync = true)
    public InvestmentMetricDto getInvestmentMetrics(String stockCode) {
        // Validate StockType: This controller is for domestic stocks
        Stock stock = stockRepository.findByStockCode(stockCode)
                .orElse(null);

        if (stock != null && stock.getStockType() != Stock.StockType.DOMESTIC) {
            log.warn("Requested domestic metrics for non-domestic stock: {}", stockCode);
            return InvestmentMetricDto.builder().stockCode(stockCode).build();
        }

        // 1. Fetch latest financial ratio from API and save to DB
        // Use "0" (Yearly) as default, can be parameterized if needed.
        List<StockFinancialRatio> ratios = fetchAndSaveFinancialRatio(stockCode, "0");

        if (ratios.isEmpty()) {
            // Fallback to DB query if API fetch fails or returns empty (though fetchAndSave
            // handles saving)
            StockFinancialRatio latest = stockFinancialRatioRepository
                    .findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
            if (latest != null) {
                ratios = new ArrayList<>();
                ratios.add(latest);
            } else {
                return InvestmentMetricDto.builder()
                        .stockCode(stockCode)
                        .stacYymm("")
                        .per("0")
                        .pbr("0")
                        .psr("0")
                        .eps("0")
                        .roe("0")
                        .bps("0")
                        .build();
            }
        }

        // 2. Get the latest VALID one (PBR > 0 or other indicators)
        // API response order is not guaranteed, so sort by stacYymm desc
        ratios.sort((o1, o2) -> o2.getStacYymm().compareTo(o1.getStacYymm()));

        StockFinancialRatio latest = ratios.stream()
                .filter(r -> r.getPbr() != null && r.getPbr().compareTo(BigDecimal.ZERO) > 0)
                .findFirst()
                .orElse(ratios.get(0)); // Fallback to absolute latest if no valid data found

        if (latest == null) {
            return InvestmentMetricDto.builder().stockCode(stockCode).build();
        }

        // EV/EBITDA from DB
        String evEbitdaStr = "N/A";
        if (latest.getEvEbitda() != null) {
            evEbitdaStr = String.format("%.2fx", latest.getEvEbitda());
        }

        return InvestmentMetricDto.builder()
                .stockCode(latest.getStockCode())
                .stacYymm(latest.getStacYymm())
                .per(latest.getPer() != null ? latest.getPer().toString() : "0")
                .pbr(latest.getPbr() != null ? latest.getPbr().toString() : "0")
                .psr(latest.getPsr() != null ? latest.getPsr().toString() : "0")
                .eps(latest.getEps() != null ? latest.getEps().toString() : "0")
                .roe(latest.getRoe() != null ? latest.getRoe().toString() : "0")
                .bps(latest.getBps() != null ? latest.getBps().toString() : "0")
                .evEbitda(evEbitdaStr)
                .build();
    }

    public String getEvEbitdaFromDb(String stockCode) {
        validateDomesticStock(stockCode);
        // Try "1" (Quarterly) first
        StockFinancialRatio latest = stockFinancialRatioRepository
                .findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "1");

        if (latest == null || latest.getEvEbitda() == null) {
            latest = stockFinancialRatioRepository
                    .findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
        }

        if (latest != null && latest.getEvEbitda() != null) {
            return String.format("%.2fx", latest.getEvEbitda());
        }
        return null;
    }

    @Transactional
    public void updateListingDate(String stockCode) {
        validateDomesticStock(stockCode);
        Stock stock = stockRepository.findByStockCode(stockCode)
                .orElseThrow(() -> new BusinessException(KisApiErrorCode.STOCK_NOT_FOUND));

        try {
            KisStockSearchInfoApiResponse response = kisApiClient.fetch(token -> webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(kisApiProperties.getSearchStockInfoUrl())
                            .queryParam("PRDT_TYPE_CD", "300")
                            .queryParam("PDNO", stockCode)
                            .build())
                    .header("authorization", token)
                    .header("appkey", kisApiProperties.getAppkey())
                    .header("appsecret", kisApiProperties.getAppsecret())
                    .header("tr_id", "CTPF1002R")
                    .header("custtype", "P"), KisStockSearchInfoApiResponse.class);

            if (response != null && "0".equals(response.getRtCd()) && response.getOutput() != null) {
                String listingDateStr = response.getOutput().getSctsMketLstgDt();
                if (listingDateStr == null || listingDateStr.trim().isEmpty() || "00000000".equals(listingDateStr)) {
                    listingDateStr = response.getOutput().getKosdaqMketLstgDt();
                }

                if (listingDateStr != null && listingDateStr.length() == 8) {
                    LocalDate listingDate = LocalDate.parse(listingDateStr, DateTimeFormatter.ofPattern("yyyyMMdd"));
                    stock.updateListingDate(listingDate);
                    stockRepository.save(stock);
                    log.info("Successfully updated listing date for {}: {}", stockCode, listingDate);
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch listing date for {}: {}", stockCode, e.getMessage());
        }
    }

    public void updateAllListingDates() {
        List<Stock> domesticStocks = stockRepository.findAll().stream()
                .filter(s -> s.getStockType() == Stock.StockType.DOMESTIC)
                .collect(Collectors.toList());

        log.info("Starting batch listing date update for {} stocks", domesticStocks.size());
        for (Stock stock : domesticStocks) {
            try {
                updateListingDate(stock.getStockCode());
                Thread.sleep(100);
            } catch (Exception e) {
                log.error("Error updating listing date for {}: {}", stock.getStockCode(), e.getMessage());
            }
        }
        log.info("Completed batch listing date update");
    }

    public void validateDomesticStock(String stockCode) {
        Stock stock = stockRepository.findByStockCode(stockCode)
                .orElseThrow(() -> new BusinessException(KisApiErrorCode.STOCK_NOT_FOUND));

        if (stock.getStockType() != Stock.StockType.DOMESTIC) {
            log.warn("Invalid stock type for domestic service: {} ({})", stockCode, stock.getStockType());
            throw new BusinessException(KisApiErrorCode.INVALID_STOCK_TYPE);
        }
    }
}
