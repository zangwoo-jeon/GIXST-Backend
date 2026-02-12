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
    @JsonInclude(Include.NON_NULL)
    public static class Summary {
        private String overallVerdict;
        private String confidence;
        private String keyInsight;

        private Verdicts verdicts;
        private Display display;
        private Probabilities probabilities; // [V5] 시계열 확률 정보
        private ConfidenceAttribution attribution; // [V6] 신뢰도 분해
        private java.util.List<String> catalysts; // [V6] 상승 촉매제
        private java.util.List<String> risks; // [V6] 하방 리스크
        private String timingAction; // [V6] 실행 타이밍 지침

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
            private String positionSummary; // [V5] "역사적 저점 부근", "단기 과열" 등
            private Strategy strategy;
            private String risk;
            private String probabilityInfo;
            private String timingAction; // [V5] "분할 매수 시작", "관망 후 반등시 매도" 등
        }

        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class Strategy {
            private String resistanceZone;
            private String supportZone;
            private String shortTermTarget;
            private String midTermTarget;
            private String longTermTarget;
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

        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class Probabilities {
            private String shortTerm; // "65%"
            private String midTerm; // "50%"
            private String longTerm; // "80%"
        }

        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class ConfidenceAttribution {
            private Double dataQuality; // 0-100
            private Double modelAgreement; // 0-100
            private Double regimeStability; // 0-100
            private String primaryDriver; // 주요 주도 요인
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
        private String modelName; // [V6] PEG, SRIM, Consensus 등
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
    public static class HistoricalValuationRange {
        private String min;
        private String max;
        private String median;
        private String current;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(Include.NON_NULL)
    public static class TechnicalIndicators {
        private double rsi;
        private Map<String, String> movingAverages;
        private Double relativeStrength;
        private String priceLocation;
        private Double stochasticK;
        private Double stochasticD;
        private String stochasticZone;
        private String stochasticSignal;
        private Double stochasticStrength;
        private String marketRegime;
        private Double regimeConfidence;
        private Double transitionProbability;
        private Double regimeStability;
        private Double atr; // [New] Average True Range
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
