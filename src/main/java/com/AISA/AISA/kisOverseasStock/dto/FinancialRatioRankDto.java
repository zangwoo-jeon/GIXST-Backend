package com.AISA.AISA.kisOverseasStock.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class FinancialRatioRankDto {
    private List<RankItem> rankings;
    private int totalCount;
    private int totalPages;
    private int currentPage;

    @Getter
    @Builder
    public static class RankItem {
        private Integer rank;
        private String stockCode;
        private String stockName;
        private String per;
        private String pbr;
        private String roe;
        private String eps; // USD
        private String bps; // USD
    }
}
