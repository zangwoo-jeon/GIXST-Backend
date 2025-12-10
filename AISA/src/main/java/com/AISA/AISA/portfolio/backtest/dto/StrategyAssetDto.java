package com.AISA.AISA.portfolio.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class StrategyAssetDto {
    private String symbol; // Stock Code
    private BigDecimal weight; // Percentage (e.g., 60 for 60%)
}
