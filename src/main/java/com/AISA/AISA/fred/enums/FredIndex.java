package com.AISA.AISA.fred.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum FredIndex {

    SP500("SP500", "SP500", "S&P 500"),
    NASDAQ("NASDAQ", "NASDAQCOM", "나스닥 종합");

    private final String marketName; // DB 저장 키 (OverseasIndexDailyData.marketName)
    private final String seriesId;   // FRED series_id
    private final String description;
}
