package com.AISA.AISA.portfolio.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class MultiStrategyBacktestRequestDto {
    private BigDecimal initialCapital;
    private String startDate;
    private String endDate;
    private String rebalanceFrequency; // "Monthly", "Quarterly", "Yearly", "None"
    private List<StrategyBacktestRequestDto> strategies;
}
