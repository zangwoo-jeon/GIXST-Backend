package com.AISA.AISA.portfolio.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class StrategyBacktestRequestDto {
    private String strategyName;
    private List<StrategyAssetDto> assets;
}
