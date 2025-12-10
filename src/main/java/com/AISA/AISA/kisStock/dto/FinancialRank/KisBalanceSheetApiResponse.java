package com.AISA.AISA.kisStock.dto.FinancialRank;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;

@Getter
@NoArgsConstructor
@ToString
public class KisBalanceSheetApiResponse {
    @JsonProperty("rt_cd")
    private String rtCd;

    @JsonProperty("msg_cd")
    private String msgCd;

    @JsonProperty("msg1")
    private String msg1;

    @JsonProperty("output")
    private List<Output> output;

    @Getter
    @NoArgsConstructor
    @ToString
    public static class Output {
        @JsonProperty("stac_yymm")
        private String stacYymm;

        @JsonProperty("cras")
        private String currentAssets;

        @JsonProperty("fxas")
        private String fixedAssets;

        @JsonProperty("total_aset")
        private String totalAssets;

        @JsonProperty("flow_lblt")
        private String currentLiabilities;

        @JsonProperty("fix_lblt")
        private String fixedLiabilities;

        @JsonProperty("total_lblt")
        private String totalLiabilities;

        @JsonProperty("cpfn")
        private String capitalStock;

        @JsonProperty("cfp_surp")
        private String capitalSurplus;

        @JsonProperty("prfi_surp")
        private String retainedEarnings;

        @JsonProperty("total_cptl")
        private String totalCapital;
    }
}
