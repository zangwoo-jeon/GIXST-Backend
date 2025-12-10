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
public class StockFinancialRank {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String stockCode;
    private String stockName;

    private String stacYymm; // 결산 년월

    @Column(precision = 20, scale = 2)
    private BigDecimal saleAccount; // 매출액

    @Column(precision = 20, scale = 2)
    private BigDecimal saleTotlPrfi; // 매출총이익

    @Column(precision = 20, scale = 2)
    private BigDecimal operatingProfit; // 영업이익

    @Column(precision = 20, scale = 2)
    private BigDecimal netIncome; // 순이익
}
