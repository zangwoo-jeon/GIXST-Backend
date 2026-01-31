package com.AISA.AISA.kisOverseasStock.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Table(name = "overseas_stock_dividend_rank")
public class OverseasStockDividendRank {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ranking", nullable = false)
    private Integer rank;

    @Column(nullable = false)
    private String stockCode;

    @Column(nullable = false)
    private String stockName;

    @Column(nullable = false)
    private String dividendAmount;

    @Column(nullable = false)
    private String dividendRate;
}
