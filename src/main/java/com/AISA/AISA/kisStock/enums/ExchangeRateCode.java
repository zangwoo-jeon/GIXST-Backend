package com.AISA.AISA.kisStock.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum ExchangeRateCode {
    // 한국은행 ECOS API (STAT_CODE: 731Y001 - 주요국 통화의 대원화환율)
    USD("USD", "0000001", "원/달러"),
    JPY("JPY", "0000002", "원/엔화(100엔)"),
    EUR("EUR", "0000003", "원/유로"),
    HKD("HKD", "0000015", "원/홍콩달러"),
    CNY("CNY", "0000053", "원/위안");

    private final String symbol;       // 통화 식별 코드
    private final String itemCode;     // 한국은행 ECOS ITEM_CODE1 (731Y001 기준)
    private final String description;  // 설명

    public static ExchangeRateCode findBySymbol(String symbol) {
        return Arrays.stream(values())
                .filter(code -> code.symbol.equals(symbol))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 통화 코드입니다: " + symbol));
    }
}
