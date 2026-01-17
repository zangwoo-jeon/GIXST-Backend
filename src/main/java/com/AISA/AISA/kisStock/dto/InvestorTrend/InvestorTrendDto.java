package com.AISA.AISA.kisStock.dto.InvestorTrend;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class InvestorTrendDto {
    private String recent1MonthForeignerNetBuy;
    private String recent1MonthInstitutionNetBuy;
    private String recent3MonthForeignerNetBuy;
    private String recent3MonthInstitutionNetBuy;
    private String recent1YearForeignerNetBuy;
    private String recent1YearInstitutionNetBuy;

    // 고급 분석 지표
    private BigDecimal foreignerAvgPrice; // 외인 매집 평단가 (VWAP)
    private BigDecimal institutionAvgPrice; // 기관 매집 평단가 (VWAP)
    private BigDecimal foreignerOverheat; // 외인 과열 지수 (%)
    private BigDecimal institutionOverheat; // 기관 과열 지수 (%)
    private double foreignerZScore; // 외인 수급 강도 (Z-Score)
    private double institutionZScore; // 기관 수급 강도 (Z-Score)

    private double supplyScore; // 종합 수급 점수 (0-100)
    private String trendStatus; // 예: "기관 장기 매집 중", "수급 다이버전스 발생" 등

    private String advice; // AI가 해석한 종합 조언글
}
