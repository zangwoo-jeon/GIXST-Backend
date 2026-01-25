package com.AISA.AISA.kisOverseasStock.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.ToString;

@Getter
@ToString
public class KisOverseasStockSearchInfoApiResponse {
    @JsonProperty("rt_cd")
    private String rtCd;

    @JsonProperty("msg_cd")
    private String msgCd;

    @JsonProperty("msg1")
    private String msg1;

    @JsonProperty("output")
    private KisOverseasStockSearchInfoOutput output;

    @Getter
    @ToString
    public static class KisOverseasStockSearchInfoOutput {
        @JsonProperty("std_pdno")
        private String stdPdno; // 표준상품번호

        @JsonProperty("prdt_eng_name")
        private String prdtEngName; // 상품영문명

        @JsonProperty("ovrs_stck_tr_stop_dvsn_cd")
        private String ovrsStckTrStopDvsnCd; // 해외주식거래정지구분코드 (01:정상, 02:거래정지(ALL), ...)

        @JsonProperty("lstg_abol_item_yn")
        private String lstgAbolItemYn; // 상장폐지종목여부 (Y/N)

        @JsonProperty("lstg_yn")
        private String lstgYn; // 상장여부 (Y/N)
    }
}
