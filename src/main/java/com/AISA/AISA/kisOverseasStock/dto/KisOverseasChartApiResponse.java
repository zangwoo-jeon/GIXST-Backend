package com.AISA.AISA.kisOverseasStock.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

@Getter
@ToString
public class KisOverseasChartApiResponse {
    @JsonProperty("rt_cd")
    private String rtCd;

    @JsonProperty("msg_cd")
    private String msgCd;

    @JsonProperty("msg1")
    private String msg1;

    @JsonProperty("output1")
    private KisOverseasChartOutput1 output1;

    @JsonProperty("output2")
    private List<KisOverseasChartOutput2> output2;

    @Getter
    @ToString
    public static class KisOverseasChartOutput1 {
        @JsonProperty("rsym")
        private String stockCode;

        @JsonProperty("zdiv")
        private String decimalPlaces;

        @JsonProperty("nrec")
        private String prevClose; // User description says '전일종가'
    }

    @Getter
    @ToString
    public static class KisOverseasChartOutput2 {
        @JsonProperty("xymd")
        private String date;

        @JsonProperty("clos")
        private String close;

        @JsonProperty("sign")
        private String sign;

        @JsonProperty("diff")
        private String diff;

        @JsonProperty("rate")
        private String rate;

        @JsonProperty("open")
        private String open;

        @JsonProperty("high")
        private String high;

        @JsonProperty("low")
        private String low;

        @JsonProperty("tvol")
        private String volume;

        @JsonProperty("tamt")
        private String amount;

        @JsonProperty("pbid")
        private String bidPrice;

        @JsonProperty("vbid")
        private String bidVolume;

        @JsonProperty("pask")
        private String askPrice;

        @JsonProperty("vask")
        private String askVolume;
    }
}
