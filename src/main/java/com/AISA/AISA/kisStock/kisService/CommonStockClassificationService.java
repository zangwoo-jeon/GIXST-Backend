package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.config.KisApiProperties;
import com.AISA.AISA.kisStock.dto.StockPrice.KisEtfPriceResponseDto;
import com.AISA.AISA.kisStock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CommonStockClassificationService {

    private final StockRepository stockRepository;
    private final KisApiClient kisApiClient;
    private final KisApiProperties kisApiProperties;
    private final WebClient webClient;

    private static final List<String> NON_COMMON_KEYWORDS = Arrays.asList(
            "스팩", "SPAC", "리츠", "REITs", "부동산투자", "인프라", "ETN");

    private static final List<String> FOREIGN_ETF_KEYWORDS = Arrays.asList(
            "미국", "나스닥", "NASDAQ", "S&P500", "SNP500", "해외", "글로벌", "재팬", "유로", "중국", "차이나", "인도", "베트남", "독일");

    public void classifyAllDomesticStocks() {
        log.info("Resetting isCommon flag for non-domestic stocks...");
        resetNonDomesticStocks();

        List<Stock> domesticStocks = stockRepository.findByStockType(Stock.StockType.DOMESTIC);
        log.info("Starting common stock classification for {} domestic stocks", domesticStocks.size());

        int updatedCount = 0;
        for (Stock stock : domesticStocks) {
            try {
                processStockClassification(stock);
                updatedCount++;

                // Rate limit handling
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Failed to classify stock {}: {}", stock.getStockCode(), e.getMessage());
            }
        }

        log.info("Finished common stock classification. Processed {} stocks.", updatedCount);
    }

    @Transactional
    public void resetNonDomesticStocks() {
        stockRepository.updateIsCommonByStockType(Stock.StockType.US_STOCK, false);
        stockRepository.updateIsCommonByStockType(Stock.StockType.FOREIGN_ETF, false);
        stockRepository.updateIsCommonByStockType(Stock.StockType.DOMESTIC_ETF, false);
    }

    @Transactional
    public void processStockClassification(Stock stock) {
        String code = stock.getStockCode();
        String name = stock.getStockName();

        // 1. Basic Code Rule: Must end with '0' for common stocks
        if (!code.endsWith("0")) {
            stock.updateIsCommon(false);
            stockRepository.save(stock);
            return;
        }

        // 2. Keyword Filter for non-common (REITs, SPAC, etc.)
        for (String keyword : NON_COMMON_KEYWORDS) {
            if (name.contains(keyword)) {
                stock.updateIsCommon(false);
                stockRepository.save(stock);
                return;
            }
        }

        // 3. KIS ETF/ETN API Check
        if (isEtfOrEtnByApiWithRetry(code)) {
            stock.updateIsCommon(false);

            // Determine specialized ETF type
            boolean isForeign = false;
            for (String kw : FOREIGN_ETF_KEYWORDS) {
                if (name.contains(kw)) {
                    isForeign = true;
                    break;
                }
            }

            Stock.StockType etfType = isForeign ? Stock.StockType.FOREIGN_ETF : Stock.StockType.DOMESTIC_ETF;
            stock.updateStockType(etfType);
            stockRepository.save(stock);
            return;
        }

        // 4. If all checks pass, it's a common domestic stock
        stock.updateIsCommon(true);
        stock.updateStockType(Stock.StockType.DOMESTIC);
        stockRepository.save(stock);
    }

    private boolean isEtfOrEtnByApiWithRetry(String code) {
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try {
                return isEtfOrEtnByApi(code);
            } catch (Exception e) {
                log.warn("Retry {}/{} for ETF check on {}: {}", i + 1, maxRetries, code, e.getMessage());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
            }
        }
        return false; // Safely assume not ETF if API fails repeatedly
    }

    private boolean isEtfOrEtnByApi(String stockCode) {
        String etfPriceUrl = kisApiProperties.getEtfPriceUrl();
        if (etfPriceUrl == null || etfPriceUrl.isBlank()) {
            return false;
        }

        KisEtfPriceResponseDto response = kisApiClient.fetch(token -> webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(etfPriceUrl)
                        .queryParam("fid_input_iscd", stockCode)
                        .queryParam("fid_cond_mrkt_div_code", "J")
                        .build())
                .header("authorization", token)
                .header("appkey", kisApiProperties.getAppkey())
                .header("appsecret", kisApiProperties.getAppsecret())
                .header("tr_id", "FHPST02400000")
                .header("custtype", "P"), KisEtfPriceResponseDto.class);

        if (response == null || !"0".equals(response.getRtCd()) || response.getOutput() == null) {
            return false;
        }

        KisEtfPriceResponseDto.Output output = response.getOutput();

        long etfCrclStcn = parseLongSafely(output.getEtfCrclStcn());
        double nav = parseDoubleSafely(output.getNav());
        double trcErrt = parseDoubleSafely(output.getTrcErrt());
        long etfCnfgIssuCnt = parseLongSafely(output.getEtfCnfgIssuCnt());

        return etfCrclStcn >= 1 || nav >= 1 || trcErrt >= 1 || etfCnfgIssuCnt >= 1;
    }

    private long parseLongSafely(String value) {
        if (value == null || value.isBlank())
            return 0;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double parseDoubleSafely(String value) {
        if (value == null || value.isBlank())
            return 0;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
