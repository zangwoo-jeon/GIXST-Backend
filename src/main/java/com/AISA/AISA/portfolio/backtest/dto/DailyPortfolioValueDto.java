package com.AISA.AISA.portfolio.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyPortfolioValueDto {
    private String date;
    private BigDecimal totalValue;
    private Double dailyReturnRate;
    private BigDecimal adjustedValue; // New field for correlation analysis (pure performance index)
    private BigDecimal balance; // Cash balance (e.g., for stocks not yet listed)
}
