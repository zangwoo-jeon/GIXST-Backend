package com.AISA.AISA.kisOverseasStock.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Table(name = "overseas_stock_shareholder_return_rank")
public class OverseasStockShareholderReturnRank {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ranking", nullable = false)
    private Integer rank;

    @Column(nullable = false)
    private String stockCode;

    @Column(nullable = false)
    private String stockName;

    @Column(precision = 20, scale = 2)
    private BigDecimal returnAmount; // Dividends + Buybacks (USD)

    @Column(nullable = false)
    private String returnRate; // (Dividends + Buybacks) / Market Cap * 100
}
