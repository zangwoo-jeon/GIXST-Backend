package com.AISA.AISA.kisStock.Entity.Index;

import com.AISA.AISA.kisStock.enums.MarketType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "market_status_history", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "market_name", "date" })
})
public class MarketStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(name = "market_name", nullable = false)
    private MarketType marketName;

    @Column(nullable = false)
    private int risingCount;

    @Column(nullable = false)
    private int fallingCount;

    @Column(nullable = false)
    private int unchangedCount;

    @Builder
    public MarketStatusHistory(LocalDate date, MarketType marketName, int risingCount, int fallingCount,
            int unchangedCount) {
        this.date = date;
        this.marketName = marketName;
        this.risingCount = risingCount;
        this.fallingCount = fallingCount;
        this.unchangedCount = unchangedCount;
    }

    public void updateCounts(int risingCount, int fallingCount, int unchangedCount) {
        this.risingCount = risingCount;
        this.fallingCount = fallingCount;
        this.unchangedCount = unchangedCount;
    }
}
