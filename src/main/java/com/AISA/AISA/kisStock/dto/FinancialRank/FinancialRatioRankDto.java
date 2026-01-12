package com.AISA.AISA.kisStock.dto.FinancialRank;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter; // Added Setter
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class FinancialRatioRankDto {
    private List<FinancialRatioEntry> ranks;

    @Getter
    @Setter // Added Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @ToString
    public static class FinancialRatioEntry {
        private String rank;
        private String stockCode;
        private String stockName;
        private String stacYymm;

        // 주요 비율
        private String roe;
        private String eps;
        private String debtRatio;
        private String reserveRatio;

        // 추가 정보
        private String salesGrowth;
        private String operatingProfitGrowth;
        private String netIncomeGrowth;

        // 가치 투자 지표
        private String per;
        private String pbr;
        private String psr;
    }
}
