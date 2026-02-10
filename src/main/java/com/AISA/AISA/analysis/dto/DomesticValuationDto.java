package com.AISA.AISA.analysis.dto;

import com.AISA.AISA.analysis.dto.ValuationBaseDto.*;
import com.AISA.AISA.kisStock.dto.InvestorTrend.InvestorTrendDto;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class DomesticValuationDto {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Request {
        private UserPropensity userPropensity;
        private Double expectedTotalReturn;
        @Builder.Default
        private boolean forceRefresh = false;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder(toBuilder = true)
    @JsonInclude(Include.NON_NULL)
    public static class Response {
        private String stockCode;
        private String stockName;
        private String currentPrice;
        private String marketCap;
        @JsonIgnore
        private String targetReturn;
        @JsonIgnore
        private DiscountRateInfo discountRate;

        private ValuationResult srim;
        private ValuationResult per;
        private ValuationResult pbr;

        @JsonIgnore
        private ValuationBand band;
        private Summary summary;

        private Double valuationScore;
        private Double trendScore;

        private AnalysisDetails analysisDetails;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AnalysisDetails {
        private String investmentTerm;
        private List<String> catalysts;
        private List<String> risks;

        @JsonIgnore
        private PriceModel priceModel;

        private TechnicalIndicators technicalIndicators;
        @JsonIgnore
        private ValuationContext valuationContext;

        private InvestorTrendDto investorTrend;

        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class PriceModel {
            private String upsidePotential;
            private String downsideRisk;
        }

        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class TechnicalIndicators {
            private double rsi;
            private Map<String, String> movingAverages; // "MA20": "70,000", "status": "정배열" 등
            private Double relativeStrength; // 지수 대비 강도 (Alpha)
            private String priceLocation; // "GOLDEN_CROSS", "SUPPORT_LINE" 등

            // [v3] 추가 필드
            private Double stochasticK;
            private Double stochasticD;
            private String stochasticZone; // OVERSOLD/WEAK/NEUTRAL/STRONG/OVERBOUGHT
            private String stochasticSignal; // GOLDEN/DEAD/NONE
            private Double stochasticStrength;
            private String marketRegime; // STRONG_TREND/WEAK_TREND/SIDEWAYS
            private Double regimeConfidence;
        }

        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class ValuationContext {
            private Double beta;
            private Double costOfEquity;
            private String evEbitda; // QualityMetrics에서 이동
            private HistoricalValuationRange historicalPerRange;
            private HistoricalValuationRange historicalPbrRange;
            private String supplyPatternAnalysis; // KNN 수급 패턴 분석 결과
        }

        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class HistoricalValuationRange {
            private String min;
            private String max;
            private String median;
            private String current;
        }
    }
}
