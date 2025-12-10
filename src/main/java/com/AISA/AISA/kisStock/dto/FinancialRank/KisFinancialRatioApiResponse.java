package com.AISA.AISA.kisStock.dto.FinancialRank;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

@Getter
@Setter
@ToString
public class KisFinancialRatioApiResponse {

    @JsonProperty("rt_cd")
    private String rtCd; // 성공 실패 여부

    @JsonProperty("msg_cd")
    private String msgCd; // 응답코드

    @JsonProperty("msg1")
    private String msg1; // 응답메세지

    @JsonProperty("output")
    private List<FinancialRatioOutput> output;

    @Getter
    @Setter
    @ToString
    public static class FinancialRatioOutput {
        @JsonProperty("stac_yymm")
        private String stacYymm; // 결산 년월

        @JsonProperty("grs")
        private String grs; // 매출액 증가율

        @JsonProperty("bsop_prfi_inrt")
        private String bsopPrfiInrt; // 영업 이익 증가율

        @JsonProperty("ntin_inrt")
        private String ntinInrt; // 순이익 증가율

        @JsonProperty("roe_val")
        private String roeVal; // ROE 값

        @JsonProperty("eps")
        private String eps; // EPS

        @JsonProperty("sps")
        private String sps; // 주당매출액

        @JsonProperty("bps")
        private String bps; // BPS

        @JsonProperty("rsrv_rate")
        private String rsrvRate; // 유보 비율

        @JsonProperty("lblt_rate")
        private String lbltRate; // 부채 비율
    }
}
