package com.AISA.AISA.kisOverseasStock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OverseasStockPriceDetailDto {
    private String stockCode;
    private String marketCap; // tomv
    private String marketCapKrw; // Won conversion
    private String listedShares; // shar
}
