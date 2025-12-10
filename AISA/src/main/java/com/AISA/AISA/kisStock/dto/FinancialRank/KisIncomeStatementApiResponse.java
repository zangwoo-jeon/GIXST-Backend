package com.AISA.AISA.kisStock.dto.FinancialRank;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class KisIncomeStatementApiResponse {
    @JsonProperty("rt_cd")
    private String rtCd;

    @JsonProperty("msg_cd")
    private String msgCd;

    @JsonProperty("msg1")
    private String msg1;

    @JsonProperty("output")
    private List<IncomeStatementOutput> output;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class IncomeStatementOutput {
        @JsonProperty("stac_yymm")
        private String stacYymm; // 결산 년월

        @JsonProperty("sale_account")
        private String saleAccount; // 매출액

        @JsonProperty("sale_cost")
        private String saleCost; // 매출 원가

        @JsonProperty("sale_totl_prfi")
        private String saleTotlPrfi; // 매출 총 이익

        @JsonProperty("bsop_prti")
        private String bsopPrti; // 영업 이익

        @JsonProperty("thtr_ntin")
        private String thtrNtin; // 당기순이익

        @JsonProperty("op_prfi")
        private String opPrfi; // 경상 이익
    }
}
