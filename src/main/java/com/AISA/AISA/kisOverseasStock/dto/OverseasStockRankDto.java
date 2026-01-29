package com.AISA.AISA.kisOverseasStock.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class OverseasStockRankDto {
    private List<RankItem> rankings;

    @Getter
    @Builder
    public static class RankItem {
        private Integer rank;
        private String stockCode;
        private String stockName;
        private String price;
        private String priceChange;
        private String changeRate;
        private String changeSign;
        private String marketCap; // USD (Millions)
        private String marketCapKrw; // KRW
        private String listedShares; // Listed shares
    }
}
