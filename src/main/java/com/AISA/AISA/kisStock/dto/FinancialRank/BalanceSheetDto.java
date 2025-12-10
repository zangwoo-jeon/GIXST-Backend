package com.AISA.AISA.kisStock.dto.FinancialRank;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class BalanceSheetDto {
    @JsonProperty("stac_yymm")
    private String stacYymm;

    @JsonProperty("total_aset")
    private String totalAssets;

    @JsonProperty("total_lblt")
    private String totalLiabilities;

    @JsonProperty("total_cptl")
    private String totalCapital;
}
