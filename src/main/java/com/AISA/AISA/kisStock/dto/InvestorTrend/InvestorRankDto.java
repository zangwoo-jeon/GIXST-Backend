package com.AISA.AISA.kisStock.dto.InvestorTrend;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class InvestorRankDto {
    private String rank;
    private String stockCode;
    private String stockName;
    private String personalNetBuyAmount;
    private String foreignerNetBuyAmount;
    private String institutionNetBuyAmount;
    private Stock.StockType stockType;
}
