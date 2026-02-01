package com.AISA.AISA.kisStock.dto.Dividend;

import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StockDividendInfoDto {
    private Long id;
    private String stockCode;
    private String stockName;
    private String recordDate;
    private BigDecimal dividendAmount;
    private Double dividendRate;
    private String paymentDate;
    private BigDecimal totalExpectedDividend; // 포트폴리오 조회 시: 보유 수량 * 배당금
    private Integer quantity; // 포트폴리오 보유 수량
}
