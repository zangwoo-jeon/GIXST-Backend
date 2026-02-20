package com.AISA.AISA.analysis.controller;

import com.AISA.AISA.analysis.dto.OverseasMomentumAnalysisDto;
import com.AISA.AISA.analysis.dto.OverseasQualityValuationDto.QualityReportResponse;
import com.AISA.AISA.analysis.service.OverseasQualityAnalysisService;
import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.analysis.service.OverseasShortTermAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/analysis/overseas/quality")
@RequiredArgsConstructor
@Tag(name = "해외 주식 품질 분석 API", description = "해외 주식의 기업 질(Quality) 및 장기 자본 효율성 분석")
public class OverseasQualityAnalysisController {

    private final OverseasQualityAnalysisService qualityAnalysisService;
    private final OverseasShortTermAnalysisService shortTermAnalysisService;

    @GetMapping("/{stockCode}/report/long-term")
    @Operation(summary = "해외 주식 장기 품질 분석 리포트", description = "기업의 구조적 경쟁력, 자본 효율성 및 재투자 가능성을 중심으로 한 장기 투자 리포트를 생성합니다.")
    public ResponseEntity<SuccessResponse<QualityReportResponse>> getQualityReport(
            @PathVariable String stockCode,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        return ResponseEntity.ok(new SuccessResponse<>(true, "해외 주식 장기 품질 분석 리포트 생성 성공",
                qualityAnalysisService.calculateQualityAnalysis(stockCode, forceRefresh)));
    }

    @GetMapping("/{stockCode}/report/short-term")
    @Operation(summary = "해외 주식 단기 모멘텀 분석 리포트", description = "해외 주식 단기 모멘텀 리포트를 생성합니다.")
    public ResponseEntity<SuccessResponse<OverseasMomentumAnalysisDto>> getShortTermReport(
            @PathVariable String stockCode,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return ResponseEntity.ok(new SuccessResponse<>(true, "해외 주식 단기 모멘텀 분석 리포트 생성 성공",
                shortTermAnalysisService.analyze(stockCode, forceRefresh)));
    }

    @DeleteMapping("/cache/long-term")
    @Operation(summary = "해외 주식 장기 품질 분석 리포트 캐시 초기화", description = "저장된 모든 해외 종목의 장기 품질 분석 리포트를 초기화합니다.")
    public ResponseEntity<SuccessResponse<Void>> clearLongTermCache() {
        qualityAnalysisService.clearQualityReports();
        return ResponseEntity.ok(new SuccessResponse<>(true, "해외 주식 장기 품질 분석 리포트 캐시 초기화 성공", null));
    }

    @DeleteMapping("/cache/short-term")
    @Operation(summary = "해외 주식 단기 모멘텀 분석 리포트 캐시 초기화", description = "저장된 모든 해외 종목의 단기 모멘텀 분석 리포트를 초기화합니다.")
    public ResponseEntity<SuccessResponse<Void>> clearShortTermCache() {
        shortTermAnalysisService.clearShortTermReports();
        return ResponseEntity.ok(new SuccessResponse<>(true, "해외 주식 단기 모멘텀 분석 리포트 캐시 초기화 성공", null));
    }
}
