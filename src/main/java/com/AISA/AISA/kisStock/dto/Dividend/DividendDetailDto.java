package com.AISA.AISA.kisStock.dto.Dividend;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Getter
@Builder
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class DividendDetailDto {
    private String stockCode;
    private String dividendYield; // 배당수익률 (%)
    private String dividendPerShare; // 주당배당금 (원)
    private String payoutRatio; // 배당성향 (%)
    private String dividendFrequency; // 배당 주기
    private String recentExDividendDate; // 최근 배당락일 (YYYYMMDD)
}
