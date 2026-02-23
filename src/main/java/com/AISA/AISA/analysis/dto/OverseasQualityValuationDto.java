package com.AISA.AISA.analysis.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

public class OverseasQualityValuationDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(name = "OverseasQualityReportResponse")
    public static class QualityReportResponse {
        private StockInfo stockInfo;
        private InvestmentSummary investmentSummary;
        private QualityAnalysis qualityAnalysis;
        private ValuationAnalysis valuationAnalysis;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StockInfo {
        private String stockCode;
        private String stockName;
        private String currentPrice;
        private String marketCap;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvestmentSummary {
        private String action; // Strong Buy ~ Sell
        private String investmentAttractiveness; // Very Attractive ~ Avoid
        private String suitability; // AI 생성
        private String reEntryCondition; // AI 생성
        private String holdingHorizon; // "3년 이상"
        private List<String> thesisMonitoring;
        private GradeRationale gradeRationale;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "OverseasGradeRationale")
    public static class GradeRationale {
        private List<String> positiveFactors;
        private List<String> negativeFactors;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class QualityAnalysis {
        private String qualityGrade; // A, B, C, D
        private String qualityDefinition; // 등급 의미
        private int score; // 0-100
        private String trajectory; // Improving / Stable / Deteriorating
        private String moatDescription; // AI 생성
        private QualityMetrics metrics;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class QualityMetrics {
        private String roicVsWacc;
        private String roicWaccSpread;
        private String sustainabilityWarning;
        private String fcfTrend;
        private String balanceSheet;
        private String dilution;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ValuationAnalysis {
        private String status; // 저평가 / 적정 / 고평가
        private String fcfYield;
        private String evEbitdaVsHistory;
    }
}
