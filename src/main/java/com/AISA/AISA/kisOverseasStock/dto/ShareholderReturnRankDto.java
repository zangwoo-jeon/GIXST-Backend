package com.AISA.AISA.kisOverseasStock.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ShareholderReturnRankDto {
    private List<RankItem> rankings;

    @Getter
    @Builder
    public static class RankItem {
        private Integer rank;
        private String stockCode;
        private String stockName;
        private String returnAmount; // USD
        private String returnRate; // %
        private String currentPrice; // USD
        private String marketCap; // USD (Billion)
    }
}
