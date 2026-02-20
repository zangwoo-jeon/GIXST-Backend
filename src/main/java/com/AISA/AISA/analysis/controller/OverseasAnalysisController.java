package com.AISA.AISA.analysis.controller;

import com.AISA.AISA.kisOverseasStock.entity.OverseasStockTradingMultiple;
import com.AISA.AISA.kisOverseasStock.service.OverseasTradingMultipleService;
import com.AISA.AISA.analysis.service.OverseasStockAnalysisService;
import com.AISA.AISA.global.response.SuccessResponse;
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

        private final OverseasTradingMultipleService overseasTradingMultipleService;
        private final OverseasStockAnalysisService overseasStockAnalysisService;

        @GetMapping("/multiples/{stockCode}")
        @Operation(summary = "해외 주식 투자 지표 (PEG, EV/EBITDA)", description = "Yahoo Finance 기반의 PEG Ratio 및 EV/EBITDA 지표를 조회합니다.")
        public ResponseEntity<SuccessResponse<OverseasStockTradingMultiple>> getTradingMultiples(
                        @PathVariable String stockCode) {
                return ResponseEntity.ok(new SuccessResponse<>(true, "투자 지표 조회 성공",
                                overseasTradingMultipleService.getTradingMultiples(stockCode).orElse(null)));
        }

        @GetMapping("/static-analysis/{stockCode}")
        @Operation(summary = "해외 주식 AI 기업 분석 조회", description = "Gemini를 사용하여 기업 개요, 미래 성장 동력, 리스크 요인을 조회합니다. (refresh=true 시 강제 갱신)")
        public ResponseEntity<SuccessResponse<String>> getStaticAnalysis(
                        @PathVariable String stockCode,
                        @RequestParam(defaultValue = "false") boolean refresh) {
                String result = overseasStockAnalysisService.getStaticAnalysis(stockCode, refresh);
                return ResponseEntity.ok(new SuccessResponse<>(true, "해외 주식 AI 기업 분석 조회 성공", result));
        }
}
