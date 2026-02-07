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
    private BigDecimal totalDailyProfit; // 일간 총 평가손익
    private BigDecimal totalDailyReturnRate; // 일간 총 수익률

    // 국내 주식 소계 (원화)
    private BigDecimal domesticTotalValue;
    private BigDecimal domesticTotalProfit;

    // 해외 주식 소계 (USD)
    private BigDecimal overseasTotalValue;
    private BigDecimal overseasTotalProfit;
    private BigDecimal overseasTotalProfitKrw;

    private List<PortStockResponse> portStocks;

    public PortfolioReturnResponse(Portfolio portfolio, List<PortStockResponse> portStocks) {
        this.portId = portfolio.getPortId();
        this.portName = portfolio.getPortName();
        this.portStocks = portStocks;

        this.totalValue = portStocks.stream()
                .map(stock -> stock.getTotalValueKrw() != null ? stock.getTotalValueKrw() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        this.totalInvestmentAmount = portStocks.stream()
                .map(stock -> {
                    BigDecimal investmentNative = stock.getAveragePrice()
                            .multiply(BigDecimal.valueOf(stock.getQuantity()));
                    BigDecimal rate = (stock.getExchangeRate() != null) ? stock.getExchangeRate() : BigDecimal.ONE;
                    return investmentNative.multiply(rate);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        this.totalDailyProfit = portStocks.stream()
                .map(stock -> stock.getDailyProfitKrw() != null ? stock.getDailyProfitKrw() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        this.totalProfitOrLoss = this.totalValue.subtract(this.totalInvestmentAmount);

        // 국내 소계 (KRW)
        this.domesticTotalValue = portStocks.stream()
                .filter(s -> "KRW".equals(s.getCurrency()))
                .map(s -> s.getTotalValue() != null ? s.getTotalValue() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        this.domesticTotalProfit = portStocks.stream()
                .filter(s -> "KRW".equals(s.getCurrency()))
                .map(s -> s.getProfit() != null ? s.getProfit() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 해외 소계 (USD)
        this.overseasTotalValue = portStocks.stream()
                .filter(s -> "USD".equals(s.getCurrency()))
                .map(s -> s.getTotalValue() != null ? s.getTotalValue() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        this.overseasTotalProfit = portStocks.stream()
                .filter(s -> "USD".equals(s.getCurrency()))
                .map(s -> s.getProfit() != null ? s.getProfit() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        this.overseasTotalProfitKrw = portStocks.stream()
                .filter(s -> "USD".equals(s.getCurrency()))
                .map(s -> s.getProfitKrw() != null ? s.getProfitKrw() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (this.totalInvestmentAmount.compareTo(BigDecimal.ZERO) == 0) {
            this.totalReturnRate = BigDecimal.ZERO;
        } else {
            this.totalReturnRate = this.totalProfitOrLoss
                    .divide(this.totalInvestmentAmount, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }

        // 일간 수익률 계산: (일간 손익 / (현재 평가금액 - 일간 손익)) * 100
        BigDecimal previousTotalValue = this.totalValue.subtract(this.totalDailyProfit);
        if (previousTotalValue.compareTo(BigDecimal.ZERO) == 0) {
            this.totalDailyReturnRate = BigDecimal.ZERO;
        } else {
            this.totalDailyReturnRate = this.totalDailyProfit
                    .divide(previousTotalValue, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }
    }
}
