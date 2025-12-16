package com.AISA.AISA.kisStock.Entity.stock;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "stock_dividend")
public class StockDividend {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id")
    private Stock stock;

    @Column(nullable = false)
    private String recordDate; // 배당 기준일 (yyyyMMdd)

    @Column(nullable = false)
    private String paymentDate; // 배당 지급일 (yyyy/MM/dd)

    @Column(nullable = false)
    private BigDecimal dividendAmount; // 배당금 (원)

    @Column(nullable = false)
    private Double dividendRate; // 배당률 (%)

    @Column(nullable = true)
    private BigDecimal stockPrice; // 배당 기준일 당시 주가 (원) - 계산용

}
