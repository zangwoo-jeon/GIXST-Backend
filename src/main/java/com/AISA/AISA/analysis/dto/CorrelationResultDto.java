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
public class CorrelationResultDto {
    private String asset1Name;
    private String asset2Name;
    private double coefficient; // 상관계수 (-1.0 ~ 1.0)
    private double pValue; // 유의성 확률
    private int sampleSize; // 표본 크기
    private String description; // 해석 (예: 강한 양의 상관관계)
    private int bestLag; // 최적 시차 (일 단위, 예: -1, 0, 1)
    private List<DataPoint> points; // 산점도용 데이터

    @Getter
    @AllArgsConstructor
    public static class DataPoint {
        private String date;
        private double x; // Asset 1 Return
        private double y; // Asset 2 Return
    }
}
