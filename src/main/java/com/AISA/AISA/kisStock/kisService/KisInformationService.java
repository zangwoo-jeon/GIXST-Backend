package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.config.KisApiProperties;
import com.AISA.AISA.kisStock.dto.FinancialRank.BalanceSheetDto;
import com.AISA.AISA.kisStock.dto.FinancialRank.FinancialRankDto;
import com.AISA.AISA.kisStock.dto.FinancialRank.InvestmentMetricDto;
import com.AISA.AISA.kisStock.dto.FinancialRank.KisBalanceSheetApiResponse;
import com.AISA.AISA.kisStock.dto.FinancialRank.KisIncomeStatementApiResponse;
import com.AISA.AISA.kisStock.exception.KisApiErrorCode;
import com.AISA.AISA.kisStock.kisService.Auth.KisAuthService;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialRank;
import com.AISA.AISA.kisStock.repository.StockFinancialRankRepository;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialStatement;
import com.AISA.AISA.kisStock.repository.StockFinancialStatementRepository;
import com.AISA.AISA.kisStock.Entity.stock.StockBalanceSheet;
import com.AISA.AISA.kisStock.repository.StockBalanceSheetRepository;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialRatio;
import com.AISA.AISA.kisStock.repository.StockFinancialRatioRepository;
import com.AISA.AISA.kisStock.dto.FinancialRank.KisFinancialRatioApiResponse;
import com.AISA.AISA.kisStock.dto.FinancialRank.FinancialRatioRankDto;
import java.math.BigDecimal;
import java.util.Map;
import com.AISA.AISA.kisStock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KisInformationService {

    private final WebClient webClient;
    private final KisApiProperties kisApiProperties;
    private final KisAuthService kisAuthService;
    private final StockRepository stockRepository;
    private final StockBalanceSheetRepository stockBalanceSheetRepository;
    private final StockFinancialRankRepository stockFinancialRankRepository;
    private final StockFinancialRatioRepository stockFinancialRatioRepository;
    private final StockFinancialStatementRepository stockFinancialStatementRepository;

    public List<BalanceSheetDto> getBalanceSheet(String stockCode, String divCode) {
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

    public List<BalanceSheetDto> refreshBalanceSheet(String stockCode, String divCode) {
        // 1. Fetch from API
        String accessToken = kisAuthService.getAccessToken();

        try {
            KisBalanceSheetApiResponse response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(kisApiProperties.getBalanceSheetUrl())
                            .queryParam("FID_DIV_CLS_CODE", divCode)
                            .queryParam("fid_cond_mrkt_div_code", "J")
                            .queryParam("fid_input_iscd", stockCode)
                            .build())
                    .header("authorization", accessToken)
                    .header("appkey", kisApiProperties.getAppkey())
                    .header("appsecret", kisApiProperties.getAppsecret())
                    .header("tr_id", "FHKST66430100")
                    .header("custtype", "P")
                    .retrieve()
                    .bodyToMono(KisBalanceSheetApiResponse.class)
                    .block();

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
            log.error("Failed to fetch balance sheet for {}: {}", stockCode, e.getMessage(), e);
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

    public FinancialRatioRankDto getFinancialRatioRank(String sort, String divCode) {
        // 1. Find latest available date
        StockFinancialRatio latest = stockFinancialRatioRepository.findTop1ByDivCodeOrderByStacYymmDesc(divCode);
        if (latest == null) {
            return FinancialRatioRankDto.builder().ranks(new ArrayList<>()).build();
        }
        String stacYymm = latest.getStacYymm();

        // 2. Fetch Ranking
        List<StockFinancialRatio> ratios;
        if ("roe".equalsIgnoreCase(sort)) {
            ratios = stockFinancialRatioRepository.findAllByDivCodeAndStacYymmOrderByRoeDesc(divCode, stacYymm);
        } else if ("eps".equalsIgnoreCase(sort)) {
            ratios = stockFinancialRatioRepository.findAllByDivCodeAndStacYymmOrderByEpsDesc(divCode, stacYymm);
        } else if ("debt".equalsIgnoreCase(sort) || "debtratio".equalsIgnoreCase(sort)) {
            ratios = stockFinancialRatioRepository.findAllByDivCodeAndStacYymmOrderByDebtRatioAsc(divCode, stacYymm);
        } else if ("per".equalsIgnoreCase(sort)) {
            ratios = stockFinancialRatioRepository.findAllByDivCodeAndStacYymmAndPerGreaterThanOrderByPerAsc(divCode,
                    stacYymm, java.math.BigDecimal.ZERO);
        } else if ("pbr".equalsIgnoreCase(sort)) {
            ratios = stockFinancialRatioRepository.findAllByDivCodeAndStacYymmAndPbrGreaterThanOrderByPbrAsc(divCode,
                    stacYymm, java.math.BigDecimal.ZERO);
        } else if ("psr".equalsIgnoreCase(sort)) {
            ratios = stockFinancialRatioRepository.findAllByDivCodeAndStacYymmAndPsrGreaterThanOrderByPsrAsc(divCode,
                    stacYymm, java.math.BigDecimal.ZERO);
        } else {
            // Default ROE
            ratios = stockFinancialRatioRepository.findAllByDivCodeAndStacYymmOrderByRoeDesc(divCode, stacYymm);
        }

        // 3. Map Stock Names (Optimize N+1)
        Map<String, String> stockMap = stockRepository.findAll().stream()
                .collect(Collectors.toMap(Stock::getStockCode, Stock::getStockName));

        // 4. Map to DTO (Top 20)
        List<FinancialRatioRankDto.FinancialRatioEntry> entries = ratios.stream()
                .limit(20)
                .map(ratio -> FinancialRatioRankDto.FinancialRatioEntry.builder()
                        .rank(String.valueOf(ratios.indexOf(ratio) + 1))
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

        return FinancialRatioRankDto.builder().ranks(entries).build();
    }

    public List<StockFinancialRatio> fetchAndSaveFinancialRatio(String stockCode, String divCode) {
        String accessToken = kisAuthService.getAccessToken();

        try {
            // 1. Fetch Financial Ratio (EPS, BPS, SPS, etc.)
            KisFinancialRatioApiResponse response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(kisApiProperties.getFinancialRatioUrl())
                            .queryParam("FID_DIV_CLS_CODE", divCode)
                            .queryParam("fid_cond_mrkt_div_code", "J")
                            .queryParam("fid_input_iscd", stockCode)
                            .build())
                    .header("authorization", accessToken)
                    .header("appkey", kisApiProperties.getAppkey())
                    .header("appsecret", kisApiProperties.getAppsecret())
                    .header("tr_id", "FHKST66430300")
                    .header("custtype", "P")
                    .retrieve()
                    .bodyToMono(KisFinancialRatioApiResponse.class)
                    .block();

            if (response == null || !"0".equals(response.getRtCd()) || response.getOutput() == null) {
                log.error("Financial Ratio API Error for {}. RtCd: {}, Msg: {}",
                        stockCode,
                        response != null ? response.getRtCd() : "null",
                        response != null ? response.getMsg1() : "null response");
                return new ArrayList<>();
            }

            // 2. Fetch Current Price (for PER/PBR Calc)
            BigDecimal currentPrice = fetchCurrentPrice(stockCode, accessToken);

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
                                .divCode(divCode);

                // Set/Update Financials
                BigDecimal eps = new BigDecimal(parseRateSafe(item.getEps()));
                BigDecimal bps = new BigDecimal(parseRateSafe(item.getBps()));
                BigDecimal sps = new BigDecimal(parseRateSafe(item.getSps()));

                builder.roe(new BigDecimal(parseRateSafe(item.getRoeVal())))
                        .eps(eps)
                        .bps(bps)
                        .debtRatio(new BigDecimal(parseRateSafe(item.getLbltRate())))
                        .reserveRatio(new BigDecimal(parseRateSafe(item.getRsrvRate())))
                        .salesGrowth(new BigDecimal(parseRateSafe(item.getGrs())))
                        .operatingProfitGrowth(new BigDecimal(parseRateSafe(item.getBsopPrfiInrt())))
                        .netIncomeGrowth(new BigDecimal(parseRateSafe(item.getNtinInrt())));

                // Set/Update Valuations (PER, PBR, PSR)
                if (currentPrice.compareTo(BigDecimal.ZERO) > 0) {
                    if (eps.compareTo(BigDecimal.ZERO) > 0) {
                        builder.per(currentPrice.divide(eps, 2, java.math.RoundingMode.HALF_UP));
                    }
                    if (bps.compareTo(BigDecimal.ZERO) > 0) {
                        builder.pbr(currentPrice.divide(bps, 2, java.math.RoundingMode.HALF_UP));
                    }
                    if (sps.compareTo(BigDecimal.ZERO) > 0) {
                        builder.psr(currentPrice.divide(sps, 2, java.math.RoundingMode.HALF_UP));
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
            return new ArrayList<>();
        }
    }

    private BigDecimal fetchCurrentPrice(String stockCode, String accessToken) {
        try {
            String responseBody = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/uapi/domestic-stock/v1/quotations/inquire-price") // Standard Price URL
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                            .queryParam("FID_INPUT_ISCD", stockCode)
                            .build())
                    .header("authorization", accessToken)
                    .header("appkey", kisApiProperties.getAppkey())
                    .header("appsecret", kisApiProperties.getAppsecret())
                    .header("tr_id", "FHKST01010100")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (responseBody != null) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(responseBody);
                String priceStr = root.path("output").path("stck_prpr").asText();
                if (priceStr != null && !priceStr.isEmpty()) {
                    return new BigDecimal(priceStr);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch price for {}: {}", stockCode, e.getMessage());
        }
        return BigDecimal.ZERO;
    }

    private String parseRateSafe(String value) {
        if (value == null || value.trim().isEmpty() || "-".equals(value.trim())) {
            return "0";
        }
        return value.trim();
    }

    public KisIncomeStatementApiResponse getIncomeStatement(String stockCode, String divCode) {
        String accessToken = kisAuthService.getAccessToken();

        try {
            return webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(kisApiProperties.getIncomeStatementUrl())
                            .queryParam("FID_DIV_CLS_CODE", divCode)
                            .queryParam("fid_cond_mrkt_div_code", "J")
                            .queryParam("fid_input_iscd", stockCode)
                            .build())
                    .header("authorization", accessToken)
                    .header("appkey", kisApiProperties.getAppkey())
                    .header("appsecret", kisApiProperties.getAppsecret())
                    .header("tr_id", "FHKST66430200")
                    .header("custtype", "P")
                    .retrieve()
                    .bodyToMono(KisIncomeStatementApiResponse.class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to fetch income statement: {}", e.getMessage());
            return null;
        }
    }

    public FinancialRankDto getFinancialRank(String sort) {
        // Default DivCode for Ranking is "0" (Yearly) unless specified otherwise.
        String divCode = "0";

        // 1. Find latest available date
        StockFinancialStatement latest = stockFinancialStatementRepository.findTop1ByDivCodeOrderByStacYymmDesc(divCode)
                .orElse(null);

        if (latest == null) {
            return FinancialRankDto.builder().ranks(new ArrayList<>()).build();
        }
        String stacYymm = latest.getStacYymm();

        List<StockFinancialStatement> ranks;
        if ("operating".equalsIgnoreCase(sort)) {
            ranks = stockFinancialStatementRepository.findTop20ByStacYymmAndDivCodeOrderByOperatingProfitDesc(stacYymm,
                    divCode);
        } else if ("netincome".equalsIgnoreCase(sort)) {
            ranks = stockFinancialStatementRepository.findTop20ByStacYymmAndDivCodeOrderByNetIncomeDesc(stacYymm,
                    divCode);
        } else if ("margin".equalsIgnoreCase(sort) || "operatingmargin".equalsIgnoreCase(sort)) {
            ranks = stockFinancialStatementRepository.findTop20ByOperatingMarginDesc(stacYymm, divCode,
                    org.springframework.data.domain.Pageable.ofSize(20));
        } else {
            ranks = stockFinancialStatementRepository.findTop20ByStacYymmAndDivCodeOrderBySaleAccountDesc(stacYymm,
                    divCode);
        }

        Map<String, String> stockMap = stockRepository.findAll().stream()
                .collect(Collectors.toMap(Stock::getStockCode, Stock::getStockName));

        List<FinancialRankDto.FinancialRankEntry> entries = ranks.stream()
                .map(rank -> FinancialRankDto.FinancialRankEntry.builder()
                        .rank(String.valueOf(ranks.indexOf(rank) + 1))
                        .stockCode(rank.getStockCode())
                        .stockName(stockMap.getOrDefault(rank.getStockCode(), "Unknown"))
                        .saleTotalProfit(rank.getSaleAccount() != null ? rank.getSaleAccount().toString() : "0")
                        .operatingProfit(rank.getOperatingProfit() != null ? rank.getOperatingProfit().toString() : "0")
                        .netIncome(rank.getNetIncome() != null ? rank.getNetIncome().toString() : "0")
                        .totalAssets("0")
                        .totalLiabilities("0")
                        .totalCapital("0")
                        .operatingRate(calculateOperatingRate(rank.getOperatingProfit(), rank.getSaleAccount()))
                        .build())
                .collect(Collectors.toList());

        return FinancialRankDto.builder().ranks(entries).build();
    }

    @Transactional
    public void fetchAndSaveAllFinancialStatements() {
        log.info("Starting fetchAndSaveAllFinancialStatements");
        refreshFinancialRank();
    }

    @Transactional
    public void refreshFinancialRank() {
        stockFinancialRankRepository.deleteAll();

        List<Stock> stocks = stockRepository.findAll();
        List<StockFinancialRank> newRanks = new ArrayList<>();

        for (Stock stock : stocks) {
            try {
                KisIncomeStatementApiResponse response = getIncomeStatement(stock.getStockCode(), "0");
                if (response != null && response.getOutput() != null && !response.getOutput().isEmpty()) {
                    KisIncomeStatementApiResponse.IncomeStatementOutput latest = response.getOutput().stream()
                            .sorted((o1, o2) -> o2.getStacYymm().compareTo(o1.getStacYymm()))
                            .findFirst()
                            .orElse(null);

                    if (latest != null) {
                        newRanks.add(StockFinancialRank.builder()
                                .stockCode(stock.getStockCode())
                                .stockName(stock.getStockName())
                                .stacYymm(latest.getStacYymm())
                                .saleAccount(new java.math.BigDecimal(parseLongSafe(latest.getSaleAccount())))
                                .operatingProfit(new java.math.BigDecimal(parseLongSafe(latest.getBsopPrti())))
                                .netIncome(new java.math.BigDecimal(parseLongSafe(latest.getThtrNtin())))
                                .saleTotlPrfi(new java.math.BigDecimal(0))
                                .build());
                    }
                }
                Thread.sleep(100);
            } catch (Exception e) {
                log.warn("Failed to refresh financial rank for {}", stock.getStockName());
            }
        }
        stockFinancialRankRepository.saveAll(newRanks);
        log.info("Refreshed Financial Ranks for {} stocks", newRanks.size());
    }

    private String calculateOperatingRate(BigDecimal operatingProfit, BigDecimal saleAccount) {
        if (saleAccount == null || operatingProfit == null || saleAccount.compareTo(BigDecimal.ZERO) == 0) {
            return "0";
        }
        return operatingProfit.divide(saleAccount, 4, java.math.RoundingMode.HALF_UP)
                .multiply(new BigDecimal(100))
                .setScale(2, java.math.RoundingMode.HALF_UP)
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

    public InvestmentMetricDto getInvestmentMetrics(String stockCode) {
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

        // 2. Get the latest one (fetchAndSaveFinancialRatio returns all items from API,
        // usually sorted or we sort)
        // API response order is not guaranteed, so sort by stacYymm desc
        StockFinancialRatio latest = ratios.stream()
                .sorted((o1, o2) -> o2.getStacYymm().compareTo(o1.getStacYymm()))
                .findFirst()
                .orElse(null);

        if (latest == null) {
            return InvestmentMetricDto.builder().stockCode(stockCode).build();
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
                .build();
    }
}
