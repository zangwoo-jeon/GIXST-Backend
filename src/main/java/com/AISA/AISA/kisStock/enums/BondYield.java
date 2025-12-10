package com.AISA.AISA.kisStock.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BondYield {
    KR_1Y("KR_1Y", "Y0104", "한국 국고채 1년"),
    KR_3Y("KR_3Y", "Y0101", "한국 국고채 3년"),
    KR_10Y("KR_10Y", "Y0106", "한국 국고채 10년"),
    US_1Y("US_1Y", "Y0203", "미국 국채 1년"),
    US_10Y("US_10Y", "Y0202", "미국 국채 10년"),
    US_30Y("US_30Y", "Y0201", "미국 국채 30년");

    private final String code; // 내부 식별용 코드
    private final String symbol; // KIS API 종목코드 (FID_INPUT_ISCD)
    private final String description; // 설명
}
