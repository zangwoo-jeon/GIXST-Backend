package com.AISA.AISA.kisStock.dto.Macro;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class KisOverseasDailyPriceDto {

    @JsonProperty("stck_bsop_date")
    private String date; // 영업 일자 (YYYYMMDD)

    @JsonProperty("ovrs_nmix_prpr")
    private String closePrice; // 종가 (현재가)

    @JsonProperty("ovrs_nmix_oprc")
    private String openPrice; // 시가

    @JsonProperty("ovrs_nmix_hgpr")
    private String highPrice; // 최고가

    @JsonProperty("ovrs_nmix_lwpr")
    private String lowPrice; // 최저가

    @JsonProperty("acml_vol")
    private String volume; // 누적 거래량
}
