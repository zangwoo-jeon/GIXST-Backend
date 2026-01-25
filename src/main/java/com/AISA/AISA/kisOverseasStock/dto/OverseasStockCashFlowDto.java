package com.AISA.AISA.kisOverseasStock.dto;

import com.AISA.AISA.kisOverseasStock.entity.OverseasStockCashFlow;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class OverseasStockCashFlowDto {
    private String stockCode;
    private String stacYymm;
    private String divCode;
    private BigDecimal repurchaseOfCapitalStock;
    private BigDecimal cashDividendsPaid;
    private BigDecimal shareholderReturnRate;

    public static OverseasStockCashFlowDto fromEntity(OverseasStockCashFlow entity) {
        return OverseasStockCashFlowDto.builder()
                .stockCode(entity.getStockCode())
                .stacYymm(entity.getStacYymm())
                .divCode(entity.getDivCode())
                .repurchaseOfCapitalStock(entity.getRepurchaseOfCapitalStock())
                .cashDividendsPaid(entity.getCashDividendsPaid())
                .shareholderReturnRate(entity.getShareholderReturnRate())
                .build();
    }
}
