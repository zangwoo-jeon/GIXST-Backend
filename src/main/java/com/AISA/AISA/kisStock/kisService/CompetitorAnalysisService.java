package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockMarketCap;
import com.AISA.AISA.kisStock.enums.Industry;
import com.AISA.AISA.kisStock.enums.SubIndustry;
import com.AISA.AISA.kisStock.repository.StockMarketCapRepository;
import com.AISA.AISA.kisStock.repository.StockRepository;
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
    private final IndustryCategorizationService industryCategorizationService; // Inject

    @Transactional // Allow Write
    public List<Stock> findCompetitors(String stockCode) {
        // Ensure Categorized (Lazy Loading)
        industryCategorizationService.categorizeStock(stockCode);

        Stock targetStock = stockRepository.findByStockCode(stockCode)
                .orElseThrow(() -> new IllegalArgumentException("Stock not found: " + stockCode));

        Industry industry = targetStock.getIndustry();
        SubIndustry subIndustry = targetStock.getSubIndustry();

        if (industry == null || subIndustry == null) {
            log.warn("Stock {} has no industry categorization. Skipping competitor analysis.", stockCode);
            return Collections.emptyList();
        }

        // 1. Find candidates (Same SubIndustry first, then Industry if needed)
        List<Stock> candidates = stockRepository.findBySubIndustryAndStockCodeNot(subIndustry, stockCode);

        // If too few candidates in SubIndustry, widen to Industry
        if (candidates.size() < 5) {
            List<Stock> industryPeers = stockRepository.findByIndustryAndStockCodeNot(industry, stockCode);
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

        // 3. Sort by Market Cap Proximity (Closest Market Cap first)
        // If Market Cap is missing (0), put it at the end.
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
