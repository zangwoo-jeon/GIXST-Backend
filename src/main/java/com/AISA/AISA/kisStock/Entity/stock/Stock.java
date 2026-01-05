package com.AISA.AISA.kisStock.Entity.stock;

import com.AISA.AISA.kisStock.enums.Industry; // Import
import com.AISA.AISA.kisStock.enums.SubIndustry; // Import
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Stock {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "stock_id")
    private Long stockId;

    @Column(nullable = false, unique = true)
    private String stockCode;

    @Column(nullable = false)
    private String stockName;

    @Column(nullable = false)
    private String marketName;

    @Enumerated(EnumType.STRING)
    private Industry industry; // 1차 산업

    @Enumerated(EnumType.STRING)
    private SubIndustry subIndustry; // 2차 세부 산업

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StockType stockType = StockType.DOMESTIC;

    public enum StockType {
        DOMESTIC, // 국내 주식
        FOREIGN_ETF // 국내 상장 해외 ETF
    }

    public static Stock create(String stockCode, String stockName, String marketName) {
        Stock stock = new Stock();
        stock.stockCode = stockCode;
        stock.stockName = stockName;
        stock.marketName = marketName;
        stock.stockType = StockType.DOMESTIC; // Default
        return stock;
    }

    public void updateInfo(String newName, String newMarketName) {
        this.stockName = newName;
        this.marketName = newMarketName;
    }

    public void updateStockType(StockType stockType) {
        this.stockType = stockType;
    }

    public void updateIndustry(Industry industry, SubIndustry subIndustry) {
        this.industry = industry;
        this.subIndustry = subIndustry;
    }
}
