package com.AISA.AISA.kisStock.Entity.stock;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Table(name = "stock_dividend_rank")
public class StockDividendRank {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ranking", nullable = false)
    private String rank;

    @Column(nullable = false)
    private String stockCode;

    @Column(nullable = false)
    private String stockName;

    @Column(nullable = false)
    private String dividendAmount;

    @Column(nullable = false)
    private String dividendRate;

    // API does not provide recordDate in the rank list directly in the same format
    // always,
    // but based on previous DTO it was not explicitly used in domain logic yet.
    // We will store it if available or keep it simple as per DTO.
    // Looking at previous DTO, it had fields map to KisDividendRankApiResponse.
    // KisDividendRankApiResponse.Output has: rank, sht_cd, isin_name,
    // per_sto_divi_amt, divi_rate

}
