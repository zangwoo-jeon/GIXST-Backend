package com.AISA.AISA.kisOverseasStock.entity;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "overseas_stock_daily_data", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "stock_id", "date" })
})
public class OverseasStockDailyData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id", nullable = false)
    private Stock stock;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private BigDecimal closingPrice;

    @Column(nullable = false)
    private BigDecimal openingPrice;

    @Column(nullable = false)
    private BigDecimal highPrice;

    @Column(nullable = false)
    private BigDecimal lowPrice;

    private BigDecimal priceChange;

    private Double changeRate;

    @Builder
    public OverseasStockDailyData(Stock stock, LocalDate date, BigDecimal closingPrice, BigDecimal openingPrice,
            BigDecimal highPrice, BigDecimal lowPrice,
            BigDecimal priceChange, Double changeRate) {
        this.stock = stock;
        this.date = date;
        this.closingPrice = closingPrice;
        this.openingPrice = openingPrice;
        this.highPrice = highPrice;
        this.lowPrice = lowPrice;
        this.priceChange = priceChange;
        this.changeRate = changeRate;
    }
}
