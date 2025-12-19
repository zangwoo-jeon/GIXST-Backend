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

    private BigDecimal currentPrice;
    private BigDecimal totalValue;
    private BigDecimal profit;
    private BigDecimal profitRate;

    public PortStockResponse(PortStock portStock) {
        this.portStockId = portStock.getId();
        this.stockCode = portStock.getStock().getStockCode();
        this.stockName = portStock.getStock().getStockName();
        this.quantity = portStock.getQuantity();
        this.averagePrice = portStock.getAveragePrice();
        this.sequence = portStock.getSequence();
    }

    public PortStockResponse(PortStock portStock, BigDecimal currentPrice) {
        this(portStock);
        this.currentPrice = currentPrice;

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
        }
    }
}
