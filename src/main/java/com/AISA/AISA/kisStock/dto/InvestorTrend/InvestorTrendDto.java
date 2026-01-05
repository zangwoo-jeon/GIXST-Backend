package com.AISA.AISA.kisStock.dto.InvestorTrend;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class InvestorTrendDto {
    private String recent3MonthForeignerNetBuy; // 최근 3개월 외인 순매수 합계
    private String recent3MonthInstitutionNetBuy; // 최근 3개월 기관 순매수 합계
    private String trendStatus; // 예: "기관 집중 매수", "외국인/기관 동반 매도" 등
}
