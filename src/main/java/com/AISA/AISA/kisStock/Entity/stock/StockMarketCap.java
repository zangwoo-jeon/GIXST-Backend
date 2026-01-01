package com.AISA.AISA.kisStock.Entity.stock;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "stock_market_cap")
public class StockMarketCap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id", nullable = false, unique = true)
    private Stock stock;

    @Column(precision = 20, scale = 2)
    private BigDecimal marketCap;

    private LocalDateTime lastUpdated;

    public static StockMarketCap create(Stock stock, BigDecimal marketCap) {
        StockMarketCap entity = new StockMarketCap();
        entity.stock = stock;
        entity.marketCap = marketCap;
        entity.lastUpdated = LocalDateTime.now();
        return entity;
    }

    public void updateMarketCap(BigDecimal marketCap) {
        this.marketCap = marketCap;
        this.lastUpdated = LocalDateTime.now();
    }
}
