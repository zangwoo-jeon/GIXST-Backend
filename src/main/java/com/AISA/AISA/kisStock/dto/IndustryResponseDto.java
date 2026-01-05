package com.AISA.AISA.kisStock.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class IndustryResponseDto {
    private String stockCode;
    private String stockName;
    private String industry;
    private String subIndustry;
}
