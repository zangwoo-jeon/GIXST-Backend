package com.AISA.AISA.kisStock.Entity.stock;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder(toBuilder = true)
@Table(name = "stock_investor_daily", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "stock_id", "date" })
})
public class StockInvestorDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id", nullable = false)
    private Stock stock;

    @Column(nullable = false)
    private LocalDate date;

    // 외국인 순매수 거래대금 (frgn_ntby_tr_pbmn)
    @Column(nullable = false)
    private BigDecimal foreignerNetBuyAmount;

    // 개인 순매수 거래대금 (prsn_ntby_tr_pbmn)
    @Column(nullable = false)
    private BigDecimal personalNetBuyAmount;

    // 기관계 순매수 거래대금 (orgn_ntby_tr_pbmn)
    @Column(nullable = false)
    private BigDecimal institutionNetBuyAmount;

    // 기타법인 순매수 거래대금 (etc_corp_ntby_tr_pbmn)
    private BigDecimal etcCorporateNetBuyAmount;

    // 외국인 순매수 수량 (frgn_ntby_qty) [NEW]
    private Long foreignerNetBuyQuantity;

    // 개인 순매수 수량 (prsn_ntby_qty) [NEW]
    private Long personalNetBuyQuantity;

    // 기관계 순매수 수량 (orgn_ntby_qty) [NEW]
    private Long institutionNetBuyQuantity;


    // StockInvestorDaily.java 엔티티에 추가
    public void update(BigDecimal foreignerNetBuyAmount,
                       BigDecimal personalNetBuyAmount,
                       BigDecimal institutionNetBuyAmount,
                       BigDecimal etcCorporateNetBuyAmount,
                       Long foreignerNetBuyQuantity,
                       Long personalNetBuyQuantity,
                       Long institutionNetBuyQuantity) {
        this.foreignerNetBuyAmount = foreignerNetBuyAmount;
        this.personalNetBuyAmount = personalNetBuyAmount;
        this.institutionNetBuyAmount = institutionNetBuyAmount;
        this.etcCorporateNetBuyAmount = etcCorporateNetBuyAmount;
        this.foreignerNetBuyQuantity = foreignerNetBuyQuantity;
        this.personalNetBuyQuantity = personalNetBuyQuantity;
        this.institutionNetBuyQuantity = institutionNetBuyQuantity;
    }
}
