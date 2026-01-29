package com.AISA.AISA.kisStock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StockGrowthRankingDto {
    private List<GrowthRankingEntry> ranks;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class GrowthRankingEntry {
        private String rank;
        private String stockCode;
        private String stockName;
        private String marketName;
        private String stacYymm;

        // Metrics
        private String salesGrowth;
        private String opGrowth;
        private String epsGrowth;

        private boolean isTurnaround;
        private String calculatedAt;

        // Optional info
        private String currentPrice;
        private String marketCap;
    }
}
