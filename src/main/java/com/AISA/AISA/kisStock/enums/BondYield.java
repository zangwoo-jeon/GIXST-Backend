package com.AISA.AISA.kisStock.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BondYield {
    // 한국 채권 - 한국은행 ECOS API (STAT_CODE: 817Y002)
    KR_1Y("KR_1Y", null, "한국 국고채 1년", "010190000"),
    KR_3Y("KR_3Y", null, "한국 국고채 3년", "010200000"),
    KR_10Y("KR_10Y", null, "한국 국고채 10년", "010210000"),
    KR_CORP_3Y("KR_CORP_3Y", null, "회사채(3년, BBB-)", "010320000"),

    // 미국 채권 - 한국투자증권 KIS API
    US_1Y("US_1Y", "Y0203", "미국 국채 1년", null),
    US_10Y("US_10Y", "Y0202", "미국 국채 10년", null),
    US_30Y("US_30Y", "Y0201", "미국 국채 30년", null);

    private final String code;         // 내부 식별용 코드
    private final String symbol;       // KIS API 종목코드 (미국 채권만 사용)
    private final String description;  // 설명
    private final String ecosItemCode; // 한국은행 ECOS ITEM_CODE1 (한국 채권만 사용)

    public boolean isEcosBased() {
        return ecosItemCode != null;
    }
}
