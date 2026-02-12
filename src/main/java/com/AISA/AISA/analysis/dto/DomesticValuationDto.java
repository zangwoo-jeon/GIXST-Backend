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
        // 1. Conclusion (요약 정보)
        private Summary summary;

        // 2. Current Position (기본 정보 + 점수 + 국면) [Promoted]
        private CurrentPosition currentPosition;

        // 3. Action Strategy (행동 전략) [Promoted]
        private ActionStrategy actionStrategy;

        // 4. Probability & Risk (확률 및 리스크) [Promoted]
        private ProbabilityAndRisk probabilityAndRisk;

        // 5. Evidence Data (밸류에이션 모델)
        private ValuationResult srim;
        private ValuationResult per;
        private ValuationResult pbr;

        // 6. Detailed Technical/Supply
        private AnalysisDetails analysisDetails;

        // Deprecated or Hidden fields for backward compatibility if needed,
        // but for now we keep them to avoid breaking internal logic relying on them
        // temporarily
        @JsonIgnore
        private String stockCode;
        @JsonIgnore
        private String stockName;
        @JsonIgnore
        private String currentPrice;
        @JsonIgnore
        private String marketCap;
        @JsonIgnore
        private Double valuationScore;
        @JsonIgnore
        private Double trendScore;

        @JsonIgnore
        private String targetReturn;
        @JsonIgnore
        private DiscountRateInfo discountRate;
        @JsonIgnore
        private ValuationBand band;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CurrentPosition {
        private String stockCode;
        private String stockName;
        private String currentPrice;
        private String marketCap;
        private Double valuationScore;
        private Double trendScore;
        private String marketPhase; // [New] LATE_STAGE_RALLY etc.
        private String marketPhaseDescription; // [New] Interpretive text
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ActionStrategy {
        private String resistancePrice;
        private String supportPrice;
        private TargetPrices targetPrices;
        private String actionPlan;
        private String timingAction;
        private String divergenceRiskIndex; // [New] LOW, MEDIUM, HIGH, EXTREME

        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class TargetPrices {
            private String shortTerm;
            private String midTerm;
            private String longTerm;
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ProbabilityAndRisk {
        private Summary.Probabilities probabilities;
        private String riskLevel; // LOW, MEDIUM, HIGH
        private List<String> riskFactors;
        private String probabilityInfo; // Descriptive summary
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AnalysisDetails {
        private String investmentTerm;
        private List<String> catalysts;
        private PriceModel priceModel;
        // reviews removed from here, moved to ProbabilityAndRisk

        private ValuationContext valuationContext;
        private TechnicalIndicators technicalIndicators;
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
        public static class ValuationContext {
            private Double beta;
            private double costOfEquity;
            private String evEbitda;
            private com.AISA.AISA.analysis.dto.ValuationBaseDto.HistoricalValuationRange historicalPerRange;
            private com.AISA.AISA.analysis.dto.ValuationBaseDto.HistoricalValuationRange historicalPbrRange;
            private String supplyPatternAnalysis;
        }

    }
}
