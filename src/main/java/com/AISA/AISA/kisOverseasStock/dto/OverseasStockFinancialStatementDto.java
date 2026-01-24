package com.AISA.AISA.kisOverseasStock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OverseasStockFinancialStatementDto {
    private String stacYymm;
    private BigDecimal totalRevenue;
    private BigDecimal operatingIncome;
    private BigDecimal netIncome;
}
