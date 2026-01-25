package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockIndustry;
import com.AISA.AISA.kisStock.Entity.stock.StockMarketCap;
import com.AISA.AISA.kisStock.repository.StockMarketCapRepository;
import com.AISA.AISA.kisStock.repository.StockRepository;
import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.kisStock.exception.KisApiErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CompetitorAnalysisService {

    private final StockRepository stockRepository;
    private final StockMarketCapRepository stockMarketCapRepository;
    private final IndustryCategorizationService industryCategorizationService;

    @Transactional
    public List<Stock> findCompetitors(String stockCode) {
        // Ensure Categorized (Lazy Loading)
        industryCategorizationService.categorizeStock(stockCode);

        Stock targetStock = stockRepository.findByStockCode(stockCode)
                .orElseThrow(() -> new BusinessException(KisApiErrorCode.STOCK_NOT_FOUND));

        if (targetStock.getStockType() != Stock.StockType.DOMESTIC) {
            throw new BusinessException(KisApiErrorCode.INVALID_STOCK_TYPE);
        }

        List<StockIndustry> industries = targetStock.getStockIndustries();

        if (industries == null || industries.isEmpty()) {
            log.warn("Stock {} has no industry categorization. Skipping competitor analysis.", stockCode);
            return Collections.emptyList();
        }

        // Use the primary industry (first one) or best fit for comparison
        // Currently logic: Try to match ANY of the sub-industries.
        // For simplicity: Use the first one (Primary)
        StockIndustry primary = industries.get(0);
        String subCode = primary.getSubIndustry().getCode();
        String indCode = primary.getSubIndustry().getIndustry().getCode();

        // 1. Find candidates (Same SubIndustry first)
        List<Stock> candidates = new ArrayList<>(
                stockRepository.findBySubIndustryCodeAndStockCodeNot(subCode, stockCode));

        // If too few candidates in SubIndustry, widen to Industry
        if (candidates.size() < 5) {
            List<Stock> industryPeers = stockRepository.findByIndustryCodeAndStockCodeNot(indCode, stockCode);
            // Merge without duplicates
            Set<String> existingCodes = candidates.stream().map(Stock::getStockCode).collect(Collectors.toSet());
            for (Stock p : industryPeers) {
                if (!existingCodes.contains(p.getStockCode())) {
                    candidates.add(p);
                }
            }
        }

        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. Fetch Market Caps
        List<StockMarketCap> candidateCaps = stockMarketCapRepository.findByStockIn(candidates);
        Map<String, BigDecimal> capMap = candidateCaps.stream()
                .collect(Collectors.toMap(mc -> mc.getStock().getStockCode(), StockMarketCap::getMarketCap));

        BigDecimal targetCap = stockMarketCapRepository.findByStock(targetStock)
                .map(StockMarketCap::getMarketCap)
                .orElse(BigDecimal.ZERO);

        // 3. Sort by Market Cap Proximity
        candidates.sort((s1, s2) -> {
            BigDecimal c1 = capMap.getOrDefault(s1.getStockCode(), BigDecimal.ZERO);
            BigDecimal c2 = capMap.getOrDefault(s2.getStockCode(), BigDecimal.ZERO);

            if (c1.compareTo(BigDecimal.ZERO) == 0)
                return 1;
            if (c2.compareTo(BigDecimal.ZERO) == 0)
                return -1;

            BigDecimal diff1 = c1.subtract(targetCap).abs();
            BigDecimal diff2 = c2.subtract(targetCap).abs();

            return diff1.compareTo(diff2);
        });

        // 4. Return Top 5
        return candidates.stream().limit(5).collect(Collectors.toList());
    }
}
