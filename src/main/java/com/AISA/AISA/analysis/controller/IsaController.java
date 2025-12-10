package com.AISA.AISA.analysis.controller;

import com.AISA.AISA.analysis.dto.IsaDTOs.IsaBacktestRequest;
import com.AISA.AISA.analysis.dto.IsaDTOs.IsaBacktestResponse;
import com.AISA.AISA.analysis.dto.IsaDTOs.IsaCalculateRequest;
import com.AISA.AISA.analysis.dto.IsaDTOs.IsaCalculateResponse;
import com.AISA.AISA.analysis.service.IsaService;
import com.AISA.AISA.global.response.SuccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analysis/isa")
@RequiredArgsConstructor
@Tag(name = "분석/시뮬레이션 API", description = "투자 분석 및 시뮬레이션 관련 API")
public class IsaController {

    private final IsaService isaService;

    @PostMapping("/calculate")
    @Operation(summary = "ISA 절세 효과 계산 (단순)", description = "예상 배당 수익금과 계좌 유형을 입력받아 ISA 계좌 이용 시 절세 효과를 계산합니다.")
    public ResponseEntity<SuccessResponse<IsaCalculateResponse>> calculateIsaBenefits(
            @RequestBody IsaCalculateRequest request) {
        IsaCalculateResponse response = isaService.calculateTaxBenefits(request);
        return ResponseEntity.ok(new SuccessResponse<>(true, "ISA 절세 효과 계산 성공", response));
    }

    @PostMapping("/calculate-backtest")
    @Operation(summary = "ISA 절세 효과 계산 (백테스트)", description = "포트폴리오와 기간을 입력받아 백테스트를 수행하고, 손익 통산 및 과세 차이를 반영하여 ISA 절세 효과를 계산합니다.")
    public ResponseEntity<SuccessResponse<IsaBacktestResponse>> calculateIsaBenefitsByBacktest(
            @RequestBody IsaBacktestRequest request) {
        IsaBacktestResponse response = isaService.calculateIsaBenefitsByBacktest(request);
        return ResponseEntity.ok(new SuccessResponse<>(true, "ISA 백테스트 기반 절세 효과 계산 성공", response));
    }
}
