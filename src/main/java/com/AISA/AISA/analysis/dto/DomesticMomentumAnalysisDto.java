package com.AISA.AISA.analysis.dto;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomesticMomentumAnalysisDto {
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
        private InvestorTrend investorTrend;
        private VolumeFilter volumeFilter;

        private String compositeSignal; // Strong Buy / Buy / Neutral / Wait / Sell
        private String rationale; // 핵심 근거 요약

        private String investmentAttractiveness; // Attractive / Neutral / Low
        private String action; // Buy / Wait / Watch / Sell
        private String reEntryCondition;
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
        private double rsi; // RTI (7~10)
        private double rsiSignal;
        private String rsiStatus; // 과매수 / 과매도 / 상승 우세 / 하락 우세 / 중립
        private MACD macd;
        private Stochastic stochastic; // (5/3)
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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvestorTrend {
        private BigDecimal foreigner5DayNetBuy;
        private BigDecimal institution5DayNetBuy;
        private String trendAlignment; // 일치 / 불일치 / 중립
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VolumeFilter {
        private Long currentVolume;
        private Long avgVolume5Day;
        private Long avgVolume20Day;
        private double volumeRatio; // (5일 평균 / 20일 평균) * 100
        private boolean isMet; // volumeRatio >= 70%
    }
}
