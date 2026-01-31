package com.AISA.AISA.kisStock.dto.DividendRank;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class DividendRankDto {
    private List<DividendRankEntry> ranks;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @ToString
    public static class DividendRankEntry {
        private Integer rank;
        private String stockCode;
        private String stockName;
        private String dividendAmount;
        private String dividendRate;
        private String currentPrice;
    }
}
