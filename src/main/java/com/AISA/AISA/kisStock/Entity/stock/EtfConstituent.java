package com.AISA.AISA.kisStock.Entity.stock;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "etf_constituent")
public class EtfConstituent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "etf_id", nullable = false)
    private Stock etf;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "constituent_id")
    private Stock constituent;

    @Column(name = "component_name")
    private String componentName;

    @Column(name = "component_symbol")
    private String componentSymbol;

    @Column(name = "weight", precision = 10, scale = 4)
    private BigDecimal weight;

    @Column(name = "last_updated")
    private LocalDateTime lastUpdated;

    @Builder
    public EtfConstituent(Stock etf, Stock constituent, String componentName,
            String componentSymbol, BigDecimal weight, LocalDateTime lastUpdated) {
        this.etf = etf;
        this.constituent = constituent;
        this.componentName = componentName;
        this.componentSymbol = componentSymbol;
        this.weight = weight;
        this.lastUpdated = lastUpdated;
    }

    public void updateWeight(BigDecimal weight, LocalDateTime lastUpdated) {
        this.weight = weight;
        this.lastUpdated = lastUpdated;
    }
}
