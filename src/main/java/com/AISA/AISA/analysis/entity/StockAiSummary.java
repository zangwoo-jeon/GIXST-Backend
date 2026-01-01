package com.AISA.AISA.analysis.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
@Table(name = "stock_ai_summary", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "stockCode" })
})
public class StockAiSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String stockCode;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String aiAnalysis; // AI가 생성한 전체 리포트 텍스트

    // AI 분석 당시의 요약 정보 (버전 관리 및 변경 추적용, JSON String 등)
    @Column(columnDefinition = "TEXT")
    private String valuationSummaryJson;

    // AI 분석 생성 당시의 기준 주가 (가격 급변 시 갱신 트리거용)
    @Column(precision = 20, scale = 2)
    private BigDecimal referencePrice;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdDate;

    @Column(nullable = false)
    private LocalDateTime lastModifiedDate;

    // 24시간 만료 체크 등을 위한 편의 메서드
    public boolean isExpired(int hours) {
        return lastModifiedDate.plusHours(hours).isBefore(LocalDateTime.now());
    }

    public void updateAnalysis(String aiAnalysis, String valuationSummaryJson, BigDecimal referencePrice) {
        this.aiAnalysis = aiAnalysis;
        this.valuationSummaryJson = valuationSummaryJson;
        this.referencePrice = referencePrice;
        this.lastModifiedDate = LocalDateTime.now();
    }
}
