package com.AISA.AISA.portfolio.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestResultDto {
    private UUID portId;
    private String startDate;
    private String endDate;
    private BigDecimal initialValue;
    private BigDecimal finalValue;
    private Double totalReturnRate;
    private Double cagr;
    private Double mdd;
    private List<DailyPortfolioValueDto> dailyValues;
}
