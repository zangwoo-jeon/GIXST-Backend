package com.AISA.AISA.kisStock.dto.StockPrice;

import com.AISA.AISA.kisStock.Entity.stock.EtfDetail;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Builder
public class EtfDetailResponseDto {
    private String stockCode;
    private String stockName;
    private String marketName;
    private String underlyingIndex;
    private String indexProvider;
    private Double trackingMultiplier;
    private String replicationMethod;
    private String manager;
    private BigDecimal totalExpense;
    private String taxType;
    private LocalDate listingDate;

    // Dynamic fields from KIS API
    private String nav; // 순자산가치 (원가)
    private String trackingError; // 추적오차율 (%)
    private String discrepancyRate; // 괴리율 (%)

    public static EtfDetailResponseDto of(EtfDetail entity, String nav, String trackingError, String discrepancyRate) {
        return EtfDetailResponseDto.builder()
                .stockCode(entity.getStock().getStockCode())
                .stockName(entity.getStock().getStockName())
                .marketName(entity.getStock().getMarketName().name())
                .underlyingIndex(entity.getUnderlyingIndex())
                .indexProvider(entity.getIndexProvider())
                .trackingMultiplier(entity.getTrackingMultiplier())
                .replicationMethod(entity.getReplicationMethod())
                .manager(entity.getManager())
                .totalExpense(entity.getTotalExpense())
                .taxType(entity.getTaxType())
                .listingDate(entity.getListingDate())
                .nav(nav)
                .trackingError(trackingError)
                .discrepancyRate(discrepancyRate)
                .build();
    }

    public static EtfDetailResponseDto from(EtfDetail entity) {
        return of(entity, null, null, null);
    }
}
