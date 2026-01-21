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
@Table(name = "overseas_stock_financial_statement", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "stockCode", "stacYymm", "divCode" })
})
public class OverseasStockFinancialStatement {

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
    private BigDecimal totalRevenue; // 매출액 (Total Revenue)

    @Column(precision = 20, scale = 2)
    private BigDecimal operatingIncome; // 영업이익 (Operating Income)

    @Column(precision = 20, scale = 2)
    private BigDecimal netIncome; // 당기순이익 (Net Income)

    @Column(nullable = false)
    @Builder.Default
    private Boolean isSuspended = false; // 거래 정지 여부

}
