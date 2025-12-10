package com.AISA.AISA.portfolio.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class BacktestCompareRequestDto {
    private UUID portId;
    private String startDate;
    private String endDate;
    private List<ComparisonStockDto> comparisonStocks;
}
