package com.AISA.AISA.kisStock.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class StockSearchResponseDto {
    private String stockCode;
    private String stockName;
    private String marketName;
    private String marketCap; // 시가총액 (문자열 포맷팅 용)
}
