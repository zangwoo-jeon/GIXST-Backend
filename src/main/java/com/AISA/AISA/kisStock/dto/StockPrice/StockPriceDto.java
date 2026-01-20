package com.AISA.AISA.kisStock.dto.StockPrice;

import com.AISA.AISA.kisStock.enums.MarketType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class StockPriceDto {
    private String stockCode;
    private String stockName;
    private MarketType marketName;
    private String stockPrice;
    private String priceChange;
    private String changeRate;
    private String accumulatedVolume;
    private String openingPrice;
    private String usdPrice;
    private String exchangeRate;
    private String listedSharesCount;
    private String marketCap;
}
