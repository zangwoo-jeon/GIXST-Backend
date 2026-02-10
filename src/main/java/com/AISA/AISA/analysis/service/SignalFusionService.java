package com.AISA.AISA.analysis.service;

import lombok.Builder;
import lombok.Getter;
import org.springframework.stereotype.Service;

@Service
public class SignalFusionService {

    @Getter
    @Builder
    public static class SignalResult {
        private final double finalScore;
        private final String stochasticZone; // 5-stage
        private final double stochasticStrength;
        private final String crossType; // GOLDEN, DEAD, NONE
    }

    public SignalResult fuseSignals(
            double rsi,
            double slowK,
            double slowD,
            double kSlope,
            double volumeZScore,
            MarketRegimeService.RegimeResult regime) {
        // 1. Stochastic Signal Strength
        // strength = abs(K-D) * 0.5 + K slope * 0.3 + zone weight * 0.2
        double kDiff = slowK - slowD;
        double stochZoneWeight = calculateZoneWeight(slowK);
        double stochStrength = (Math.abs(kDiff) * 0.5) + (Math.abs(kSlope) * 0.3) + (stochZoneWeight * 20.0); // scaled
                                                                                                              // to
                                                                                                              // 0~100ish

        String crossType = "NONE";
        if (kDiff > 0 && kSlope > 0)
            crossType = "GOLDEN";
        else if (kDiff < 0 && kSlope < 0)
            crossType = "DEAD";

        // 2. Zone Definition (5-stage)
        String zone = determineStochZone(slowK);

        // 3. Dynamic Weighting
        double stochWeight = 0.15; // Default Weak Trend
        if (regime.getType() == MarketRegimeService.RegimeType.SIDEWAYS) {
            stochWeight = 0.25;
        } else if (regime.getType() == MarketRegimeService.RegimeType.STRONG_TREND) {
            stochWeight = 0.10;
        }

        // 4. Base Components
        double rsiComponent = rsi;
        double stochComponent = slowK; // Base is K value

        // Adjust Stoch Component based on Signal & Regime
        if ("GOLDEN".equals(crossType)) {
            double boost = (stochStrength / 100.0) * 20.0;
            if (volumeZScore > 1.0)
                boost *= 1.3; // Volume Expansion filter
            stochComponent = Math.min(100, stochComponent + boost);
        } else if ("DEAD".equals(crossType)) {
            double penalty = (stochStrength / 100.0) * 20.0;
            stochComponent = Math.max(0, stochComponent - penalty);
        }

        // Bull-Market Exception: If Strong Trend & NOT Overextended, ignore Overbought
        // penalty
        if (regime.getType() == MarketRegimeService.RegimeType.STRONG_TREND && !regime.isOverextended()) {
            if (slowK > 80) {
                // Instead of penalizing for being high, we treat it as strong momentum
                stochComponent = Math.max(85, stochComponent);
            }
        } else {
            // Normal or Overextended: Overbought is a risk
            if (slowK > 80)
                stochComponent -= 10;
        }

        // 5. RSI Synergy
        // RSI > 50 & Increasing + Stoch Golden Cross = High
        if (rsi > 50 && "GOLDEN".equals(crossType)) {
            rsiComponent = Math.min(100, rsiComponent + 10);
        } else if (rsi < 50 && "DEAD".equals(crossType)) {
            rsiComponent = Math.max(0, rsiComponent - 10);
        }

        // 6. Final Fusion (Simplified weighted average for demonstration)
        // [V4.2 Original Weight Ref: Supply(40) + RS(30) + Technical(30)]
        // We are enhancing the Technical part (30%) and maybe influencing RS.
        // For this service, we'll return a "Fused Technical Score" (0~100)
        double finalScore = (rsiComponent * (1 - stochWeight)) + (stochComponent * stochWeight);

        return SignalResult.builder()
                .finalScore(finalScore)
                .stochasticZone(zone)
                .stochasticStrength(stochStrength)
                .crossType(crossType)
                .build();
    }

    private String determineStochZone(double k) {
        if (k <= 20)
            return "OVERSOLD";
        if (k <= 40)
            return "WEAK";
        if (k <= 60)
            return "NEUTRAL";
        if (k <= 80)
            return "STRONG";
        return "OVERBOUGHT";
    }

    private double calculateZoneWeight(double k) {
        if (k <= 20 || k >= 80)
            return 1.0; // Extreme zones are more significant
        return 0.5;
    }
}
