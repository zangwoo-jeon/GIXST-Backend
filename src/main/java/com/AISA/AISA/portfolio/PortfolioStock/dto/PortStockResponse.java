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

    public PortStockResponse(PortStock portStock) {
        this.portStockId = portStock.getId();
        this.stockCode = portStock.getStock().getStockCode();
        this.stockName = portStock.getStock().getStockName();
        this.quantity = portStock.getQuantity();
        this.averagePrice = portStock.getAveragePrice();
        this.sequence = portStock.getSequence();
    }
}
