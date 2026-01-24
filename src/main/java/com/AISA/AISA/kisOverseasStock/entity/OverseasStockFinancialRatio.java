package com.AISA.AISA.kisOverseasStock.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Table(name = "overseas_stock_financial_ratio", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "stockCode", "stacYymm", "divCode" })
})
public class OverseasStockFinancialRatio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String stockCode;

    @Column(nullable = false)
    private String stacYymm; // 결산 년월 (YYYYMM)

    @Column(nullable = false, length = 1, columnDefinition = "VARCHAR(1) DEFAULT '0'")
    private String divCode; // 0: Annual (연간), 1: Quarterly (분기)

    // 가치 투자 지표 (Price-based)
    @Column(precision = 10, scale = 2)
    private BigDecimal per; // Price Earnings Ratio

    @Column(precision = 10, scale = 2)
    private BigDecimal pbr; // Price Book-value Ratio

    @Column(precision = 10, scale = 2)
    private BigDecimal psr; // Price Sales Ratio

    @Column(precision = 10, scale = 2)
    private BigDecimal roe; // Return On Equity

    // EPS (USD/KRW)
    @Column(precision = 20, scale = 4)
    private BigDecimal epsUsd;

    @Column(precision = 20, scale = 2)
    private BigDecimal epsKrw;

    // BPS (USD/KRW)
    @Column(precision = 20, scale = 4)
    private BigDecimal bpsUsd;

    @Column(precision = 20, scale = 2)
    private BigDecimal bpsKrw;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isSuspended = false; // 거래 정지 여부
}
