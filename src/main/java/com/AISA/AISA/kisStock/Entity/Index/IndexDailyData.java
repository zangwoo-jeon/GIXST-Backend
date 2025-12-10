package com.AISA.AISA.kisStock.Entity.Index;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "index_daily_data", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "market_name", "date" })
})
public class IndexDailyData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "market_name", nullable = false, length = 10)
    private String marketName; // KOSPI, KOSDAQ

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "opening_price", nullable = false, precision = 20, scale = 2)
    private BigDecimal openingPrice;

    @Column(name = "closing_price", nullable = false, precision = 20, scale = 2)
    private BigDecimal closingPrice;

    @Column(name = "high_price", nullable = false, precision = 20, scale = 2)
    private BigDecimal highPrice;

    @Column(name = "low_price", nullable = false, precision = 20, scale = 2)
    private BigDecimal lowPrice;

    @Column(name = "price_change", nullable = false, precision = 20, scale = 2)
    private BigDecimal priceChange;

    @Column(name = "change_rate", nullable = false)
    private Double changeRate;

    @Column(name = "volume", precision = 20, scale = 0)
    private BigDecimal volume;

    @Builder
    public IndexDailyData(String marketName, LocalDate date, BigDecimal openingPrice, BigDecimal closingPrice,
            BigDecimal highPrice, BigDecimal lowPrice, BigDecimal priceChange, Double changeRate, BigDecimal volume) {
        this.marketName = marketName;
        this.date = date;
        this.openingPrice = openingPrice;
        this.closingPrice = closingPrice;
        this.highPrice = highPrice;
        this.lowPrice = lowPrice;
        this.priceChange = priceChange;
        this.changeRate = changeRate;
        this.volume = volume;
    }

    public void updateInfo(BigDecimal closingPrice, BigDecimal openingPrice, BigDecimal highPrice, BigDecimal lowPrice,
            BigDecimal priceChange, Double changeRate, BigDecimal volume) {
        this.closingPrice = closingPrice;
        this.openingPrice = openingPrice;
        this.highPrice = highPrice;
        this.lowPrice = lowPrice;
        this.priceChange = priceChange;
        this.changeRate = changeRate;
        this.volume = volume;
    }
}
