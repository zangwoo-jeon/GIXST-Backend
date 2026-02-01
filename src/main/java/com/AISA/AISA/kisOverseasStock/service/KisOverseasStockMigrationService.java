package com.AISA.AISA.kisOverseasStock.service;

import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockRepository;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class KisOverseasStockMigrationService {

    private final KisOverseasStockRepository overseasStockRepository;
    private final KisOverseasStockService kisOverseasStockService;

    /**
     * 차트 데이터가 없는 종목들의 과거 데이터를 복구합니다.
     */
    public void recoverMissingHistoricalData(String startDateStr) {
        log.info("Starting migration: recovering missing historical data for US stocks...");
        List<Stock> stocks = overseasStockRepository.findStocksWithNoDailyData();
        log.info("Found {} stocks with no historical data.", stocks.size());

        for (Stock stock : stocks) {
            try {
                log.info("Recovering data for {} ({})", stock.getStockName(), stock.getStockCode());
                kisOverseasStockService.fetchAndSaveHistoricalOverseasStockData(
                        stock.getStockId().toString(), startDateStr);
                // API Rate limit 방지
                Thread.sleep(200);
            } catch (Exception e) {
                log.error("Failed to recover data for {}: {}", stock.getStockCode(), e.getMessage());
            }
        }
        log.info("Completed migration: Overseas stock historical data recovery finished.");
    }

    /**
     * 기존 종목코드에 포함된 '/'를 '.'으로 일괄 변경합니다.
     */
    @Transactional
    public void normalizeStockCodes() {
        log.info("Starting migration: Normalizing overseas stock codes (replacing / with .)");
        List<Stock> usStocks = overseasStockRepository.findAllByStockType(Stock.StockType.US_STOCK);
        int count = 0;
        for (Stock stock : usStocks) {
            String originalCode = stock.getStockCode();
            if (originalCode != null && originalCode.contains("/")) {
                String normalizedCode = originalCode.replace('/', '.');
                try {
                    updateStockCodeNative(stock.getStockId(), normalizedCode);
                    log.info("Normalized stock code: {} -> {}", originalCode, normalizedCode);
                    count++;
                } catch (Exception e) {
                    log.error("Failed to normalize stock code for ID {}: {}", stock.getStockId(), e.getMessage());
                }
            }
        }
        log.info("Completed migration: Normalized {} stock codes.", count);
    }

    @Transactional
    public void updateStockCodeNative(Long stockId, String newCode) {
        overseasStockRepository.updateStockCode(stockId, newCode);
    }
}
