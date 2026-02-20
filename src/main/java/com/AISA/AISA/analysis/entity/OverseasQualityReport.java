package com.AISA.AISA.analysis.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
@Table(name = "overseas_quality_report", indexes = {
        @Index(name = "idx_quality_stock_code", columnList = "stockCode", unique = true)
})
public class OverseasQualityReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String stockCode;

    private String stockName;

    private String currentPrice;

    private String marketCap;

    private String qualityGrade;

    private int qualityScore;

    private String valuationStatus;

    private String investmentAttractiveness;

    @Column(precision = 19, scale = 4)
    private java.math.BigDecimal lastPrice;

    private String lastStacYymm;

    @Column(columnDefinition = "TEXT")
    private String fullReportJson;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdDate;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime lastModifiedDate;
}
