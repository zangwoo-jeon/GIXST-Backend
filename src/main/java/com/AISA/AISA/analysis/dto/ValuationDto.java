package com.AISA.AISA.analysis.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.AISA.AISA.kisStock.dto.InvestorTrend.InvestorTrendDto;
import java.util.List;
import java.util.Map;

public class ValuationDto {

    public enum UserPropensity {
        CONSERVATIVE, NEUTRAL, AGGRESSIVE
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Request {
        private UserPropensity userPropensity; // "CONSERVATIVE", "NEUTRAL", "AGGRESSIVE"
        private Double expectedTotalReturn; // Advanced override (Optional)
        @Builder.Default
        private boolean forceRefresh = false; // Force regenerate AI report (Ignore Cache)
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
        private String marketCap; // Added Market Cap
        @JsonIgnore
        private String targetReturn; // 적용된 할인율 (Display String)

        @JsonIgnore
        private DiscountRateInfo discountRate; // Detailed Discount Rate Info

        private ValuationResult srim;
        private ValuationResult per;
        private ValuationResult pbr;

        private ValuationBand band;
        private Summary summary;

        // Phase 6: Strategist Details
        private AnalysisDetails analysisDetails; // Domestic
        private OverseasAnalysisDetails overseasAnalysisDetails; // Overseas
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AnalysisDetails {
        private String upsidePotential; // "23.28%"
        private String downsideRisk; // "-10.93%"
        private String investmentTerm; // "6-12 Months"
        private List<String> catalysts; // 상승 동력
        private List<String> risks; // 리스크 요인
        private PeerComparison peerComparison; // 경쟁사 비교
        private InvestorTrendDto investorTrend; // 수급 요약
        private String evEbitda; // EV/EBITDA (Domestic)
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OverseasAnalysisDetails {
        private String upsidePotential;
        private String downsideRisk;
        private String investmentTerm;
        private List<String> catalysts;
        private List<String> risks;

        private String pegRatio; // e.g. "1.2 (Fair)"
        private String evEbitda; // e.g. "12.5x"
        private String shareholderYield; // e.g. "5.4% (Buyback 3.2% + Div 2.2%)"
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PeerComparison {
        private String sectorAvgPer;
        private String status; // "BELOW_SECTOR_AVG"
        private List<PeerInfo> peers;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PeerInfo {
        private String name;
        private String per;
        private String pbr;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Summary {
        private String overallVerdict; // BUY, SELL, HOLD (Legacy)
        private String confidence; // HIGH, MEDIUM, LOW
        private String keyInsight; // One line summary

        // Phase 2
        @JsonIgnore
        private String valuationLogic; // Detailed logic description
        @JsonIgnore
        private String decisionRule;
        @JsonIgnore
        private String aiAnalysis; // (Legacy)

        // Phase 3: Dual Verdict System
        private Verdicts verdicts;
        // Phase 4: Beginner Verdict
        @JsonIgnore
        private BeginnerVerdict beginnerVerdict;
        // Phase 5: Display Layer (Compact View)
        private Display display;

        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class Display {
            private String verdict; // "HOLD" (System Code)
            private String verdictLabel; // "관망" (User Friendly Label)
            private String summary; // "한 줄 요약"
            private String risk; // "MEDIUM" (Safe String)
        }

        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class Verdicts {
            @JsonIgnore
            private ModelVerdict modelVerdict;
            private AiVerdict aiVerdict;
            @Builder.Default
            @JsonIgnore
            private Principles principles = new Principles();
        }

        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class BeginnerVerdict {
            private String summarySentence; // 초보자용 대표 문장 (행동 가이드)
        }

        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class Principles {
            @Builder.Default
            private boolean aiOverrideAllowed = false;
            @Builder.Default
            private String aiRole = "EXECUTION_GUIDANCE";
        }

        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class ModelVerdict {
            private String rating; // BUY, HOLD, SELL
            private int score;
            private String confidence;
            @Builder.Default
            @JsonIgnore
            private String question = "내재가치 대비 저평가되었는가?";
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
            @JsonIgnore
            private String alignmentNote;
        }
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
    public static class ValuationResult {
        private String price; // 산출된 적정 주가
        private String verdict; // UNDERVALUED, FAIR, OVERVALUED
        private String gapRate; // 현재가 대비 괴리율 (%)
        private String description; // 모델 설명

        // Phase 1.5
        private boolean available; // 모델 적용 가능 여부
        @JsonIgnore
        private String reason; // 적용 불가 사유 (e.g. "EPS 적자")
        @JsonIgnore
        private String roeType; // 사용된 ROE 타입 (e.g. "3Y_AVG", "LATEST")

        // Phase 2
        @JsonIgnore
        private Map<String, ValuationResult> scenarios; // CONSERVATIVE, BASE, OPTIMISTIC
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DiscountRateInfo {
        private String basis; // "Market reference..."
        private String profile; // "NEUTRAL"
        private String value; // "8.0%"
        private String source; // "USER_OVERRIDE" or "PROFILE_DEFAULT"
        private String note; // Additional notes or explanation for the discount rate
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ValuationBand {
        private String minPrice; // 보수적 적정가
        private String maxPrice; // 공격적 적정가
        private String currentPrice;
        private String gapRate; // 밴드(중간값) 대비 괴리율
        private String position; // 밴드 내 위치 (Allows negative or >100)
        private String status; // BELOW_BAND, WITHIN_BAND, ABOVE_BAND

        // Phase 1.5
        private Map<String, Double> weights; // 모델별 가중치
    }
}
