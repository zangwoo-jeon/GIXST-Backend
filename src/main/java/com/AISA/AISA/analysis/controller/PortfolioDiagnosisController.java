package com.AISA.AISA.analysis.controller;

import com.AISA.AISA.analysis.dto.DiagnosisResultDto;
import com.AISA.AISA.analysis.dto.RollingCorrelationDto;
import com.AISA.AISA.analysis.service.PortfolioDiagnosisService;
import com.AISA.AISA.global.response.SuccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
@Tag(name = "포트폴리오 진단 API", description = "포트폴리오 종합 진단 및 조언")
public class PortfolioDiagnosisController {

    private final PortfolioDiagnosisService portfolioDiagnosisService;

    @GetMapping("/diagnosis/{portfolioId}")
    @Operation(summary = "포트폴리오 종합 진단", description = "포트폴리오의 시장 민감도(Beta)와 상관관계를 분석하여 투자 성향을 진단하고 조언을 제공합니다.")
    public ResponseEntity<SuccessResponse<DiagnosisResultDto>> diagnosePortfolio(
            @PathVariable UUID portfolioId) {
        DiagnosisResultDto result = portfolioDiagnosisService.diagnosePortfolio(portfolioId);
        return ResponseEntity.ok(new SuccessResponse<>(true, "포트폴리오 진단 성공", result));
    }

    @GetMapping("/diagnosis/{portfolioId}/rolling")
    @Operation(summary = "포트폴리오 시장 민감도 추이 (롤링 상관계수)", description = "포트폴리오와 벤치마크(시장 지수) 간의 롤링 상관계수를 통해 시장 민감도 변화를 분석합니다.")
    public ResponseEntity<SuccessResponse<RollingCorrelationDto>> getPortfolioRollingCorrelation(
            @PathVariable UUID portfolioId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "INDEX") String benchmarkType,
            @RequestParam(defaultValue = "KOSPI") String benchmarkCode,
            @RequestParam(defaultValue = "90") int windowSize) {

        if (endDate == null) {
            endDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        }
        if (startDate == null) {
            startDate = LocalDate.parse(endDate, DateTimeFormatter.ofPattern("yyyyMMdd"))
                    .minusYears(3)
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        }
        RollingCorrelationDto result = portfolioDiagnosisService
                .getPortfolioRollingCorrelation(
                        portfolioId, benchmarkType, benchmarkCode, startDate, endDate, windowSize);
        return ResponseEntity.ok(new SuccessResponse<>(true, "포트폴리오 롤링 상관계수 분석 성공", result));
    }
}
