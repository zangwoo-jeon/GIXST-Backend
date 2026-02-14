package com.AISA.AISA.kisStock.dto.VolumeRank;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VolumeRankDto {
    private List<VolumeRankEntry> ranks;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class VolumeRankEntry {
        private String stockName;
        private String stockCode;
        private String rank;
        private String currentPrice;
        private String priceChangeSign;
        private String priceChange;
        private String priceChangeRate;
        private String accumulatedVolume;
        private String previousDayVolume;
        private String averageVolume;
        private Stock.StockType stockType;
    }
}
