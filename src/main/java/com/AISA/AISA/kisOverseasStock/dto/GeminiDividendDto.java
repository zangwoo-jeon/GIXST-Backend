package com.AISA.AISA.kisOverseasStock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeminiDividendDto {
    private String recordDate; // yyyyMMdd
    private String paymentDate; // yyyy/MM/dd
    private String dividendAmount; // USD
}
