package com.AISA.AISA.analysis.scheduler;

import com.AISA.AISA.analysis.service.MarketValuationService;
import com.AISA.AISA.kisStock.enums.MarketType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketValuationScheduler {

    private final MarketValuationService marketValuationService;

    /**
     * 국내 증시(KOSPI, KOSDAQ) 밸류에이션 데이터를 매일 18:00에 갱신합니다.
     * 이 시점은 장 마감 후 투자자별 수급 및 채권 금리 등 모든 데이터 동기화가 완료된 시점입니다.
     */
    @Scheduled(cron = "0 0 18 * * MON-FRI")
    public void refreshDomesticValuation() {
        log.info("Starting scheduled domestic market valuation refresh...");

        try {
            // 1. KOSPI 갱신
            log.info("Refreshing KOSPI valuation...");
            marketValuationService.evictMarketValuationCache(MarketType.KOSPI);
            marketValuationService.calculateMarketValuation(MarketType.KOSPI);

            // 2. KOSDAQ 갱신
            log.info("Refreshing KOSDAQ valuation...");
            marketValuationService.evictMarketValuationCache(MarketType.KOSDAQ);
            marketValuationService.calculateMarketValuation(MarketType.KOSDAQ);

            log.info("Successfully completed domestic market valuation refresh.");
        } catch (Exception e) {
            log.error("Failed to refresh domestic market valuation: {}", e.getMessage(), e);
        }
    }
}
