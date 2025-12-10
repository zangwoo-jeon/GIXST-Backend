package com.AISA.AISA.kisStock.dto.DividendRank;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

@Getter
@Builder
@ToString
public class DividendRankDto {
    private List<DividendRankEntry> ranks;

    @Getter
    @Builder
    @ToString
    public static class DividendRankEntry {
        private String rank;
        private String stockCode;
        private String stockName;
        private String dividendAmount;
        private String dividendRate;
        private String currentPrice;
    }
}
