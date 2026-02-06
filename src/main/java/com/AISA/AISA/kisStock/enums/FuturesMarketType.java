package com.AISA.AISA.kisStock.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum FuturesMarketType {
    KOSPI200("KOSPI200", "코스피 200 선물"),
    KOSDAQ150("KOSDAQ150", "코스닥 150 선물");

    private final String code;
    private final String description;
}
