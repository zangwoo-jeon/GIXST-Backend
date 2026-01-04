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

    @Column(columnDefinition = "TEXT")
    private String valuationAnalysis; // 동적 분석 (가치평가 해석)

    // AI 분석 생성 당시의 기준 주가 (가격 급변 시 갱신 트리거용)
    @Column(precision = 20, scale = 2)
    private BigDecimal referencePrice;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdDate;

    @Column(nullable = false)
    private LocalDateTime lastModifiedDate; // 동적(Valuation) 분석 마지막 수정일

    // 24시간 만료 체크 등을 위한 편의 메서드
    public boolean isExpired(int hours) {
        return lastModifiedDate.plusHours(hours).isBefore(LocalDateTime.now());
    }

    // Display Layer Fields (Optimized for Query & Response)
    private String displayVerdict; // "HOLD"
    private String displayLabel; // "관망"

    @Column(columnDefinition = "TEXT")
    private String displaySummary; // "한 줄 요약..."

    private String displayRisk; // "MEDIUM"

    public void updateValuationAnalysis(String valuationAnalysis, BigDecimal referencePrice,
            String displayVerdict, String displayLabel, String displaySummary, String displayRisk) {
        this.valuationAnalysis = valuationAnalysis;
        this.referencePrice = referencePrice;
        this.displayVerdict = displayVerdict;
        this.displayLabel = displayLabel;
        this.displaySummary = displaySummary;
        this.displayRisk = displayRisk;
        this.lastModifiedDate = LocalDateTime.now();
    }

}
