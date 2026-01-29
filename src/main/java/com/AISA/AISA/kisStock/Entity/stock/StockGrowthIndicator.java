package com.AISA.AISA.kisStock.Entity.stock;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Table(name = "stock_growth_indicator", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "stockCode", "stacYymm", "divCode" })
}, indexes = {
        @Index(name = "idx_sgi_sales_yoy", columnList = "divCode, salesYoY DESC"),
        @Index(name = "idx_sgi_sales_cagr", columnList = "divCode, sales3YCagr DESC"),
        @Index(name = "idx_sgi_op_yoy", columnList = "divCode, opYoY DESC"),
        @Index(name = "idx_sgi_op_cagr", columnList = "divCode, op3YCagr DESC"),
        @Index(name = "idx_sgi_eps_yoy", columnList = "divCode, epsYoY DESC"),
        @Index(name = "idx_sgi_eps_cagr", columnList = "divCode, eps3YCagr DESC")
})
public class StockGrowthIndicator {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String stockCode;

    @Column(nullable = false)
    private String stacYymm; // 결산 년월

    @Column(nullable = false)
    private String divCode; // 0:년, 1:분기

    // Sales Growth
    @Column(precision = 19, scale = 4)
    private BigDecimal salesYoY;

    @Column(precision = 19, scale = 4)
    private BigDecimal sales3YCagr;

    // Operating Profit Growth
    @Column(precision = 19, scale = 4)
    private BigDecimal opYoY;

    @Column(precision = 19, scale = 4)
    private BigDecimal op3YCagr;

    // EPS Growth
    @Column(precision = 19, scale = 4)
    private BigDecimal epsYoY;

    @Column(precision = 19, scale = 4)
    private BigDecimal eps3YCagr;

    @Column(nullable = false)
    private boolean isTurnaround; // 턴어라운드 (적자 -> 흑자) 여부

    @Column(nullable = false)
    private LocalDateTime calculatedAt; // 계산 시각 (스냅샷)

    public void updateMetrics(BigDecimal salesYoY, BigDecimal sales3YCagr,
            BigDecimal opYoY, BigDecimal op3YCagr,
            BigDecimal epsYoY, BigDecimal eps3YCagr,
            boolean isTurnaround) {
        this.salesYoY = salesYoY;
        this.sales3YCagr = sales3YCagr;
        this.opYoY = opYoY;
        this.op3YCagr = op3YCagr;
        this.epsYoY = epsYoY;
        this.eps3YCagr = eps3YCagr;
        this.isTurnaround = isTurnaround;
        this.calculatedAt = LocalDateTime.now();
    }
}
