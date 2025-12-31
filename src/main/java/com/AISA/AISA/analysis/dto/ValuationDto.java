package com.AISA.AISA.analysis.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

public class ValuationDto {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Request {
        private Double expectedTotalReturn; // 요구 수익률 (Default suggest: 0.08 ~ 0.10)
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Response {
        private String stockCode;
        private String stockName;
        private String currentPrice;
        private String targetReturn; // 적용된 할인율

        private ValuationResult srim;
        private ValuationResult per;
        private ValuationResult pbr;

        private ValuationBand band;
        private Summary summary;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Summary {
        private String overallVerdict; // BUY, SELL, HOLD, MIXED
        private String confidence; // HIGH, MEDIUM, LOW
        private String keyInsight; // One line summary

        // Phase 2
        private String valuationLogic; // Detailed logic description
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
        private String reason; // 적용 불가 사유 (e.g. "EPS 적자")
        private String roeType; // 사용된 ROE 타입 (e.g. "3Y_AVG", "LATEST")

        // Phase 2
        private Map<String, ValuationResult> scenarios; // CONSERVATIVE, BASE, OPTIMISTIC
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
        private String position; // 밴드 내 위치 (0~100%)

        // Phase 1.5
        private Map<String, Double> weights; // 모델별 가중치
    }
}
