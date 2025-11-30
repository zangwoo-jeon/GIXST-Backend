package com.AISA.AISA.analysis.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RollingCorrelationDto {
    private String asset1Name;
    private String asset2Name;
    private int windowSize; // 윈도우 크기 (예: 90일)
    private List<RollingDataPoint> rollingData;

    @Getter
    @AllArgsConstructor
    public static class RollingDataPoint {
        private String date;
        private double correlation;
    }
}
