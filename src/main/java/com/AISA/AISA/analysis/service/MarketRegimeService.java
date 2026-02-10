package com.AISA.AISA.analysis.service;

import lombok.Builder;
import lombok.Getter;
import org.springframework.stereotype.Service;

@Service
public class MarketRegimeService {

    public enum RegimeType {
        STRONG_TREND, WEAK_TREND, SIDEWAYS
    }

    @Getter
    @Builder
    public static class RegimeResult {
        private final RegimeType type;
        private final double confidence; // 0~100
        private final boolean overextended; // Price/MA20 > 1.15
    }

    public RegimeResult determineRegime(double currentPrice, double ma20, double ma60, double smoothedSlope,
            double relativeStrength) {
        double priceToMa20Ratio = currentPrice / ma20;
        boolean overextended = priceToMa20Ratio > 1.15;

        // Strong Trend: Price > MA20 > MA60 AND Slope > 0 AND RS > 55
        if (currentPrice > ma20 && ma20 > ma60 && smoothedSlope > 0 && relativeStrength > 55) {
            return RegimeResult.builder()
                    .type(RegimeType.STRONG_TREND)
                    .confidence(calculateConfidence(currentPrice, ma20, ma60, smoothedSlope, relativeStrength,
                            RegimeType.STRONG_TREND))
                    .overextended(overextended)
                    .build();
        }

        // Sideways: MA20/MA60 distance is small AND RS is neutral (45-55)
        double maDistance = Math.abs(ma20 - ma60) / ma60;
        if (maDistance < 0.02 && relativeStrength >= 45 && relativeStrength <= 55) {
            return RegimeResult.builder()
                    .type(RegimeType.SIDEWAYS)
                    .confidence(80.0) // Simplified for now
                    .overextended(overextended)
                    .build();
        }

        // Weak Trend: In-between / Uncertain
        return RegimeResult.builder()
                .type(RegimeType.WEAK_TREND)
                .confidence(60.0)
                .overextended(overextended)
                .build();
    }

    private double calculateConfidence(double price, double ma20, double ma60, double slope, double rs,
            RegimeType type) {
        // Placeholder for simple confidence calculation
        if (type == RegimeType.STRONG_TREND) {
            double c = 70.0;
            if (rs > 65)
                c += 10;
            if (slope > (ma20 * 0.001))
                c += 10;
            if (price > ma20 * 1.02)
                c += 10;
            return Math.min(100, c);
        }
        return 50.0;
    }
}
