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
     * 장중 갱신 (10시, 13시, 16시): PER, PBR, CAPE, Yield Gap 등 지수/금리 기반 지표만 갱신.
     * StockDailyData에 당일 데이터가 없으므로 breadth(상승/하락 종목 수) 계산을 건너뜁니다.
     */
    @Scheduled(cron = "0 0 10,13,16 * * MON-FRI")
    public void refreshDomesticValuationIntraday() {
        log.info("Starting scheduled intraday market valuation refresh (without breadth)...");
        try {
            refreshMarket(MarketType.KOSPI, false);
            refreshMarket(MarketType.KOSDAQ, false);
            log.info("Successfully completed intraday market valuation refresh.");
        } catch (Exception e) {
            log.error("Failed to refresh intraday market valuation: {}", e.getMessage(), e);
        }
    }

    /**
     * 장 마감 후 전체 갱신: breadth 포함 전체 지표 갱신.
     * StockScheduler에서 investor trend 저장 완료 후 호출됩니다.
     */
    public void refreshDomesticValuationFull() {
        log.info("Starting scheduled full market valuation refresh (with breadth)...");
        try {
            refreshMarket(MarketType.KOSPI, true);
            refreshMarket(MarketType.KOSDAQ, true);
            log.info("Successfully completed full market valuation refresh.");
        } catch (Exception e) {
            log.error("Failed to refresh full market valuation: {}", e.getMessage(), e);
        }
    }

    private void refreshMarket(MarketType market, boolean includeBreadth) {
        log.info("Refreshing {} valuation (includeBreadth={})...", market, includeBreadth);
        marketValuationService.evictMarketValuationCache(market);
        marketValuationService.calculateMarketValuationWithOptions(market, includeBreadth);
    }
}
