package com.AISA.AISA.kisStock.dto.InvestorTrend;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccumulatedInvestorTrendDto {
    private BigDecimal personalNetBuyAmount;
    private BigDecimal foreignerNetBuyAmount;
    private BigDecimal institutionNetBuyAmount;
}
