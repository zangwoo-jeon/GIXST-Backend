package com.AISA.AISA.kisStock.dto.StockPrice;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class StockPriceResponse {
    @JsonProperty("stck_shrn_iscd") // 주식 종목 코드
    private String stockCode;

    @JsonProperty("stck_prpr") // 주식 현재가
    private String stockPriceRaw;

    @JsonProperty("prdy_vrss") // 전일 대비
    private String priceChangeRaw;

    @JsonProperty("prdy_ctrt") // 전일 대비율
    private String changeRateRaw;

    @JsonProperty("acml_vol") // 누적 거래량
    private String accumulatedVolumeRaw;

    @JsonProperty("stck_oprc") // 시가
    private String openingPriceRaw;

    @JsonProperty("lstn_stcn") // 상장 주수
    private String listedSharesCount;

    @JsonProperty("hts_avls") // HTS 시가총액
    private String marketCapRaw;

    @JsonProperty("iscd_stat_cls_code") // 종목상태구분코드 (58:거래정지)
    private String statusCode;
}
