package com.AISA.AISA.portfolio.PortfolioStock.dto;

import com.AISA.AISA.portfolio.PortfolioGroup.Portfolio;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@Getter
@NoArgsConstructor
public class PortfolioReturnResponse {
    private UUID portId;
    private String portName;
    private BigDecimal totalValue; // 총 평가금액
    private BigDecimal totalInvestmentAmount; // 총 매수금액
    private BigDecimal totalProfitOrLoss; // 총 평가손익
    private BigDecimal totalReturnRate; // 총 수익률
    private List<PortStockReturnResponse> stockReturns;

    public PortfolioReturnResponse(Portfolio portfolio, List<PortStockReturnResponse> stockReturns) {
        this.portId = portfolio.getPortId();
        this.portName = portfolio.getPortName();
        this.stockReturns = stockReturns;

        this.totalValue = stockReturns.stream()
                .map(PortStockReturnResponse::getTotalValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        this.totalInvestmentAmount = stockReturns.stream()
                .map(PortStockReturnResponse::getInvestmentAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        this.totalProfitOrLoss = this.totalValue.subtract(this.totalInvestmentAmount);

        if (this.totalInvestmentAmount.compareTo(BigDecimal.ZERO) == 0) {
            this.totalReturnRate = BigDecimal.ZERO;
        } else {
            this.totalReturnRate = this.totalProfitOrLoss
                    .divide(this.totalInvestmentAmount, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }
    }
}
