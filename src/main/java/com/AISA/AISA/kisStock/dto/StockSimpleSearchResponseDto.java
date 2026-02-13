package com.AISA.AISA.kisStock.dto;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.enums.MarketType;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class StockSimpleSearchResponseDto {
    private String stockCode;
    private String stockName;
    private MarketType marketName;
    private Stock.StockType stockType;
}
