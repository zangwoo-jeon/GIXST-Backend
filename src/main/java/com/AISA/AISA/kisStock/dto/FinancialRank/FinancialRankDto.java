package com.AISA.AISA.kisStock.dto.FinancialRank;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter; // Added Setter Import
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class FinancialRankDto {
    private List<FinancialRankEntry> ranks;

    @Getter
    @Setter // Added Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder(toBuilder = true)
    @ToString
    public static class FinancialRankEntry {
        private String rank; // data_rank
        private String stockCode; // mksc_shrn_iscd
        private String stockName; // hts_kor_isnm
        private String stacYymm; // Added: 결산 연월 (TTM 기준)
        private String currentPrice; // stck_prpr
        private String saleTotalProfit; // sale_totl_prfi (매출총이익) - Note: User wants Sales Rank, check if this is Sales
                                        // or Gross Profit.
        private String operatingProfit; // bsop_prti (영업이익)
        private String netIncome; // thtr_ntin (당기순이익)
        private String totalAssets; // total_aset
        private String totalLiabilities; // total_lblt
        private String totalCapital; // total_cptl
        private String operatingRate; // 영업이익률
    }
}
