package com.AISA.AISA.analysis.controller;

import com.AISA.AISA.analysis.dto.CorrelationResultDto;
import com.AISA.AISA.analysis.dto.RollingCorrelationDto;
import com.AISA.AISA.analysis.service.AnalysisService;
import com.AISA.AISA.global.response.SuccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.AISA.AISA.analysis.dto.ValuationDto;
import com.AISA.AISA.analysis.service.ValuationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
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
                        "- **EXCHANGE**: 환율 (예: FX@KRW)<br>" +
                        "- **BASE_RATE**: 기준금리 (예: BASE_RATE)<br>" +
                        "- **BOND**: 국채 금리 (예: KR_3Y, US_10Y)<br>" +
                        "- **CPI**: 소비자물가지수 (예: CPI)<br>" +
                        "- **M2**: M2 통화량 (예: M2)")
        public ResponseEntity<SuccessResponse<CorrelationResultDto>> getCorrelation(
                        @RequestParam String asset1Type, // STOCK, INDEX, EXCHANGE, BOND, BASE_RATE
                        @RequestParam String asset1Code, // Symbol (e.g., 005930, NASDAQ, FX@KRW, US_10Y)
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

        @PostMapping("/valuation/{stockCode}")
        @Operation(summary = "종목 적정 주가 진단 (S-RIM/PER/PBR)", description = "특정 종목의 적정 주가를 S-RIM, PER, PBR 모델로 분석하고 밴드를 제시합니다. \n\n"
                        + "userPropensity 옵션: CONSERVATIVE (보수적, 10%), NEUTRAL (중립, 8%, 기본값), AGGRESSIVE (공격적, 6%) \n"
                        + "(할인율 커스텀 가능)")
        public ResponseEntity<SuccessResponse<ValuationDto.Response>> valuation(
                        @PathVariable String stockCode,
                        @RequestBody(required = false) ValuationDto.Request request) {

                return ResponseEntity.ok(new SuccessResponse<>(true, "적정 주가 진단 성공",
                                valuationService.calculateValuation(stockCode, request)));
        }

        @PostMapping("/valuation/{stockCode}/report")
        @Operation(summary = "종목 적정 주가 진단 + AI 리포트", description = "종목 진단 결과와 함께 Gemini AI가 분석한 투자 조언 리포트를 제공합니다. (약 3~5초 소요)")
        public ResponseEntity<SuccessResponse<ValuationDto.Response>> valuationReport(
                        @PathVariable String stockCode,
                        @RequestBody(required = false) ValuationDto.Request request) {

                return ResponseEntity.ok(new SuccessResponse<>(true, "AI 적정 주가 분석 리포트 생성 성공",
                                valuationService.calculateValuationWithAi(stockCode, request)));
        }

        @GetMapping("/valuation/{stockCode}/optimized-report")
        @Operation(summary = "AI 적정 주가 분석 리포트 (최적화/캐싱)", description = "표준화된(NEUTRAL) 설정으로 AI 리포트를 생성하거나 캐시된 결과를 즉시 반환합니다. (속도 빠름)")
        public ResponseEntity<SuccessResponse<ValuationDto.Response>> optimizedValuationReport(
                        @PathVariable String stockCode) {
                return ResponseEntity.ok(new SuccessResponse<>(true, "AI 적정 주가 분석 리포트 조회 성공 (최적화)",
                                valuationService.getStandardizedValuationReport(stockCode)));
        }

        @DeleteMapping("/cache")
        @Operation(summary = "AI 분석 캐시 초기화", description = "저장된 모든 종목의 AI 분석 리포트 캐시(DB)를 삭제합니다.")
        public ResponseEntity<SuccessResponse<Void>> clearAiSummaryCache() {
                valuationService.clearAiSummaryCache();
                return ResponseEntity.ok(new SuccessResponse<>(true, "AI 분석 캐시 초기화 성공", null));
        }
}
