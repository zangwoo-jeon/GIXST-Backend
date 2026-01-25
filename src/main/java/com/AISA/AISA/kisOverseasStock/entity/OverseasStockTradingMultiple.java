package com.AISA.AISA.kisOverseasStock.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
@Table(name = "overseas_stock_trading_multiple")
public class OverseasStockTradingMultiple {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String stockCode;

    // PEG Ratio (Price/Earnings to Growth)
    // Yahoo: pegRatio
    @Column(columnDefinition = "DOUBLE")
    private Double pegRatio;

    // EV/EBITDA (Enterprise Value / EBITDA)
    // Yahoo: enterpriseToEbitda
    @Column(columnDefinition = "DOUBLE")
    private Double evEbitda;

    @LastModifiedDate
    private LocalDateTime lastUpdated;

    public void update(Double pegRatio, Double evEbitda) {
        this.pegRatio = pegRatio;
        this.evEbitda = evEbitda;
    }
}
