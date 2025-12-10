package com.AISA.AISA.portfolio.PortfolioStock.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
public class PortStockAddRequest {
    private String stockCode;
    private Integer quantity;
    private BigDecimal averagePrice;

    public PortStockAddRequest(String stockCode, Integer quantity, BigDecimal averagePrice) {
        this.stockCode = stockCode;
        this.quantity = quantity;
        this.averagePrice = averagePrice;
    }
}
