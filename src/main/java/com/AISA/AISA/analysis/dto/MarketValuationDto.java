package com.AISA.AISA.analysis.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.AISA.AISA.kisStock.enums.MarketType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class MarketValuationDto {
    private MarketType market;
    private String marketDescription;
    private BigDecimal totalScore;
    private String grade;
    private String strategy;

    private ValuationInfo valuation;
    private ScoreDetails scoreDetails;
    private InvestorTrendInfo investorTrend;
    private MetadataInfo metadata;
    private List<TimeSeriesPoint> timeSeries;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValuationInfo {
        private BigDecimal per;
        private BigDecimal pbr;
        private BigDecimal cape;
        private BigDecimal yieldGap;
        private BigDecimal bondYield;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScoreDetails {
        private BigDecimal capeRangePosition; // 0-100% (Absolute position between Min and Max)
        private BigDecimal distributionPercentile; // 0-100% (Statistical Rank: % of data points below current)
        private BigDecimal yieldGapScore; // 40 max
        private BigDecimal deviationScore; // 20 max
        private Boolean yieldGapInversion; // Flag for negative yield gap
        private Boolean dataDistortionWarning; // Flag for structural change
        private SentimentSignal sentimentSignal;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvestorTrendInfo {
        private Long individualNet5d;
        private Long foreignNet5d;
        private Long institutionalNet5d;

        private Double foreignRelativeStrength; // (5d avg / 20d avg)
        private Double institutionalRelativeStrength;

        private TrendDirection foreignTrend;
        private TrendDirection individualTrend;
        private TrendDirection institutionalTrend;
    }

    public enum TrendDirection {
        BUYING_ACCELERATED,
        BUYING_SLOWED,
        SELLING_ACCELERATED,
        SELLING_SLOWED,
        NEUTRAL
    }

    public enum SentimentSignal {
        INDIVIDUAL_FOMO,
        SMART_MONEY_INFLOW,
        HEALTHY_BULL,
        SHORT_COVERING_SUSPECTED,
        STAGNANT,
        NEUTRAL
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetadataInfo {
        private long stockCount;
        private String totalMarketCap;
        private BigDecimal dataCoverage;
        private String updatedAt;
        private HistoricalStats historicalStats;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistoricalStats {
        private BigDecimal tenYearHigh;
        private BigDecimal tenYearLow;
        private BigDecimal tenYearAvg;
        private BigDecimal tenYearMedian;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimeSeriesPoint {
        private String date;
        private BigDecimal cape;
        private BigDecimal yieldGap;
        @JsonIgnore
        private BigDecimal lowerBound;
        @JsonIgnore
        private BigDecimal upperBound;
    }
}
