package com.AISA.AISA.portfolio.PortfolioStock.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
public class PortStockUpdateRequest {
    private Integer quantity;
    private BigDecimal averagePrice;
    private Integer sequence;

    public PortStockUpdateRequest(Integer quantity, BigDecimal averagePrice, Integer sequence) {
        this.quantity = quantity;
        this.averagePrice = averagePrice;
        this.sequence = sequence;
    }
}
