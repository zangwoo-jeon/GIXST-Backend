package com.AISA.AISA.kisStock.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MarketType {
    KOSPI("J", "KS", "KRW", "대한민국 코스피"),
    KOSDAQ("Q", "KQ", "KRW", "대한민국 코스닥"),
    KONEX("N", "KN", "KRW", "대한민국 코넥스"),
    VKOSPI("V", "KS", "KRW", "코스피 200 변동성 지수"),
    NAS("NAS", "NAS", "USD", "나스닥"),
    NYS("NYS", "NYS", "USD", "뉴욕 거래소"),
    AMS("AMS", "AMS", "USD", "아멕스");

    private final String apiCode; // KIS API 호출 시 사용하는 시장 코드
    private final String exchangeCode; // 거래소 코드 (내부 관리용)
    private final String currency; // 기본 통화
    private final String description; // 설명
}
