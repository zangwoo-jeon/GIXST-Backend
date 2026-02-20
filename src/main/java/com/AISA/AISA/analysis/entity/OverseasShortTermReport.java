package com.AISA.AISA.analysis.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
@Table(name = "overseas_short_term_report", indexes = {
        @Index(name = "idx_short_term_stock_code", columnList = "stockCode", unique = true)
})
public class OverseasShortTermReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String stockCode;

    private String stockName;

    @Column(precision = 19, scale = 4)
    private BigDecimal lastPrice;

    private Integer lastRsi;

    @Column(precision = 19, scale = 6)
    private BigDecimal lastMacdHistogram;

    @Column(columnDefinition = "TEXT")
    private String fullReportJson;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdDate;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime lastModifiedDate;
}
