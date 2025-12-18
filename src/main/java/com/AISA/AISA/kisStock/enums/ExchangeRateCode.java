package com.AISA.AISA.kisStock.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum ExchangeRateCode {
    USD("FX@KRW", "0000001", "미국 달러"),
    JPY("FX@KRWJS", "0000002", "일본 엔(100엔)"),
    HKD("FX@HKD", "0000003", "홍콩 달러"),
    HKD_KRW("HKD/KRW", "0000004", "홍콩 달러/원 (계산됨)"),
    EUR("FX@EUR", "0000005", "유로 (USD/EUR)"),
    EUR_KRW("EUR/KRW", "0000006", "유로/원 (계산됨)");

    private final String symbol; // KIS API 종목코드 (FID_INPUT_ISCD)
    private final String itemCode; // 내부 관리용 ItemCode (DB 저장용)
    private final String description; // 설명

    public static ExchangeRateCode findBySymbol(String symbol) {
        return Arrays.stream(values())
                .filter(code -> code.symbol.equals(symbol))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 통화 코드입니다: " + symbol));
    }
}
