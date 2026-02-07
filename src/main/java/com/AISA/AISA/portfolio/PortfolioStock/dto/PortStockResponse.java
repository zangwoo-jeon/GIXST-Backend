package com.AISA.AISA.portfolio.PortfolioStock.dto;

import com.AISA.AISA.portfolio.PortfolioStock.PortStock;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@NoArgsConstructor
public class PortStockResponse {
    private UUID portStockId;
    private String stockCode;
    private String stockName;
    private Integer quantity;
    private BigDecimal averagePrice;
    private Integer sequence;
    private String currency; // 통화 (KRW, USD 등)
    private BigDecimal currentPrice;
    private BigDecimal totalValue;
    private BigDecimal profit;
    private BigDecimal profitRate;
    private BigDecimal dailyProfit;
    private BigDecimal dailyChangeRate;

    // KRW 환산 값
    private BigDecimal currentPriceKrw;
    private BigDecimal totalValueKrw;
    private BigDecimal profitKrw;
    private BigDecimal dailyProfitKrw;
    private BigDecimal exchangeRate;

    public PortStockResponse(PortStock portStock) {
        this.portStockId = portStock.getId();
        this.stockCode = portStock.getStock().getStockCode();
        this.stockName = portStock.getStock().getStockName();
        this.quantity = portStock.getQuantity();
        this.averagePrice = portStock.getAveragePrice();
        this.sequence = portStock.getSequence();
        this.currency = portStock.getStock().getCurrency();
    }

    public PortStockResponse(PortStock portStock, BigDecimal currentPrice, BigDecimal dailyProfit,
            BigDecimal dailyChangeRate, BigDecimal exchangeRate) {
        this(portStock);
        this.currentPrice = currentPrice;
        this.dailyProfit = dailyProfit;
        this.dailyChangeRate = dailyChangeRate;
        this.exchangeRate = exchangeRate;

        if (currentPrice != null) {
            this.totalValue = currentPrice.multiply(BigDecimal.valueOf(this.quantity));

            BigDecimal diff = currentPrice.subtract(this.averagePrice);
            this.profit = diff.multiply(BigDecimal.valueOf(this.quantity));

            if (this.averagePrice.compareTo(BigDecimal.ZERO) != 0) {
                this.profitRate = diff.divide(this.averagePrice, 4, java.math.RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
            } else {
                this.profitRate = BigDecimal.ZERO;
            }

            // KRW 환산
            if (exchangeRate != null) {
                this.currentPriceKrw = currentPrice.multiply(exchangeRate);
                this.totalValueKrw = this.totalValue.multiply(exchangeRate);
                this.profitKrw = this.profit.multiply(exchangeRate);
                this.dailyProfitKrw = dailyProfit.multiply(exchangeRate);
            } else {
                // KRW인 경우 (exchangeRate이 1이거나 null일 수 있음)
                this.currentPriceKrw = currentPrice;
                this.totalValueKrw = this.totalValue;
                this.profitKrw = this.profit;
                this.dailyProfitKrw = dailyProfit;
            }
        }
    }
}
