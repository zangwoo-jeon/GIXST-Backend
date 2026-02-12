package com.AISA.AISA.kisStock.Entity.stock;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "etf_detail")
public class EtfDetail {

    @Id
    @Column(name = "stock_id")
    private Long stockId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "stock_id")
    private Stock stock;

    @Column(name = "underlying_index")
    private String underlyingIndex; // 기초지수명

    @Column(name = "index_provider")
    private String indexProvider; // 지수산출기관

    @Column(name = "tracking_multiplier")
    private Double trackingMultiplier; // 추적배수

    @Column(name = "replication_method")
    private String replicationMethod; // 복제방법

    @Column(name = "manager")
    private String manager; // 운용사

    @Column(name = "total_expense", precision = 10, scale = 6)
    private BigDecimal totalExpense; // 총보수

    @Column(name = "tax_type")
    private String taxType; // 과세유형

    @Column(name = "listing_date")
    private LocalDate listingDate; // 상장일

    @Builder
    public EtfDetail(Stock stock, String underlyingIndex, String indexProvider,
            Double trackingMultiplier, String replicationMethod,
            String manager, BigDecimal totalExpense, String taxType, LocalDate listingDate) {
        this.stock = stock;
        this.underlyingIndex = underlyingIndex;
        this.indexProvider = indexProvider;
        this.trackingMultiplier = trackingMultiplier;
        this.replicationMethod = replicationMethod;
        this.manager = manager;
        this.totalExpense = totalExpense;
        this.taxType = taxType;
        this.listingDate = listingDate;
    }

    public void updateMetadata(String underlyingIndex, String indexProvider,
            Double trackingMultiplier, String replicationMethod,
            String manager, BigDecimal totalExpense, String taxType, LocalDate listingDate) {
        this.underlyingIndex = underlyingIndex;
        this.indexProvider = indexProvider;
        this.trackingMultiplier = trackingMultiplier;
        this.replicationMethod = replicationMethod;
        this.manager = manager;
        this.totalExpense = totalExpense;
        this.taxType = taxType;
        this.listingDate = listingDate;
    }
}
