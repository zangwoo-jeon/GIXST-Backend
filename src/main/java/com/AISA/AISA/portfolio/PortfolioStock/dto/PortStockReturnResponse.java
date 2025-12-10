package com.AISA.AISA.portfolio.PortfolioStock.dto;

import com.AISA.AISA.portfolio.PortfolioStock.PortStock;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Getter
@NoArgsConstructor
public class PortStockReturnResponse extends PortStockResponse {
    private BigDecimal currentPrice;
    private BigDecimal totalValue; // 평가금액
    private BigDecimal investmentAmount; // 매수금액
    private BigDecimal profitOrLoss; // 평가손익
    private BigDecimal returnRate; // 수익률

    public PortStockReturnResponse(PortStock portStock, BigDecimal currentPrice) {
        super(portStock);
        this.currentPrice = currentPrice;
        this.investmentAmount = portStock.getAveragePrice().multiply(BigDecimal.valueOf(portStock.getQuantity()));
        this.totalValue = currentPrice.multiply(BigDecimal.valueOf(portStock.getQuantity()));
        this.profitOrLoss = this.totalValue.subtract(this.investmentAmount);

        if (this.investmentAmount.compareTo(BigDecimal.ZERO) == 0) {
            this.returnRate = BigDecimal.ZERO;
        } else {
            this.returnRate = this.profitOrLoss.divide(this.investmentAmount, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }
    }
}
