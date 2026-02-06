package com.AISA.AISA.kisStock.dto.StockPrice;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class KisEtfPriceResponseDto {
    @JsonProperty("rt_cd")
    private String rtCd;

    @JsonProperty("msg_cd")
    private String msgCd;

    @JsonProperty("msg1")
    private String msg1;

    @JsonProperty("output")
    private Output output;

    @Getter
    @Setter
    public static class Output {
        @JsonProperty("stck_prpr")
        private String stckPrpr; // 주식 현재가

        @JsonProperty("nav")
        private String nav; // NAV

        @JsonProperty("trc_errt")
        private String trcErrt; // 추적 오차율

        @JsonProperty("etf_crcl_stcn")
        private String etfCrclStcn; // ETF 유통 주수

        @JsonProperty("etf_cnfg_issu_cnt")
        private String etfCnfgIssuCnt; // ETF 구성 종목 수
    }
}
