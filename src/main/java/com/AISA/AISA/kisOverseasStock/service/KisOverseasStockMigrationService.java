package com.AISA.AISA.kisOverseasStock.service;

import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockRepository;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class KisOverseasStockMigrationService {

    private final KisOverseasStockRepository overseasStockRepository;
    private final KisOverseasStockService kisOverseasStockService;

    /**
     * 차트 데이터가 0건인 종목들을 찾아 과거 데이터를 복구합니다.
     * 1회성 마이그레이션용 서비스입니다.
     */
    public void recoverMissingHistoricalData(String startDateStr) {
        log.info("Starting migration: Recovering missing overseas stock historical data from {}", startDateStr);

        List<Stock> missingStocks = overseasStockRepository.findStocksWithNoDailyData();
        log.info("Found {} stocks with no daily data.", missingStocks.size());

        for (Stock stock : missingStocks) {
            try {
                log.info("Recovering data for stock: {} ({}) - ID: {}",
                        stock.getStockName(), stock.getStockCode(), stock.getStockId());

                // 기존 서비스의 티커/ID 기반 복구 로직 재사용
                kisOverseasStockService.fetchAndSaveHistoricalOverseasStockData(
                        stock.getStockId().toString(), startDateStr);

                // API Rate Limit 방지를 위한 지연
                Thread.sleep(500);
            } catch (Exception e) {
                log.error("Failed to recover data for stock: {} ({}) - {}",
                        stock.getStockName(), stock.getStockCode(), e.getMessage());
            }
        }

        log.info("Completed migration: Overseas stock historical data recovery finished.");
    }
}
