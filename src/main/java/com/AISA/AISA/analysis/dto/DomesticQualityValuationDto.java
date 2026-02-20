package com.AISA.AISA.analysis.dto;

import lombok.*;

import java.util.List;

public class DomesticQualityValuationDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class QualityReportResponse {
        private String stockCode;
        private String stockName;
        private String currentPrice;
        private String marketCap;
        private LongTermVerdict longTermVerdict;
        private BusinessQuality businessQuality;
        private ValuationContext valuationContext;
        private List<String> thesisMonitoring;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LongTermVerdict {
        private String qualityGrade;              // A, B, C, D
        private String qualityDefinition;          // 등급 의미
        private String valuationStatus;            // 저평가 / 적정 / 고평가
        private String investmentAttractiveness;   // Very Attractive ~ Avoid
        private String suitability;                // AI 생성
        private String action;                     // Strong Buy ~ Sell
        private String reEntryCondition;           // AI 생성
        private String holdingHorizon;             // "3년 이상"
        private GradeRationale gradeRationale;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GradeRationale {
        private List<String> positiveFactors;
        private List<String> negativeFactors;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BusinessQuality {
        private int score;                         // 0-100
        private String trajectory;                 // Improving / Stable / Deteriorating
        private String roeAvg3Y;                   // "3년 평균 ROE 18.5%"
        private String operatingProfitStability;   // "안정적 (3/3년 흑자)"
        private String salesGrowth3Y;              // "매출 3년 CAGR 12.3%"
        private String growthWarning;              // 매출 역성장 + EPS 성장 경고 (nullable)
        private String balanceSheet;               // "건전 (부채비율 85%)"
        private String dividendStability;          // "안정적 (3년 연속 배당)"
        private String moatDescription;            // AI 생성
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ValuationContext {
        private String status;                     // 저평가 / 적정 / 고평가
        private String perPercentile;              // "하위 30% (현재 10.2배)"
        private String pbrPercentile;              // "하위 25% (현재 1.1배)"
        private String evEbitdaPercentile;         // "중간 50% (현재 8.5배)"
        private String earningsYieldVsBond;        // "Earnings Yield 9.8% vs 국채3년 3.2%"
    }
}
