package com.AISA.AISA.kisOverseasStock.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "overseas_stock_cash_flow", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "stockCode", "stacYymm", "divCode" })
})
public class OverseasStockCashFlow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String stockCode;

    @Column(nullable = false)
    private String stacYymm; // 결산 년월 (YYYYMM)

    @Column(nullable = false, length = 1, columnDefinition = "VARCHAR(1) DEFAULT '0'")
    private String divCode; // 0: Annual (연간), 1: Quarterly (분기)

    @Column(precision = 20, scale = 2)
    private BigDecimal repurchaseOfCapitalStock; // 자사주 매입 (Repurchase Of Capital Stock)

    @Column(precision = 20, scale = 2)
    private BigDecimal cashDividendsPaid; // 배당금 지급 (Cash Dividends Paid)

    @Column(precision = 5, scale = 2)
    private BigDecimal shareholderReturnRate; // 주주환원율 ( (자사주매입 + 배당금) / 순이익 * 100 )
}
