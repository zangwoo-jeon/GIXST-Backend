package com.AISA.AISA.analysis.controller;

import com.AISA.AISA.analysis.dto.MarketValuationDto;
import com.AISA.AISA.analysis.service.MarketValuationService;
import com.AISA.AISA.kisStock.enums.MarketType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analysis/market")
@RequiredArgsConstructor
public class MarketAnalysisController {

    private final MarketValuationService marketValuationService;

    @GetMapping("/valuation")
    public ResponseEntity<MarketValuationDto> getMarketValuation(@RequestParam MarketType market) {
        return ResponseEntity.ok(marketValuationService.calculateMarketValuation(market));
    }

    @PostMapping("/valuation/refresh")
    public ResponseEntity<MarketValuationDto> refreshMarketValuation(@RequestParam MarketType market) {
        marketValuationService.evictMarketValuationCache(market);
        return ResponseEntity.ok(marketValuationService.calculateMarketValuation(market));
    }
}
