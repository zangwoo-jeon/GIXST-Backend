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

    public void classifyAllDomesticStocks() {
        // 1. Initial cleanup for non-domestic types (Dedicated transactions)
        log.info("Resetting isCommon flag for non-domestic stocks...");
        resetNonDomesticStocks();

        List<Stock> domesticStocks = stockRepository.findByStockType(Stock.StockType.DOMESTIC);
        log.info("Starting common stock classification for {} domestic stocks", domesticStocks.size());

        int updatedCount = 0;
        for (Stock stock : domesticStocks) {
            try {
                boolean isCommon = checkIfCommon(stock);
                if (updateStockIsCommon(stock.getStockId(), isCommon)) {
                    updatedCount++;
                }

                // Rate limit handling
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Failed to classify stock {}: {}", stock.getStockCode(), e.getMessage());
            }
        }

        log.info("Finished common stock classification. Updated {} stocks.", updatedCount);
    }

    @Transactional
    public void resetNonDomesticStocks() {
        stockRepository.updateIsCommonByStockType(Stock.StockType.US_STOCK, false);
        stockRepository.updateIsCommonByStockType(Stock.StockType.FOREIGN_ETF, false);
    }

    @Transactional
    public boolean updateStockIsCommon(Long stockId, boolean isCommon) {
        Stock stock = stockRepository.findById(stockId).orElse(null);
        if (stock != null && stock.isCommon() != isCommon) {
            stock.updateIsCommon(isCommon);
            stockRepository.save(stock);
            return true;
        }
        return false;
    }

    private boolean checkIfCommon(Stock stock) {
        String code = stock.getStockCode();
        String name = stock.getStockName();

        // 1. Basic Code Rule: Must end with '0'
        if (!code.endsWith("0")) {
            return false;
        }

        // 2. Keyword Filter
        for (String keyword : NON_COMMON_KEYWORDS) {
            if (name.contains(keyword)) {
                return false;
            }
        }

        // 3. KIS ETF/ETN API Check with Retry
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try {
                return !isEtfOrEtnByApi(code);
            } catch (Exception e) {
                log.warn("Retry {}/{} for ETF check on {}: {}", i + 1, maxRetries, code, e.getMessage());
                try {
                    Thread.sleep(1000); // Wait longer before retry
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
            }
        }
        return true; // Default to true if all retries fail, or false based on safety preference.
                     // Here we return true to avoid accidentally excluding valid stocks.
    }

    private boolean isEtfOrEtnByApi(String stockCode) {
        String etfPriceUrl = kisApiProperties.getEtfPriceUrl();
        if (etfPriceUrl == null || etfPriceUrl.isBlank()) {
            log.warn("ETF Price URL is not configured in application.yml");
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
