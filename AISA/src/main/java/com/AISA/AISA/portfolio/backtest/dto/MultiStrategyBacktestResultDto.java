package com.AISA.AISA.portfolio.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiStrategyBacktestResultDto {
    private List<StrategyBacktestResultDto> results;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StrategyBacktestResultDto {
        private String strategyName;
        private BacktestResultDto result;
    }
}
