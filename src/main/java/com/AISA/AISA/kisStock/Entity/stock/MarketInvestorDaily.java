package com.AISA.AISA.kisStock.Entity.stock;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "market_investor_daily", indexes = {
        @Index(name = "idx_market_date", columnList = "market_code, date", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketInvestorDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "market_code", nullable = false, length = 10)
    private String marketCode; // 0001: KOSPI, 1001: KOSDAX

    @Column(nullable = false)
    private LocalDate date;

    @Column(precision = 18, scale = 4)
    private BigDecimal indexPrice; // 업종 지수 현재가

    // Net Buy amounts in Million KRW (pbmn)
    @Column(precision = 18, scale = 4)
    private BigDecimal personalNetBuy;

    @Column(precision = 18, scale = 4)
    private BigDecimal foreignerNetBuy;

    @Column(precision = 18, scale = 4)
    private BigDecimal institutionNetBuy;

    // Detailed Institution Net Buy
    @Column(precision = 18, scale = 4)
    private BigDecimal securitiesNetBuy; // 증권

    @Column(precision = 18, scale = 4)
    private BigDecimal investmentTrustNetBuy; // 투신

    @Column(precision = 18, scale = 4)
    private BigDecimal privateFundNetBuy; // 사모펀드

    @Column(precision = 18, scale = 4)
    private BigDecimal bankNetBuy; // 은행

    @Column(precision = 18, scale = 4)
    private BigDecimal insuranceNetBuy; // 보험

    @Column(precision = 18, scale = 4)
    private BigDecimal merchantBankNetBuy; // 종금

    @Column(precision = 18, scale = 4)
    private BigDecimal pensionFundNetBuy; // 기금

    @Column(precision = 18, scale = 4)
    private BigDecimal etcCorporateNetBuy; // 기타법인

    @Column(precision = 18, scale = 4)
    private BigDecimal etcNetBuy; // 기타
}
