package com.AISA.AISA.kisStock.Entity.stock;

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
@Table(name = "stock_financial_statement", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "stockCode", "stacYymm", "divCode" })
})
public class StockFinancialStatement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String stockCode;

    @Column(nullable = false)
    private String stacYymm; // 결산 년월

    @Column(nullable = false, length = 1, columnDefinition = "VARCHAR(1) DEFAULT '0'")
    private String divCode; // 0: 년, 1: 분기

    @Column(precision = 20, scale = 2)
    private BigDecimal saleAccount; // 매출액

    @Column(precision = 20, scale = 2)
    private BigDecimal operatingProfit; // 영업이익

    @Column(precision = 20, scale = 2)
    private BigDecimal netIncome; // 당기순이익

    @Column(nullable = false)
    @Builder.Default
    private Boolean isSuspended = false; // 거래 정지 여부

}
