package com.AISA.AISA.kisStock.dto.InvestorTrend;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;

@Getter
@NoArgsConstructor
@ToString
public class KisInvestorDailyResponse {

    @JsonProperty("rt_cd")
    private String rtCd; // 성공 실패 여부

    @JsonProperty("msg_cd")
    private String msgCd; // 응답 코드

    @JsonProperty("msg1")
    private String msg1; // 응답 메시지

    @JsonProperty("output2")
    private List<InvestorDailyOutput> output; // 일자별 리스트 (Output2)

    @Getter
    @NoArgsConstructor
    @ToString
    public static class InvestorDailyOutput {

        @JsonProperty("stck_bsop_date")
        private String stckBsopDate; // 영업일자 (YYYYMMDD)

        @JsonProperty("frgn_ntby_tr_pbmn")
        private String frgnNtbyTrPbmn; // 외국인 순매수 거래대금

        @JsonProperty("prsn_ntby_tr_pbmn")
        private String prsnNtbyTrPbmn; // 개인 순매수 거래대금

        @JsonProperty("orgn_ntby_tr_pbmn")
        private String orgnNtbyTrPbmn; // 기관계 순매수 거래대금

        @JsonProperty("etc_corp_ntby_tr_pbmn")
        private String etcCorpNtbyTrPbmn; // 기타법인 순매수 거래대금

        @JsonProperty("frgn_ntby_qty")
        private String frgnNtbyQty; // 외국인 순매수 수량 [NEW]

        @JsonProperty("prsn_ntby_qty")
        private String prsnNtbyQty; // 개인 순매수 수량 [NEW]

        @JsonProperty("orgn_ntby_qty")
        private String orgnNtbyQty; // 기관계 순매수 수량 [NEW]
    }
}
