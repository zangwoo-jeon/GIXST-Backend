package com.AISA.AISA.kisStock.dto.Dividend;

import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StockDividendInfoDto {
    private String stockCode;
    private String stockName;
    private String recordDate;
    private BigDecimal dividendAmount;
    private Double dividendRate;
    private String paymentDate;
}
