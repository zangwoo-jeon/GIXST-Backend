package com.AISA.AISA.kisStock.dto.DividendRank;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;

@Getter
@NoArgsConstructor
@ToString
public class KisDividendRankApiResponse {
    @JsonProperty("rt_cd")
    private String rtCd;

    @JsonProperty("msg1")
    private String msg1;

    @JsonProperty("output")
    private List<Output> output;

    @Getter
    @NoArgsConstructor
    @ToString
    public static class Output {
        @JsonProperty("rank")
        private String rank;

        @JsonProperty("sht_cd")
        private String stockCode;

        @JsonProperty("isin_name")
        private String stockName;

        @JsonProperty("per_sto_divi_amt")
        private String dividendAmount;

        @JsonProperty("divi_rate")
        private String dividendRate;
    }
}
