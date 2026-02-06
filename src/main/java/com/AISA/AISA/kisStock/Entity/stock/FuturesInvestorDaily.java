package com.AISA.AISA.kisStock.Entity.stock;

import com.AISA.AISA.kisStock.enums.FuturesMarketType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "futures_investor_daily", indexes = {
        @Index(name = "idx_futures_date_market", columnList = "date, market_type", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FuturesInvestorDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(name = "market_type", nullable = false, length = 20)
    private FuturesMarketType marketType;

    // 개인 순매수 금액
    @Column(name = "personal_net_buy_amount", precision = 18, scale = 4)
    private BigDecimal personalNetBuyAmount;

    // 외국인 순매수 금액
    @Column(name = "foreigner_net_buy_amount", precision = 18, scale = 4)
    private BigDecimal foreignerNetBuyAmount;

    // 기관계 순매수 금액
    @Column(name = "institution_net_buy_amount", precision = 18, scale = 4)
    private BigDecimal institutionNetBuyAmount;
}
