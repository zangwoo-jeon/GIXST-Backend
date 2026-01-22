package com.AISA.AISA.kisOverseasStock.scheduler;

import com.AISA.AISA.kisOverseasStock.service.KisOverseasStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
@Slf4j
public class KisOverseasStockScheduler {

    private final KisOverseasStockService kisOverseasStockService;

    /**
     * 매일 새벽 5시에 모든 해외 주식의 최근 1개월치 과거 데이터를 업데이트합니다.
     * 미 주식 시장 종료(현지 시간 오후 4시, 한국 시간 기준 오전 5시 또는 6시) 후 데이터를 수집합니다.
     */
    @Scheduled(cron = "0 0 5 * * *")
    public void scheduledUpdateAllOverseasStockData() {
        log.info("Starting scheduled overseas stock historical data update (1 month period)...");

        try {
            // 한 달 전 날짜 계산
            LocalDate startDate = LocalDate.now().minusMonths(1);
            String startDateStr = startDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            log.info("Fetching data starting from: {}", startDateStr);
            kisOverseasStockService.fetchAllOverseasStocksHistoricalData(startDateStr);

            log.info("Completed scheduled overseas stock historical data update.");
        } catch (Exception e) {
            log.error("Error occurred during scheduled overseas stock data update: {}", e.getMessage(), e);
        }
    }
}
