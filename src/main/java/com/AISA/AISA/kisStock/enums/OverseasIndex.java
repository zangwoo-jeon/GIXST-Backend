package com.AISA.AISA.kisStock.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OverseasIndex {
    NASDAQ("NQ", "COMP", "나스닥"),
    SP500("SP500", "SPX", "S&P 500"),
    NIKKEI("Nikkei", "JP#NI225", "일본 니케이 225"),
    HANGSENG("HangSeng", "HK#HS", "홍콩 항셍"),
    EUROSTOXX50("EuroStoxx50", "SX5E", "유로 STOXX 50");

    private final String code; // 내부 식별용 코드
    private final String symbol; // KIS API 종목코드 (FID_INPUT_ISCD)
    private final String description; // 설명
}
