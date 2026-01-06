package com.AISA.AISA.kisStock.Entity.stock;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "stock_industry", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "stock_id", "sub_industry_id" })
})
public class StockIndustry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id", nullable = false)
    private Stock stock;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sub_industry_id", nullable = false)
    private SubIndustry subIndustry;

    // Optional: Is this the primary industry? (주력 산업 여부)
    private boolean isPrimary;

    public StockIndustry(Stock stock, SubIndustry subIndustry, boolean isPrimary) {
        this.stock = stock;
        this.subIndustry = subIndustry;
        this.isPrimary = isPrimary;
    }
}
