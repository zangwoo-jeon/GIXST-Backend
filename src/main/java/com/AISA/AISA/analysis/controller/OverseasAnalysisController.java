package com.AISA.AISA.analysis.controller;

import com.AISA.AISA.analysis.dto.OverseasValuationDto;
import com.AISA.AISA.analysis.service.OverseasValuationService;
import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisOverseasStock.entity.OverseasStockTradingMultiple;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/analysis/overseas")
@RequiredArgsConstructor
@Tag(name = "해외 주식 분석 API", description = "해외 주식 적정 주가 및 AI 진단")
public class OverseasAnalysisController {

        private final OverseasValuationService overseasValuationService;
        private final com.AISA.AISA.kisOverseasStock.service.OverseasTradingMultipleService overseasTradingMultipleService;

        @PostMapping("/valuation/{stockCode}")
        @Operation(summary = "해외 주식 적정 주가 진단 (S-RIM/PER/PBR)", description = "해외 주식의 적정 주가를 S-RIM, PER, PBR 모델로 분석합니다. (주주환원율 데이터 반영)")
        public ResponseEntity<SuccessResponse<OverseasValuationDto.Response>> valuation(
                        @PathVariable String stockCode,
                        @RequestBody(required = false) OverseasValuationDto.Request request) {

                return ResponseEntity.ok(new SuccessResponse<>(true, "해외 주식 적정 주가 진단 성공",
                                overseasValuationService.calculateValuation(stockCode, request)));
        }

        @PostMapping("/valuation/{stockCode}/report")
        @Operation(summary = "해외 주식 AI 적정 주가 분석 리포트", description = "해외 주식 진단 결과와 함께 AI가 분석한 투자 조언 리포트를 제공합니다. (자사주 매입, 총 주주환원율 집중 분석)")
        public ResponseEntity<SuccessResponse<OverseasValuationDto.Response>> valuationReport(
                        @PathVariable String stockCode,
                        @RequestBody(required = false) OverseasValuationDto.Request request) {

                return ResponseEntity.ok(new SuccessResponse<>(true, "해외 주식 AI 적정 주가 분석 리포트 생성 성공",
                                overseasValuationService.calculateValuationWithAi(stockCode, request)));
        }

        @GetMapping("/valuation/{stockCode}/optimized-report")
        @Operation(summary = "해외 주식 AI 적정 주가 분석 리포트 (최적화/캐싱)", description = "표준화된(NEUTRAL) 설정으로 AI 리포트를 생성하거나 캐시된 결과를 즉시 반환합니다. (속도 빠름, 캐싱 적용)")
        public ResponseEntity<SuccessResponse<OverseasValuationDto.Response>> optimizedValuationReport(
                        @PathVariable String stockCode,
                        @RequestParam(defaultValue = "false") boolean refresh) {
                return ResponseEntity.ok(new SuccessResponse<>(true, "해외 주식 AI 적정 주가 분석 리포트 조회 성공 (최적화)",
                                overseasValuationService.getStandardizedValuationReport(stockCode, refresh)));
        }

        @GetMapping("/multiples/{stockCode}")
        @Operation(summary = "해외 주식 투자 지표 (PEG, EV/EBITDA)", description = "Yahoo Finance 기반의 PEG Ratio 및 EV/EBITDA 지표를 조회합니다.")
        public ResponseEntity<SuccessResponse<OverseasStockTradingMultiple>> getTradingMultiples(
                        @PathVariable String stockCode) {
                return ResponseEntity.ok(new SuccessResponse<>(true, "투자 지표 조회 성공",
                                overseasTradingMultipleService.getTradingMultiples(stockCode).orElse(null)));
        }
}
