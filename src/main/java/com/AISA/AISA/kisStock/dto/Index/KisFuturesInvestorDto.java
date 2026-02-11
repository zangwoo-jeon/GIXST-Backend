package com.AISA.AISA.kisStock.dto.Index;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class KisFuturesInvestorDto {

    // 개인 순매수 거래 대금
    @JsonProperty("prsn_ntby_tr_pbmn")
    private String prsnNtbyTrPbmn;

    // 외국인 순매수 거래 대금
    @JsonProperty("frgn_ntby_tr_pbmn")
    private String frgnNtbyTrPbmn;

    // 기관계 순매수 거래 대금
    @JsonProperty("orgn_ntby_tr_pbmn")
    private String orgnNtbyTrPbmn;

    // 추가 필드 (필요시)
    @JsonProperty("stot_ntby_tr_pbmn")
    private String stotNtbyTrPbmn; // 합계? (보통은 개인+외국인+기관)
}
