package com.AISA.AISA.kisStock.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Industry {
    TECHNOLOGY("IT · 기술"),
    INDUSTRIALS("산업재"),
    CONSUMER_DISCRETIONARY("소비재 (내구재)"),
    CONSUMER_STAPLES("소비재 (필수재)"),
    HEALTHCARE("헬스케어 · 바이오"),
    FINANCIALS("금융"),
    ENERGY_MATERIALS("에너지 · 소재"),
    UTILITIES_INFRA("인프라 · 유틸리티"),
    COMMUNICATION_MEDIA("콘텐츠 · 미디어"),
    CONGLOMERATES_OTHERS("기타 / 복합"),
    REITS("리츠"),
    ETF("ETF (상장지수펀드)"),
    UNKNOWN("미분류");

    private final String description;
}
