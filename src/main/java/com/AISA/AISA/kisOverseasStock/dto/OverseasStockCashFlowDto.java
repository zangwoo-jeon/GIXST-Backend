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
    private BigDecimal shareholderReturnRateNetIncome;
    private BigDecimal fcf;
    private BigDecimal shareholderReturnRateFcf;

    public static OverseasStockCashFlowDto fromEntity(OverseasStockCashFlow entity) {
        return OverseasStockCashFlowDto.builder()
                .stockCode(entity.getStockCode())
                .stacYymm(entity.getStacYymm())
                .divCode(entity.getDivCode())
                .repurchaseOfCapitalStock(entity.getRepurchaseOfCapitalStock())
                .cashDividendsPaid(entity.getCashDividendsPaid())
                .shareholderReturnRate(entity.getShareholderReturnRate())
                .shareholderReturnRateNetIncome(entity.getShareholderReturnRateNetIncome())
                .fcf(entity.getFcf())
                .shareholderReturnRateFcf(entity.getShareholderReturnRateFcf())
                // .shareholderReturnRateNetIncome() // Entity에는 없으므로 Service에서 계산해서 주입 (주석 유지)
                .build();
    }
}
