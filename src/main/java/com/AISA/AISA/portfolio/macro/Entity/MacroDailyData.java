package com.AISA.AISA.portfolio.macro.Entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "macro_daily_data", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "stat_code", "item_code", "date" })
})
public class MacroDailyData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stat_code", nullable = false)
    private String statCode; // e.g., 731Y001 (Exchange Rate), 101Y004 (M2)

    @Column(name = "item_code", nullable = false)
    private String itemCode; // e.g., 0000001 (USD), BBHA00 (M2 Total)

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false, precision = 20, scale = 4)
    private BigDecimal value;
}
