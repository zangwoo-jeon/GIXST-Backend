package com.AISA.AISA.kisStock.dto;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.enums.MarketType;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class StockSearchResponseDto {
    private String stockCode;
    private String stockName;
    private MarketType marketName;
    private Stock.StockType stockType;
    private String marketCap; // 시가총액 (문자열 포맷팅 용)

    // 실시간 가격 정보 (Rank 조회 시 포함됨)
    private String currentPrice;
    private String priceChange; // 전일 대비
    private String changeRate; // 등락률
}
