package com.AISA.AISA.kisStock.dto.FinancialRank;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class KisStockSearchInfoApiResponse {
    @JsonProperty("rt_cd")
    private String rtCd;

    @JsonProperty("msg_cd")
    private String msgCd;

    @JsonProperty("msg1")
    private String msg1;

    @JsonProperty("output")
    private StockSearchInfoOutput output;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class StockSearchInfoOutput {
        @JsonProperty("pdno")
        private String pdno; // 상품번호

        @JsonProperty("prdt_type_cd")
        private String prdtTypeCd; // 상품유형코드

        @JsonProperty("scts_mket_lstg_dt")
        private String sctsMketLstgDt; // 유가증권시장상장일자

        @JsonProperty("kosdaq_mket_lstg_dt")
        private String kosdaqMketLstgDt; // 코스닥시장상장일자

        @JsonProperty("frbd_mket_lstg_dt")
        private String frbdMketLstgDt; // 프리보드시장상장일자

        @JsonProperty("lstg_abol_dt")
        private String lstgAbolDt; // 상장폐지일자

        @JsonProperty("tr_stop_yn")
        private String trStopYn; // 거래정지여부
    }
}
