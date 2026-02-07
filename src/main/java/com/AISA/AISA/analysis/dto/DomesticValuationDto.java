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
        private QualityMetrics qualityMetrics;
        private TechnicalIndicators technicalIndicators;
        private ValuationContext valuationContext;

        private PeerComparison peerComparison;
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
        public static class QualityMetrics {
            private String evEbitda;
        }

        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class TechnicalIndicators {
            private double rsi;
            private List<String> movingAverages; // "20: 70,000", "60: 72,000" 등
            private Double relativeStrengthIndex; // 지수 대비 강도 (Alpha)
            private String priceLocation; // "GOLDEN_CROSS", "SUPPORT_LINE" 등
        }

        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class ValuationContext {
            private Double beta;
            private Double costOfEquity;
            private HistoricalValuationRange historicalPerRange;
            private HistoricalValuationRange historicalPbrRange;
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

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PeerComparison {
        private String sectorAvgPer;
        private String status;
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
}
