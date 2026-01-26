package com.AISA.AISA.analysis.dto;

import com.AISA.AISA.analysis.dto.ValuationBaseDto.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

public class OverseasValuationDto {

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
        private String targetReturn;
        private DiscountRateInfo discountRate;

        private ValuationResult srim;
        private ValuationResult per;
        private ValuationResult pbr;

        @JsonIgnore
        private ValuationBand band;
        private Summary summary;

        private OverseasAnalysisDetails overseasAnalysisDetails;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OverseasAnalysisDetails {
        private String investmentTerm;
        private List<String> catalysts;
        private List<String> risks;

        @JsonIgnore
        private PriceModel priceModel;
        private QualityMetrics qualityMetrics;

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
            private String pegRatio;
            private String evEbitda;
            private String shareholderYield;
        }
    }
}
