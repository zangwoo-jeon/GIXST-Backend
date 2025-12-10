package com.AISA.AISA.kisStock.dto.VolumeRank;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;

@Getter
@NoArgsConstructor
@ToString
public class KisVolumeRankApiResponse {
    @JsonProperty("rt_cd")
    private String rtCd;

    @JsonProperty("msg1")
    private String msg1;

    @JsonProperty("output")
    private List<VolumeRankItem> output;

    @Getter
    @NoArgsConstructor
    @ToString
    public static class VolumeRankItem {
        @JsonProperty("hts_kor_isnm")
        private String stockName;

        @JsonProperty("mksc_shrn_iscd")
        private String stockCode;

        @JsonProperty("data_rank")
        private String rank;

        @JsonProperty("stck_prpr")
        private String currentPrice;

        @JsonProperty("prdy_vrss_sign")
        private String priceChangeSign;

        @JsonProperty("prdy_vrss")
        private String priceChange;

        @JsonProperty("prdy_ctrt")
        private String priceChangeRate;

        @JsonProperty("acml_vol")
        private String accumulatedVolume;

        @JsonProperty("prdy_vol")
        private String previousDayVolume;

        @JsonProperty("avrg_vol")
        private String averageVolume;
    }
}
