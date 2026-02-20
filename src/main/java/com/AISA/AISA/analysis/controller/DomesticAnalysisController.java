package com.AISA.AISA.analysis.controller;

import com.AISA.AISA.analysis.dto.DomesticMomentumAnalysisDto;
import com.AISA.AISA.analysis.dto.DomesticQualityValuationDto;
import com.AISA.AISA.analysis.service.DomesticQualityAnalysisService;
import com.AISA.AISA.analysis.service.DomesticShortTermAnalysisService;
import com.AISA.AISA.global.response.SuccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/analysis/domestic/quality")
@RequiredArgsConstructor
@Tag(name = "Domestic Analysis", description = "국내 주식 분석 관련 API")
public class DomesticAnalysisController {

    private final DomesticShortTermAnalysisService shortTermAnalysisService;
    private final DomesticQualityAnalysisService qualityAnalysisService;

    @GetMapping("/{stockCode}/report/short-term")
    @Operation(summary = "국내 주식 단기 모멘텀 분석 리포트 조회", description = "RSI, Stochastic, MACD 및 수급 동향을 결합한 단기 모멘텀 분석 결과를 반환합니다.")
    public ResponseEntity<SuccessResponse<DomesticMomentumAnalysisDto>> getShortTermReport(
            @PathVariable String stockCode,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        DomesticMomentumAnalysisDto result = shortTermAnalysisService.analyze(stockCode, forceRefresh);
        return ResponseEntity.ok(new SuccessResponse<>(true, "국내 주식 단기 모멘텀 분석 조회 성공", result));
    }

    @DeleteMapping("/cache/short-term")
    @Operation(summary = "국내 주식 단기 모멘텀 분석 리포트 캐시 초기화", description = "저장된 모든 국내 종목의 단기 모멘텀 분석 리포트를 초기화합니다.")
    public ResponseEntity<SuccessResponse<Void>> clearShortTermCache() {
        shortTermAnalysisService.clearShortTermReports();
        return ResponseEntity.ok(new SuccessResponse<>(true, "국내 주식 단기 모멘텀 분석 리포트 캐시 초기화 성공", null));
    }

    @GetMapping("/{stockCode}/report/long-term")
    @Operation(summary = "국내 주식 장기 품질 분석 리포트 조회", description = "ROE, 영업이익 안정성, 성장성, 재무안정성, 배당 안정성 및 역사적 밸류에이션 Percentile 기반의 장기 품질 분석 결과를 반환합니다.")
    public ResponseEntity<SuccessResponse<DomesticQualityValuationDto.QualityReportResponse>> getLongTermReport(
            @PathVariable String stockCode,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        DomesticQualityValuationDto.QualityReportResponse result = qualityAnalysisService.analyze(stockCode, forceRefresh);
        return ResponseEntity.ok(new SuccessResponse<>(true, "국내 주식 장기 품질 분석 조회 성공", result));
    }

    @DeleteMapping("/cache/long-term")
    @Operation(summary = "국내 주식 장기 품질 분석 리포트 캐시 초기화", description = "저장된 모든 국내 종목의 장기 품질 분석 리포트를 초기화합니다.")
    public ResponseEntity<SuccessResponse<Void>> clearLongTermCache() {
        qualityAnalysisService.clearReports();
        return ResponseEntity.ok(new SuccessResponse<>(true, "국내 주식 장기 품질 분석 리포트 캐시 초기화 성공", null));
    }
}
