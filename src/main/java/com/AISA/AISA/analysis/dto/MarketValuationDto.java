package com.AISA.AISA.analysis.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    private ValuationAnalysis valuationAnalysis;
    private TrendAnalysis trendAnalysis;
    private InvestmentStrategy investmentStrategy; // [NEW] 종합 투자 전략

    private ValuationInfo valuation;
    private ScoreDetails scoreDetails;
    @JsonIgnore
    private InvestorTrendInfo investorTrend;
    private MetadataInfo metadata;
    private List<TimeSeriesPoint> timeSeries;
    private PredictionReport predictionReport;
    private ScenarioRecommendation scenarioRecommendation; // VERY_LOW일 때만 생성, 평소엔 null
    private TheoreticalAnchor theoreticalAnchor; // Shiller CAPE 기반 10년 기대수익률 (KNN과 독립, OOD에도 작동)
    private DomesticEconomy domesticEconomy; // 한국 내수 펀더멘털 (연체율 4종 + CSI) — 학계 임계값 기반 regime 판정
    // 모든 분석 결과를 통합한 종합 narrative — 4축(밸류에이션/추세/리스크/내수) + 종합 전략 + 행동 지침
    private ComprehensiveAnalysis comprehensiveAnalysis;

    @Getter
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValuationAnalysis {
        private BigDecimal score;
        private String state; // 기존 grade
        private ValuationSignal actionSignal; // 기존 valuationSignal
        private String strategyText; // 기존 valuationStrategy
    }

    @Getter
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrendAnalysis {
        private BigDecimal score;
        private String state; // 기존 trendDescription
        private TrendSignal actionSignal; // 기존 trendSignal
        private String strategyText; // 기존 trendStrategy
    }

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
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvestorTrendInfo {
        private Long individualNet5d;
        private Long foreignNet5d;
        private Long institutionalNet5d;

        private Long individualNet3d;
        private Long foreignNet3d;
        private Long institutionalNet3d;

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
        EXTREME_FEAR,
        UNDERVALUED,
        FAIR_VALUE,
        OVERHEATED,
        STRONG_OVERHEATED,
        EXTREME_GREED
    }

    public enum CombinedSignal {
        STRONG_BUY,
        ACCUMULATE,
        HOLD,
        CAUTION,
        AGGRESSIVE_SELL
    }

    @Getter
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvestmentStrategy {
        private CombinedSignal finalActionSignal;
        private String combinedStrategyText;
        // KNN outcomeDistribution이 매트릭스 결과를 명확히 반박해 신호가 약화된 경우에만 채워짐
        private CombinedSignal preKnnAdjustmentSignal;
        private List<String> knnAdjustmentReasons;
        // 미래 예측 불확실성 flag (강등과 무관, caveat 표시용)
        // oodFlag: KNN confidence가 VERY_LOW (유사 과거 사례 부족)
        // uncertaintyFlag: Bootstrap winRate 95% CI가 50%를 포함 (방향 통계적 유의성 부족)
        private Boolean oodFlag;
        private Boolean uncertaintyFlag;
        private List<String> uncertaintyReasons;
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
        // KNN 특성: 월별 샘플 시점의 5일 누적/평균값 (해당 시점의 시장 상태 보조 표현)
        private BigDecimal vkospi;
        private Long foreignNet5d;
        private BigDecimal breadth5d;
        // KNN 특성 6번째 — CPI YoY (%) (인플레이션 압력, macro regime change 선행 지표)
        private BigDecimal cpiYoy;
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
        private KnnStats knnStats; // KNN 신뢰도 및 OOD 감지 정보
        private OutcomeDistribution outcomeDistribution; // 30개 raw return의 비관/중간/낙관 시나리오
        private List<HistoricalMatchCase> topMatches; // KNN 30개 중 거리 가까운 상위 5개 사례
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistoricalMatchCase {
        private String date; // 사례 시점 (yyyy-MM-dd)
        private BigDecimal cape;
        private BigDecimal vkospi;
        private BigDecimal yieldGap;
        private Long foreignNet5d;
        private BigDecimal breadth5d;
        private BigDecimal forwardReturn; // 30일 후 수익률 (%)
        private BigDecimal distance; // z-score 공간 거리
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OutcomeDistribution {
        private ScenarioCase bearCase; // p10 — 비관
        private ScenarioCase baseCase; // p50 — 중간
        private ScenarioCase bullCase; // p90 — 낙관
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScenarioCase {
        @JsonProperty("return")
        private BigDecimal returnValue; // 시나리오 수익률 (%)
        private String label; // "비관" / "중간" / "낙관"
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScenarioRecommendation {
        private String summary; // 1-2문장 요약
        private ScenarioDetail bearCase;
        private ScenarioDetail baseCase;
        private ScenarioDetail bullCase;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScenarioDetail {
        private String narrative; // 역사적 맥락 기반 가설적 시나리오 서술
    }

    /**
     * Shiller CAPE 기반 10년 연환산 기대 수익률 추정.
     * 학계 표준 단순 모델: E[10Y annualized real return] ≈ 1/CAPE × 100 (earnings yield).
     * KNN과 독립적이라 OOD 상황에서도 작동 — "단기는 모르나 장기는 이렇다"의 anchor 역할.
     * S&P 500 백테스트(1881~) R²≈0.40 기반. 한국 시장 적용은 방향성 valid, 절대치는 약한 외삽.
     */
    @Getter
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TheoreticalAnchor {
        private BigDecimal expectedReturn10YAnnual; // 10년 연환산 기대 실질 수익률 (%)
        private BigDecimal lowerBound; // -1σ (대략 -2.5%p)
        private BigDecimal upperBound; // +1σ
        private String capeRegime; // UNDERVALUED / FAIR / EXPENSIVE / EXTREME
        private String interpretation; // 사용자용 1문장 해석
        private String basis; // 학술적 근거 설명
        private String explanation; // 현재 시장이 어떤 상황인지 평이하게 풀어 설명 (strategyText 톤, 2~3문장)
    }

    /**
     * 한국 내수 펀더멘털 — 연체율 4종 (가계/기업전체/대기업/중소기업) + 소비자심리지수.
     * 한국은행 ECOS 데이터 가용 시점이 2019-12부터라 KNN/timeSeries 통합은 안 함.
     * 학계/한국은행 공식 평시 임계값 기준으로 현재 시점 regime 판정.
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DomesticEconomy {
        private DelinquencyMetric delinquencyHousehold; // 가계대출 연체율
        private DelinquencyMetric delinquencyCorporate; // 기업대출 연체율 (전체)
        private DelinquencyMetric delinquencyLarge; // 대기업대출 연체율
        private DelinquencyMetric delinquencySmall; // 중소기업대출 연체율 (선행 지표)
        private ConsumerSentimentMetric consumerSentiment; // 소비자심리지수 CSI
        private String interpretation; // 종합 해석 (strategyText 톤, 2~4문장)
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DelinquencyMetric {
        private BigDecimal value; // 연체율 (%)
        private String asOfDate; // 데이터 기준일 (yyyy-MM-dd)
        private String regime; // NORMAL / BORDERLINE / RISK
        private String normalRange; // 평시 정상 범위 (예: "0.2~0.4%")
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConsumerSentimentMetric {
        private BigDecimal value; // CSI 값 (100 기준)
        private String asOfDate;
        private String regime; // OPTIMISTIC (≥110) / NEUTRAL (90~110) / PESSIMISTIC (<90)
        private String baseline; // "100 기준 (한국은행 소비자동향조사)"
    }

    /**
     * 모든 분석 결과를 통합한 종합 narrative.
     * 4축(밸류에이션/추세/리스크/내수) + 종합 전략 + 한 줄 요약 + 행동 지침.
     * 프론트엔드가 각 필드를 별도 카드로 렌더링하기 위해 구조화된 객체로 분리.
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComprehensiveAnalysis {
        private String overallSummary; // 전체 진단 요약 (3~4문장)
        private String valuationAxis; // 1. 밸류에이션 분석
        private String trendAxis; // 2. 추세 및 수급 분석
        private String riskAxis; // 3. 리스크 지표 분석
        private String domesticAxis; // 4. 실물 경제 분석
        private String strategyNarrative; // 종합 투자 전략 본문
        private String oneLineSummary; // 한 줄 요약
        private List<String> actionGuidelines; // 행동 지침 3가지
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KnnStats {
        private BigDecimal avgDistance; // K개 이웃의 평균 z-score 거리
        private BigDecimal minDistance; // K개 이웃 중 최소 거리 (가장 가까운 매칭)
        private KnnConfidence confidence; // HIGH / MEDIUM / LOW / VERY_LOW
        private boolean oodWarning; // true면 현재 상태가 학습 데이터 분포 밖
        private ReturnStats returnStats; // K개 이웃의 raw forwardReturn 분포 통계
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReturnStats {
        private BigDecimal min; // 최소 수익률 (%)
        private BigDecimal max; // 최대 수익률 (%)
        private BigDecimal mean; // 산술 평균 수익률 (%)
        private BigDecimal std; // 표준편차 (%)
    }

    public enum KnnConfidence {
        HIGH, // avgDistance < 1.0 — 유사 사례 풍부
        MEDIUM, // avgDistance < 1.5
        LOW, // avgDistance < 2.5
        VERY_LOW // avgDistance >= 2.5 — OOD, 예측 신뢰 불가
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProbabilityInfo {
        private double upProbability;
        private double downProbability;
        private String primaryReason;
        // Block Bootstrap 결과: 1000회 재샘플링 기반 mean return 분포
        private BootstrapDistribution distribution;
        // Block Bootstrap 결과: winRate의 95% 신뢰구간 [lower, upper]
        private List<BigDecimal> winRateCI;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BootstrapDistribution {
        private BigDecimal p10; // 10% 시나리오 평균 수익률 (하방 리스크)
        private BigDecimal p50; // 중간값
        private BigDecimal p90; // 90% 시나리오 평균 수익률 (상방 시나리오)
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
