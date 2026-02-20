package com.AISA.AISA.analysis.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

public class OverseasQualityValuationDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class QualityReportResponse {
        private LongTermVerdict longTermVerdict;
        private BusinessQuality businessQuality;
        private ValuationContext valuationContext;
        private List<String> thesisMonitoring;

        // Common metadata
        private String stockCode;
        private String stockName;
        private String currentPrice;
        private String marketCap;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LongTermVerdict {
        private String qualityGrade; // A, B, C, D
        private String qualityDefinition; // Philosophical meaning of the grade
        private String valuationStatus; // Expensive, Fair, Cheap
        private String investmentAttractiveness; // Strong Buy, Buy, Neutral, Avoid
        private String suitability; // AI-generated rationale
        private String action; // Legacy/Simplified action guide
        private String reEntryCondition;
        private String holdingHorizon;
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
        private int score; // 0-100
        private String trajectory; // Added for V3: Improving | Stable | Deteriorating
        private String roicVsWacc; // e.g., "ROIC 18% > WACC 9%"
        private String roicWaccSpread; // Added for V6: e.g., "+9.0%p"
        private String sustainabilityWarning; // Added for V4: Extreme efficiency or risk warnings
        private String fcfTrend; // e.g., "5년 연속 증가"
        private String balanceSheet; // e.g., "순현금"
        private String dilution; // e.g., "주식수 안정적"
        private String moatDescription; // Added for qualitative insight
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ValuationContext {
        private String status; // 저평가, 적정, 고평가
        private String fcfYield;
        private String evEbitdaVsHistory; // e.g., "5년 평균 대비 +10%"
    }
}
