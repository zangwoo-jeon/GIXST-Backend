package com.AISA.AISA.kisStock.Entity.stock;

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
@Builder(toBuilder = true)
@Table(name = "stock_financial_ratio", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "stockCode", "stacYymm", "divCode" })
})
public class StockFinancialRatio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String stockCode;

    @Column(nullable = false)
    private String stacYymm; // 결산 년월

    @Column(nullable = false, length = 1, columnDefinition = "VARCHAR(1) DEFAULT '0'")
    private String divCode; // 0: 년, 1: 분기

    // 비율 데이터 (퍼센트 단위, 소수점 저장)
    @Column(precision = 10, scale = 2)
    private BigDecimal roe; // ROE (자기자본이익률)

    @Column(precision = 10, scale = 2)
    private BigDecimal eps; // EPS (주당순이익)

    @Column(precision = 10, scale = 2)
    private BigDecimal bps; // BPS (주당순자산가치)

    @Column(precision = 10, scale = 2)
    private BigDecimal debtRatio; // 부채비율 (lblt_rate)

    @Column(precision = 10, scale = 2)
    private BigDecimal reserveRatio; // 유보율 (rsrv_rate)

    @Column(precision = 10, scale = 2)
    private BigDecimal salesGrowth; // 매출액 증가율 (grs)

    @Column(precision = 10, scale = 2)
    private BigDecimal operatingProfitGrowth; // 영업이익 증가율 (bsop_prfi_inrt)

    @Column(precision = 10, scale = 2)
    private BigDecimal netIncomeGrowth; // 순이익 증가율 (ntin_inrt)

    // 가치 투자 지표 (Price-based) - Update시 현재가 기준 계산값 저장
    @Column(precision = 10, scale = 2)
    private BigDecimal per; // Price Earnings Ratio

    @Column(precision = 10, scale = 2)
    private BigDecimal pbr; // Price Book-value Ratio

    @Column(precision = 10, scale = 2)
    private BigDecimal psr; // Price Sales Ratio (Optional, requires Sales per Share)

    @Column(nullable = false)
    @Builder.Default
    private boolean isSuspended = false; // 거래 정지 여부
}
