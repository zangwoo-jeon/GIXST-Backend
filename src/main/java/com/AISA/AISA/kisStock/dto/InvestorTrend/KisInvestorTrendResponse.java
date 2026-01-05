package com.AISA.AISA.kisStock.dto.InvestorTrend;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class KisInvestorTrendResponse {

    @JsonProperty("rt_cd")
    private String rtCd;

    @JsonProperty("msg1")
    private String msg1;

    @JsonProperty("output")
    private List<InvestorTrendOutput> output;

    @Getter
    @Setter
    @NoArgsConstructor
    @ToString
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InvestorTrendOutput {
        @JsonProperty("stck_bsop_date")
        private String stckBsopDate; // 영업일자

        @JsonProperty("prsn_ntby_tr_pbmn")
        private String prsnNtbyTrPbmn; // 개인 순매수 거래 대금

        @JsonProperty("frgn_ntby_tr_pbmn")
        private String frgnNtbyTrPbmn; // 외국인 순매수 거래 대금

        @JsonProperty("orgn_ntby_tr_pbmn")
        private String orgnNtbyTrPbmn; // 기관계 순매수 거래 대금
    }
}
