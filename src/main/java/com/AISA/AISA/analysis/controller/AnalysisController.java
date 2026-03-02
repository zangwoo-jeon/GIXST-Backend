package com.AISA.AISA.analysis.controller;

import com.AISA.AISA.analysis.dto.CorrelationResultDto;
import com.AISA.AISA.analysis.dto.RollingCorrelationDto;
import com.AISA.AISA.analysis.service.AnalysisService;
import com.AISA.AISA.global.response.SuccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.AISA.AISA.analysis.service.ValuationService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
@Tag(name = "자산 분석 API", description = "포트폴리오 및 자산 상관관계 분석")
public class AnalysisController {

        private final AnalysisService analysisService;
        private final ValuationService valuationService;

        @GetMapping("/correlation")
        @Operation(summary = "자산 간 상관관계 분석", description = "두 자산 간의 피어슨 상관계수를 계산합니다.<br>" +
                        "**지원 자산 타입 및 예시:**<br>" +
                        "- **STOCK**: 주식 (예: 005930, 000660)<br>" +
                        "- **INDEX**: 지수 (예: KOSPI, KOSDAQ, NASDAQ, SP500, NIKKEI, HANGSENG, EUROSTOXX50)<br>" +
                        "- **EXCHANGE**: 환율 (예: USD, JPY, EUR, HKD, CNY)<br>" +
                        "- **BASE_RATE**: 기준금리 (예: BASE_RATE)<br>" +
                        "- **BOND**: 국채 금리 (예: KR_3Y, US_10Y)<br>" +
                        "- **CPI**: 소비자물가지수 (예: CPI)<br>" +
                        "- **M2**: M2 통화량 (예: M2)")
        public ResponseEntity<SuccessResponse<CorrelationResultDto>> getCorrelation(
                        @RequestParam String asset1Type,
                        @RequestParam String asset1Code,
                        @RequestParam String asset2Type,
                        @RequestParam String asset2Code,
                        @RequestParam String startDate,
                        @RequestParam String endDate,
                        @RequestParam(defaultValue = "AUTO") String method) {
                CorrelationResultDto result = analysisService.calculateCorrelation(asset1Type, asset1Code, asset2Type,
                                asset2Code, startDate, endDate, method);
                return ResponseEntity.ok(new SuccessResponse<>(true, "상관관계 분석 성공", result));
        }

        @GetMapping("/correlation/rolling")
        @Operation(summary = "롤링 상관계수 분석", description = "두 자산 간의 롤링 상관계수(이동 상관계수) 추이를 조회합니다.")
        public ResponseEntity<SuccessResponse<RollingCorrelationDto>> getRollingCorrelation(
                        @RequestParam String asset1Type,
                        @RequestParam String asset1Code,
                        @RequestParam String asset2Type,
                        @RequestParam String asset2Code,
                        @RequestParam String startDate,
                        @RequestParam String endDate,
                        @RequestParam(defaultValue = "90") int windowSize) {
                RollingCorrelationDto result = analysisService.calculateRollingCorrelation(asset1Type, asset1Code,
                                asset2Type,
                                asset2Code, startDate, endDate, windowSize);
                return ResponseEntity.ok(new SuccessResponse<>(true, "롤링 상관계수 분석 성공", result));
        }

        @GetMapping("/valuation/{stockCode}/static-report")
        @Operation(summary = "AI 기업 개요 및 리스크 분석 (정적 캐싱: DB 1년, Redis 7일)", description = "기업 개요, 미래 성장 동력, 리스크 요인을 조회합니다. (refresh=true 시 강제 갱신)")
        public ResponseEntity<SuccessResponse<String>> getStaticValuationReport(
                        @PathVariable String stockCode,
                        @RequestParam(defaultValue = "false") boolean refresh) {
                return ResponseEntity.ok(new SuccessResponse<>(true, "기업 개요 및 리스크 분석 조회 성공",
                                valuationService.getStaticAnalysis(stockCode, refresh)));
        }

}
