package com.AISA.AISA.kisStock.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;

@Getter
@NoArgsConstructor
@ToString
public class KisOtherMajorRatiosResponse {

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
        @JsonProperty("stac_yymm")
        private String stacYymm;

        @JsonProperty("ev_ebitda")
        private String evEbitda;

        @JsonProperty("ebitda")
        private String ebitda;

        @JsonProperty("payout_rate")
        private String payoutRate;

        @JsonProperty("eva")
        private String eva;
    }
}
