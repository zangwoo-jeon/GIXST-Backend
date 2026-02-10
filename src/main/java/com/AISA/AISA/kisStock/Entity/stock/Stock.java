package com.AISA.AISA.kisStock.Entity.stock;

// import com.AISA.AISA.kisStock.enums.Industry; // Removed
// import com.AISA.AISA.kisStock.enums.SubIndustry; // Removed
import com.AISA.AISA.kisStock.enums.MarketType;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MarketType marketName;

    @OneToMany(mappedBy = "stock", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<StockIndustry> stockIndustries = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StockType stockType = StockType.DOMESTIC;

    @Column(nullable = false)
    private boolean isSuspended = false;

    @Column(name = "is_common", nullable = false)
    private boolean isCommon = true; // 보통주 여부

    @Column
    private LocalDate listingDate;

    public enum StockType {
        DOMESTIC, // 국내 주식
        FOREIGN_ETF, // 국내 상장 해외 ETF
        US_STOCK, // 미국 주식
        US_ETF // 미국 ETF
    }

    public static Stock create(String stockCode, String stockName, MarketType market) {
        Stock stock = new Stock();
        stock.stockCode = stockCode;
        stock.stockName = stockName;
        stock.marketName = market;
        stock.stockType = StockType.DOMESTIC; // Default
        return stock;
    }

    public static Stock createOverseas(String stockCode, String stockName, MarketType market) {
        Stock stock = new Stock();
        stock.stockCode = stockCode;
        stock.stockName = stockName;
        stock.marketName = market;
        stock.stockType = StockType.US_STOCK;
        return stock;
    }

    public void updateInfo(String newName, MarketType newMarket) {
        this.stockName = newName;
        this.marketName = newMarket;
    }

    public String getCurrency() {
        return this.marketName != null ? this.marketName.getCurrency() : "KRW";
    }

    public void updateStockType(StockType stockType) {
        this.stockType = stockType;
    }

    // Helper to add industry
    public void addIndustry(StockIndustry stockIndustry) {
        this.stockIndustries.add(stockIndustry);
    }

    public void clearIndustries() {
        this.stockIndustries.clear();
    }

    public void updateSuspensionStatus(boolean isSuspended) {
        this.isSuspended = isSuspended;
    }

    public void updateListingDate(LocalDate listingDate) {
        this.listingDate = listingDate;
    }

    public void updateIsCommon(boolean isCommon) {
        this.isCommon = isCommon;
    }
}
