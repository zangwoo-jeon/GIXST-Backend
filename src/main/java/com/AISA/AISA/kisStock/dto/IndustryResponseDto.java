package com.AISA.AISA.kisStock.dto;

import lombok.Builder;
import lombok.Getter;
import java.util.List;

@Getter
@Builder
public class IndustryResponseDto {
    private String stockCode;
    private String stockName;
    private List<Classification> classifications;

    @Getter
    @Builder
    public static class Classification {
        private String industryCode;
        private String industryName;
        private String subIndustryCode;
        private String subIndustryName;
        private boolean isPrimary;
    }
}
