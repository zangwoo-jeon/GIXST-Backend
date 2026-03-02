package com.AISA.AISA.fred.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FredIndexDataDto {

    private String date;         // yyyyMMdd 형식
    private String price;        // 종가
    private String exchangeRate; // 원/달러 환율 (KRW 조회 시에만 포함)
}
