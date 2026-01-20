package com.AISA.AISA.kisOverseasStock.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.ToString;

@Getter
@ToString
public class KisOverseasPriceApiResponse {
    @JsonProperty("rt_cd")
    private String rtCd;

    @JsonProperty("msg_cd")
    private String msgCd;

    @JsonProperty("msg1")
    private String msg1;

    @JsonProperty("output")
    private KisOverseasPriceOutput output;

    @Getter
    @ToString
    public static class KisOverseasPriceOutput {
        @JsonProperty("rsym")
        private String stockCode;

        @JsonProperty("last")
        private String price; // Current Price (USD)

        @JsonProperty("base")
        private String prevClose; // Previous Close

        @JsonProperty("diff")
        private String priceChange; // Change Amount

        @JsonProperty("rate")
        private String changeRate; // Change Rate (%)

        @JsonProperty("tvol")
        private String volume;

        @JsonProperty("tomv")
        private String marketCap;

        @JsonProperty("t_xprc")
        private String wonPrice; // Converted to KRW

        @JsonProperty("t_rate")
        private String exchangeRate; // Exchange Rate
    }
}
