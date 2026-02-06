package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.kisStock.Entity.Index.MarketStatusHistory;
import com.AISA.AISA.kisStock.enums.MarketType;
import com.AISA.AISA.kisStock.repository.MarketStatusHistoryRepository;
import com.AISA.AISA.kisStock.repository.StockDailyDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketStatusHistoryService {

    private final StockDailyDataRepository stockDailyDataRepository;
    private final MarketStatusHistoryRepository marketStatusHistoryRepository;

    @Transactional
    public void calculateAndSaveMarketStatus(LocalDate date, MarketType marketType) {
        // Fetching existing history for update if already exists
        MarketStatusHistory history = marketStatusHistoryRepository.findByMarketNameAndDate(marketType, date)
                .orElse(MarketStatusHistory.builder()
                        .marketName(marketType)
                        .date(date)
                        .build());

        long risingCount = stockDailyDataRepository
                .countByDateAndStock_MarketNameAndStock_IsCommonTrueAndChangeRateGreaterThan(
                        date, marketType, 0.0);
        long fallingCount = stockDailyDataRepository
                .countByDateAndStock_MarketNameAndStock_IsCommonTrueAndChangeRateLessThan(
                        date, marketType, 0.0);
        long unchangedCount = stockDailyDataRepository
                .countByDateAndStock_MarketNameAndStock_IsCommonTrueAndChangeRate(
                        date, marketType, 0.0);

        long totalCount = risingCount + fallingCount + unchangedCount;

        if (totalCount == 0) {
            log.info("No trading data found for {} on {}. Skipping.", marketType, date);
            return;
        }

        history.updateCounts((int) risingCount, (int) fallingCount, (int) unchangedCount);

        marketStatusHistoryRepository.save(history);
        log.info("Saved market status history for {} on {}: Rising={}, Falling={}, Unchanged={}",
                marketType, date, risingCount, fallingCount, unchangedCount);

    }

    @Transactional
    public void generateHistory(LocalDate startDate, LocalDate endDate, MarketType marketType) {
        log.info("Generating market status history from {} to {} for market {}", startDate, endDate, marketType);

        LocalDate currentDate = startDate;
        while (!currentDate.isAfter(endDate)) {
            if (marketType != null) {
                calculateAndSaveMarketStatus(currentDate, marketType);
            } else {
                calculateAndSaveMarketStatus(currentDate, MarketType.KOSPI);
                calculateAndSaveMarketStatus(currentDate, MarketType.KOSDAQ);
            }
            currentDate = currentDate.plusDays(1);
        }
        log.info("Market status history generation completed.");
    }

    @Transactional(readOnly = true)
    public List<MarketStatusHistory> getHistory(MarketType marketName, LocalDate startDate, LocalDate endDate) {
        return marketStatusHistoryRepository.findAllByMarketNameAndDateBetweenOrderByDateAsc(
                marketName, startDate, endDate);
    }
}
