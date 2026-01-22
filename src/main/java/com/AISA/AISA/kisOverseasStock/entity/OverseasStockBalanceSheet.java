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
@Builder
@Table(name = "overseas_stock_balance_sheet", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "stockCode", "stacYymm", "divCode" })
})
public class OverseasStockBalanceSheet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String stockCode;

    @Column(nullable = false)
    private String stacYymm; // 결산 년월 (YYYYMM)

    @Column(nullable = false, length = 1, columnDefinition = "VARCHAR(1) DEFAULT '0'")
    private String divCode; // 0: Annual (연간), 1: Quarterly (분기)

    @Column(precision = 20, scale = 2)
    private BigDecimal totalAssets; // 자산총계 (Total Assets)

    @Column(precision = 20, scale = 2)
    private BigDecimal totalLiabilities; // 부채총계 (Total Liabilities)

    @Column(precision = 20, scale = 2)
    private BigDecimal totalCapital; // 자본총계 (Total Equity)
}
