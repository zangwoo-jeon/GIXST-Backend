package com.AISA.AISA.kisStock.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class StockSearchResponseDto {
    private String stockCode;
    private String stockName;
    private String marketName;
}
