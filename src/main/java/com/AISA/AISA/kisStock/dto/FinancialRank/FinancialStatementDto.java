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
public class FinancialStatementDto {
    @JsonProperty("Date")
    private String stacYymm; // 결산 년월

    @JsonProperty("Sales")
    private String saleAccount; // 매출액

    @JsonProperty("Operating_Profit")
    private String operatingProfit; // 영업 이익

    @JsonProperty("Net_Income")
    private String netIncome; // 당기순이익
}
