package com.AISA.AISA.kisStock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MarketAccumulatedTrendDto {
    private String marketCode;
    private String period; // 1w, 1m, 3m, 1y
    private String startDate;
    private String endDate;

    // Accumulated Net Buy amounts (Million KRW)
    private BigDecimal personalNetBuy;
    private BigDecimal foreignerNetBuy;
    private BigDecimal institutionNetBuy;

    // Detailed Institution
    private BigDecimal securitiesNetBuy;
    private BigDecimal investmentTrustNetBuy;
    private BigDecimal privateFundNetBuy;
    private BigDecimal bankNetBuy;
    private BigDecimal insuranceNetBuy;
    private BigDecimal merchantBankNetBuy;
    private BigDecimal pensionFundNetBuy;
    private BigDecimal etcCorporateNetBuy;
    private BigDecimal etcNetBuy;
}
