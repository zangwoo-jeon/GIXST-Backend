package com.AISA.AISA.analysis.controller;

import com.AISA.AISA.analysis.dto.MarketValuationDto;
import com.AISA.AISA.analysis.service.MarketValuationService;
import com.AISA.AISA.kisStock.enums.MarketType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "시장 분석 API", description = "국내/해외 시장의 밸류에이션 및 과열 지수 분석 정보를 제공합니다.")
public class MarketAnalysisController {

    private final MarketValuationService marketValuationService;

    @GetMapping("/valuation")
    @Operation(summary = "시장 밸류에이션 조회", description = "지정된 시장(KOSPI, KOSDAQ)의 CAPE, Yield Gap 등 밸류에이션 지표와 AI 전략 요합을 조회합니다.")
    public ResponseEntity<MarketValuationDto> getMarketValuation(
            @Parameter(description = "시장 구분 (KOSPI, KOSDAQ)", example = "KOSPI") @RequestParam MarketType market) {
        if (market != MarketType.KOSPI && market != MarketType.KOSDAQ) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(marketValuationService.calculateMarketValuation(market));
    }

    @PostMapping("/valuation/refresh")
    @Operation(summary = "시장 밸류에이션 캐시 갱신", description = "시장 데이터를 기반으로 밸류에이션 결과를 재계산하고 캐시를 갱신합니다.")
    public ResponseEntity<MarketValuationDto> refreshMarketValuation(
            @Parameter(description = "시장 구분 (KOSPI, KOSDAQ)", example = "KOSPI") @RequestParam MarketType market) {
        if (market != MarketType.KOSPI && market != MarketType.KOSDAQ) {
            return ResponseEntity.badRequest().build();
        }
        marketValuationService.evictMarketValuationCache(market);
        return ResponseEntity.ok(marketValuationService.calculateMarketValuation(market));
    }
}
