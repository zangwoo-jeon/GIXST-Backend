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
@Table(name = "stock_balance_sheet", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "stockCode", "stacYymm", "divCode" })
})
public class StockBalanceSheet {

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
    private BigDecimal totalAssets; // 자산총계

    @Column(precision = 20, scale = 2)
    private BigDecimal totalLiabilities; // 부채총계

    @Column(precision = 20, scale = 2)
    private BigDecimal totalCapital; // 자본총계
}
