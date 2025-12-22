package com.AISA.AISA.analysis.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class DiagnosisResultDto {
    private String portfolioId;
    private String diagnosisDate;
    private List<FactorAnalysisResult> factorAnalysis;
    private List<String> adviceList;

    @Getter
    @Builder
    public static class FactorAnalysisResult {
        private String factorName; // e.g., "KOSPI", "NASDAQ", "USD", "BOND"
        private double correlation;
        private String sensitivity; // e.g., "High", "Medium", "Low", "Inverse"
        private String description;

        // New Risk Metrics
        private double volatility; // Annualized Volatility
        private double mdd; // Maximum Drawdown
        private String correlationTrend; // "Increasing", "Decreasing", "Stable"
        private String scenarioAnalysis; // e.g., "Adding 5% reduces risk by 0.2%"

        // Advanced Metrics
        private double beta; // Sensitivity
        private double downsideCorrelation; // Crisis Correlation
    }
}
