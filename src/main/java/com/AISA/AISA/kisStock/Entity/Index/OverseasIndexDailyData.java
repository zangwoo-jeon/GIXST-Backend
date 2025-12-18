package com.AISA.AISA.kisStock.Entity.Index;

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
@Table(name = "overseas_index_daily_data", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "market_name", "date" })
})
public class OverseasIndexDailyData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "market_name", nullable = false, length = 20)
    private String marketName; // NASDAQ, SP500, etc.

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "opening_price", nullable = false, precision = 20, scale = 4)
    private BigDecimal openingPrice;

    @Column(name = "closing_price", nullable = false, precision = 20, scale = 4)
    private BigDecimal closingPrice;

    @Column(name = "high_price", nullable = false, precision = 20, scale = 4)
    private BigDecimal highPrice;

    @Column(name = "low_price", nullable = false, precision = 20, scale = 4)
    private BigDecimal lowPrice;

    // 해외 지수 데이터는 보통 가격 변화량/등락률은 제공해주지만 필수가 아닐 수 있어 체크 필요
    // 하지만 user spec에서 전일 대비(ovrs_nmix_prdy_vrss), 전일 대비율(prdy_ctrt) 제공됨.
    // DTO에서 이를 파싱해서 저장할 수는 없는데(history loop에서는 output2만 사용하므로),
    // output2(daily list)에는 '변경 여부'만 있고 가격 변화량은 없음.
    // 계산해서 넣어야 함. 일단 필드는 만들어 둠.
    @Column(name = "price_change", precision = 20, scale = 4)
    private BigDecimal priceChange;

    @Column(name = "change_rate")
    private Double changeRate;

    @Column(name = "volume", precision = 20, scale = 0)
    private BigDecimal volume;

    @Builder
    public OverseasIndexDailyData(String marketName, LocalDate date, BigDecimal openingPrice, BigDecimal closingPrice,
            BigDecimal highPrice, BigDecimal lowPrice, BigDecimal priceChange, Double changeRate, BigDecimal volume) {
        this.marketName = marketName;
        this.date = date;
        this.openingPrice = openingPrice;
        this.closingPrice = closingPrice;
        this.highPrice = highPrice;
        this.lowPrice = lowPrice;
        this.priceChange = priceChange;
        this.changeRate = changeRate;
        this.volume = volume;
    }
}
