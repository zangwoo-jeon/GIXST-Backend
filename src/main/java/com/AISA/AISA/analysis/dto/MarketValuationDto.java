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
    private BigDecimal valuationScore;
    private String grade;
    private String valuationStrategy;
    private String trendStrategy;

    private BigDecimal trendScore;
    private String trendDescription;

    private ValuationInfo valuation;
    private ScoreDetails scoreDetails;
    private InvestorTrendInfo investorTrend;
    private MetadataInfo metadata;
    private List<TimeSeriesPoint> timeSeries;
    private PredictionReport predictionReport;

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
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScoreDetails {
        private BigDecimal capeRangePosition; // 0-100% (Absolute position between Min and Max)
        private BigDecimal distributionPercentile; // 0-100% (Statistical Rank: % of data points below current)
        private BigDecimal yieldGapScore; // 40 max
        private BigDecimal deviationScore; // 20 max
        private Boolean yieldGapInversion; // Flag: current YG below market's own historical median
        private BigDecimal yieldGapPercentile; // 0-100% (Position within market's own YG distribution)
        private Boolean dataDistortionWarning; // Flag for structural change
        private ValuationSignal valuationSignal;
        private TrendSignal trendSignal;
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

        private Long commonRisingStockCount;
        private Long commonFallingStockCount;
        private BigDecimal commonMarketBreadthIndex;
        private BigDecimal breadth5dAvg;
        private BigDecimal breadth20dAvg;
        private BigDecimal breadth60dAvg;
        private String breadthDate;

        // VKOSPI & Futures
        private BigDecimal vkospi;
        private Long futuresForeignNet5d;
        private Long futuresIndividualNet5d;
        private Long futuresInstitutionalNet5d;
    }

    public enum TrendDirection {
        BUYING_ACCELERATED,
        BUYING_SLOWED,
        SELLING_ACCELERATED,
        SELLING_SLOWED,
        NEUTRAL
    }

    public enum ValuationSignal {
        UNDERVALUED_BUY, // Low Score + Buying
        OVERVALUED_CAUTION, // High Score + Major Exit
        VALUE_TRAP, // Low Score but persistent selling
        FAIR_VALUE, // Near median
        NEUTRAL
    }

    public enum TrendSignal {
        HEALTHY_BULL, // Price up + Breadth up + Foreigner buying
        BULL_TRAP, // Price up but Breadth/Volume diverging
        OVERSOLD_REBOUND, // Extreme low breadth + turning up
        PANIC_SELLING, // Sharp breadth drop + major exit
        STAGNANT, // Low volatility/sideways
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

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PredictionReport {
        private ProbabilityInfo shortTerm; // 1 week
        private ProbabilityInfo mediumTerm; // 1 month
        private ProbabilityInfo longTerm; // 3 months
        private HistoricalMatch historicalMatch;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProbabilityInfo {
        private double upProbability;
        private double downProbability;
        private String primaryReason;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistoricalMatch {
        private int totalMatches; // Total similar periods found
        private int positiveOutcomes; // Count of positive returns after period
        private int negativeOutcomes; // Count of negative returns
        private double winRate; // % of positive outcomes
        private double averageReturn; // Avg return after period
    }
}
