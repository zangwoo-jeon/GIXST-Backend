package com.AISA.AISA.portfolio.backtest.controller;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.portfolio.backtest.dto.BacktestCompareRequestDto;
import com.AISA.AISA.portfolio.backtest.dto.BacktestCompareResultDto;
import com.AISA.AISA.portfolio.backtest.dto.BacktestResultDto;
import com.AISA.AISA.portfolio.backtest.dto.MultiStrategyBacktestRequestDto;
import com.AISA.AISA.portfolio.backtest.dto.MultiStrategyBacktestResultDto;
import com.AISA.AISA.portfolio.backtest.service.BacktestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
@Tag(name = "포트폴리오 백테스트 API", description = "포트폴리오 과거 수익률 시뮬레이션 관련 API")
public class BacktestController {

    private final BacktestService backtestService;

    @GetMapping("/{portId}")
    @Operation(summary = "포트폴리오 백테스트 실행", description = "특정 포트폴리오의 과거 기간 수익률을 시뮬레이션합니다.")
    public ResponseEntity<SuccessResponse<BacktestResultDto>> runBacktest(
            @PathVariable UUID portId,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(required = false) java.math.BigDecimal initialCapital) {

        BacktestResultDto result = backtestService.calculatePortfolioBacktest(portId, startDate, endDate,
                initialCapital);
        return ResponseEntity.ok(new SuccessResponse<>(true, "백테스트 실행 성공", result));
    }

    @PostMapping("/compare")
    @Operation(summary = "포트폴리오 vs 비교 그룹 백테스트", description = "포트폴리오와 임의의 비교 그룹 간의 과거 수익률을 비교 시뮬레이션합니다.")
    public ResponseEntity<SuccessResponse<BacktestCompareResultDto>> compareBacktest(
            @RequestBody BacktestCompareRequestDto requestDto) {
        BacktestCompareResultDto result = backtestService.comparePortfolioBacktest(requestDto);
        return ResponseEntity.ok(new SuccessResponse<>(true, "비교 백테스트 실행 성공", result));
    }

    @PostMapping("/strategies")
    @Operation(summary = "다중 전략 시뮬레이션 백테스트", description = "여러 전략(종목 구성 및 비중)에 대한 과거 수익률을 시뮬레이션합니다. 초기 자본금과 비중을 기반으로 수량을 계산합니다.")
    public ResponseEntity<SuccessResponse<MultiStrategyBacktestResultDto>> runMultiStrategyBacktest(
            @RequestBody MultiStrategyBacktestRequestDto requestDto) {
        MultiStrategyBacktestResultDto result = backtestService.calculateMultiStrategyBacktest(requestDto);
        return ResponseEntity.ok(new SuccessResponse<>(true, "다중 전략 백테스트 실행 성공", result));
    }
}
