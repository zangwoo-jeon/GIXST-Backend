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

    @Column(precision = 20, scale = 2)
    private BigDecimal marketCapUsd;

    @Column(precision = 20, scale = 2)
    private BigDecimal listedShares;

    private String currentPrice;
    private String priceChange;
    private String changeRate;
    private String changeSign;

    private LocalDateTime lastUpdated;

    public static StockMarketCap create(Stock stock, BigDecimal marketCap) {
        StockMarketCap entity = new StockMarketCap();
        entity.stock = stock;
        entity.marketCap = marketCap;
        entity.lastUpdated = LocalDateTime.now();
        return entity;
    }

    public static StockMarketCap createOverseas(Stock stock, BigDecimal marketCapKrw, BigDecimal marketCapUsd,
            BigDecimal listedShares, String price, String priceChange, String changeRate, String changeSign) {
        StockMarketCap entity = new StockMarketCap();
        entity.stock = stock;
        entity.marketCap = marketCapKrw;
        entity.marketCapUsd = marketCapUsd;
        entity.listedShares = listedShares;
        entity.currentPrice = price;
        entity.priceChange = priceChange;
        entity.changeRate = changeRate;
        entity.changeSign = changeSign;
        entity.lastUpdated = LocalDateTime.now();
        return entity;
    }

    public void updateMarketCap(BigDecimal marketCap) {
        this.marketCap = marketCap;
        this.lastUpdated = LocalDateTime.now();
    }

    public void updateDomesticInfo(BigDecimal marketCap, BigDecimal listedShares, String price, String priceChange,
            String changeRate, String changeSign) {
        this.marketCap = marketCap;
        this.listedShares = listedShares;
        this.currentPrice = price;
        this.priceChange = priceChange;
        this.changeRate = changeRate;
        this.changeSign = changeSign;
        this.lastUpdated = LocalDateTime.now();
    }

    public void updateOverseasInfo(BigDecimal marketCapKrw, BigDecimal marketCapUsd, BigDecimal listedShares,
            String price, String priceChange, String changeRate, String changeSign) {
        this.marketCap = marketCapKrw;
        this.marketCapUsd = marketCapUsd;
        this.listedShares = listedShares;
        this.currentPrice = price;
        this.priceChange = priceChange;
        this.changeRate = changeRate;
        this.changeSign = changeSign;
        this.lastUpdated = LocalDateTime.now();
    }

    public void updateListedShares(BigDecimal listedShares) {
        this.listedShares = listedShares;
    }
}
