package com.AISA.AISA.kisStock.dto.InvestorTrend;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class StockInvestorDailyDto {
    private String date; // YYYY-MM-DD
    private String foreignerNetBuyAmount;
    private String institutionNetBuyAmount;
    private String personalNetBuyAmount;
    private String etcCorporateNetBuyAmount;

    private String foreignerNetBuyQuantity; // New
    private String institutionNetBuyQuantity; // New
    private String personalNetBuyQuantity; // New
}
