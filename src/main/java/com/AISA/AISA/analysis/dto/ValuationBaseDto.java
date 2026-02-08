package com.AISA.AISA.analysis.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

/**
 * Common components for Valuation DTOs
 */
public class ValuationBaseDto {

    public enum UserPropensity {
        CONSERVATIVE, NEUTRAL, AGGRESSIVE
    }

    public enum Stance {
        BUY, ACCUMULATE, HOLD, REDUCE, SELL
    }

    public enum Timing {
        EARLY, MID, LATE, UNCERTAIN
    }

    public enum RiskLevel {
        LOW, MEDIUM, HIGH
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Summary {
        private String overallVerdict;
        private String confidence;
        private String keyInsight;

        private Verdicts verdicts;
        private Display display;

        @JsonIgnore
        private BeginnerVerdict beginnerVerdict;

        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class Display {
            private String verdict;
            private String verdictLabel;
            private Strategy strategy;
            private String risk;
            private String probabilityInfo;
        }

        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class Strategy {
            private String resistanceZone;
            private String supportZone;
            private String actionPlan;
        }

        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class Verdicts {
            private AiVerdict aiVerdict;
        }

        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class BeginnerVerdict {
            private String summarySentence;
        }

        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class AiVerdict {
            private Stance stance;
            private Timing timing;
            private RiskLevel riskLevel;
            private String guidance;
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ValuationResult {
        private String price;
        private String verdict;
        private String gapRate;
        private String description;
        @JsonIgnore
        private boolean available;
        @JsonIgnore
        private String reason;
        @JsonIgnore
        private String roeType;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ValuationBand {
        private String minPrice;
        private String maxPrice;
        private String currentPrice;
        private String gapRate;
        private String position;
        private String status;
        private Map<String, Double> weights;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DiscountRateInfo {
        private String basis;
        private String profile;
        private String value;
        private String source;
        private String note;
    }
}
