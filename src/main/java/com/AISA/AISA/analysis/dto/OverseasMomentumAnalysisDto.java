package com.AISA.AISA.analysis.dto;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverseasMomentumAnalysisDto {
    private String stockCode;
    private String stockName;
    private String currentPrice;
    private ShortTermVerdict shortTermVerdict;
    private List<String> explanation; // AI-generated qualitative explanation

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShortTermVerdict {
        private int trendScore; // 종합 추세 점수 (0-100)
        private String trendDirection; // Bullish / Bearish / Sideways
        private Momentum momentum;
        private String volatility; // 높음 / 보통 / 낮음
        private SupportResistance supportResistance;
        private TechnicalIndicators technicalIndicators;
        private String valuationSignal; // Cheap / Fair / Expensive (Relative to history)
        private String investmentAttractiveness; // Strong / Attractive / Neutral / Low / Avoid
        private String action; // Buy / Wait / Watch / Sell
        private String reEntryCondition; // 단기 재진입 조건
        private String holdingHorizon; // 1~3개월
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SupportResistance {
        private BigDecimal supportLevel;
        private BigDecimal resistanceLevel;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TechnicalIndicators {
        private double rsi; // RSI(14) with Wilder's smoothing
        private double rsiSignal; // RSI Signal (6-period SMA of RSI)
        private String rsiStatus; // 과매수 / 과매도 / 상승 우세 / 하락 우세 / 중립
        private MACD macd;
        private Stochastic stochastic;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MACD {
        private BigDecimal macdLine;
        private BigDecimal signalLine;
        private BigDecimal histogram;
        private String status; // 상승 우세 / 하락 우세 / 중립
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Stochastic {
        private double k;
        private double d;
        private String status; // 과매수 / 과매도 / 상승 우세 / 하락 우세 / 중립
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Momentum {
        private String direction;   // 상승 / 하락 / 횡보
        private String strength;    // 강함 / 보통 / 약함
        private String trend;       // 가속 / 유지 / 둔화
        private int confidence;     // 0~100
        private String summary;     // 한 줄 요약
    }
}
