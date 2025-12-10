package com.AISA.AISA.portfolio.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestCompareResultDto {
    private BacktestResultDto targetPortfolioResult;
    private BacktestResultDto comparisonGroupResult;
}
