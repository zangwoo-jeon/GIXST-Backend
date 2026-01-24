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
public class OverseasFinancialRatioDto {
    private String stockCode;
    private String stacYymm;
    private String divCode;
    private BigDecimal per;
    private BigDecimal pbr;
    private BigDecimal psr;
    private BigDecimal roe;
    private BigDecimal epsUsd;
    private BigDecimal epsKrw;
    private BigDecimal bpsUsd;
    private BigDecimal bpsKrw;
}
