package com.AISA.AISA.kisStock.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SubIndustry {
    // 1. TECHNOLOGY
    SEMICONDUCTOR("반도체", Industry.TECHNOLOGY),
    IT_HARDWARE("IT하드웨어", Industry.TECHNOLOGY),
    SOFTWARE("소프트웨어", Industry.TECHNOLOGY),
    INTERNET_PLATFORM("인터넷 / 플랫폼", Industry.TECHNOLOGY),
    GAME("게임", Industry.TECHNOLOGY),
    IT_SERVICE_SI("IT서비스 / SI", Industry.TECHNOLOGY),

    // 2. INDUSTRIALS
    MACHINERY("기계", Industry.INDUSTRIALS),
    ELECTRICAL_COMPONENT("전기·전자부품", Industry.INDUSTRIALS),
    SHIPBUILDING("조선", Industry.INDUSTRIALS),
    AEROSPACE_DEFENSE("항공·방산", Industry.INDUSTRIALS),
    CONSTRUCTION("건설", Industry.INDUSTRIALS),
    LOGISTICS_TRANSPORT("물류·운송", Industry.INDUSTRIALS),

    // 3. CONSUMER_DISCRETIONARY
    AUTO("자동차", Industry.CONSUMER_DISCRETIONARY),
    AUTO_COMPONENT("자동차부품", Industry.CONSUMER_DISCRETIONARY),
    HOME_APPLIANCE("가전", Industry.CONSUMER_DISCRETIONARY),
    FASHION_APPAREL("패션 / 의류", Industry.CONSUMER_DISCRETIONARY),
    LEISURE_TRAVEL("레저 / 여행", Industry.CONSUMER_DISCRETIONARY),

    // 4. CONSUMER_STAPLES
    FOOD("식품", Industry.CONSUMER_STAPLES),
    BEVERAGE("음료", Industry.CONSUMER_STAPLES),
    HOUSEHOLD_PRODUCT("생활용품", Industry.CONSUMER_STAPLES),
    RETAIL("유통", Industry.CONSUMER_STAPLES),

    // 5. HEALTHCARE
    PHARMA("제약", Industry.HEALTHCARE),
    BIO("바이오", Industry.HEALTHCARE),
    MEDICAL_DEVICE("의료기기", Industry.HEALTHCARE),
    HEALTHCARE_SERVICE("헬스케어 서비스", Industry.HEALTHCARE),

    // 6. FINANCIALS
    BANK("은행", Industry.FINANCIALS),
    SECURITIES("증권", Industry.FINANCIALS),
    INSURANCE("보험", Industry.FINANCIALS),
    ASSET_MANAGEMENT("자산운용", Industry.FINANCIALS),
    OTHER_FINANCE("기타 금융", Industry.FINANCIALS),

    // 7. ENERGY_MATERIALS
    ENERGY("에너지", Industry.ENERGY_MATERIALS),
    CHEMICAL("화학", Industry.ENERGY_MATERIALS),
    REFINERY("정유", Industry.ENERGY_MATERIALS),
    STEEL("철강", Industry.ENERGY_MATERIALS),
    NON_FERROUS_METAL("비철금속", Industry.ENERGY_MATERIALS),

    // 8. UTILITIES_INFRA
    ELECTRICITY("전력", Industry.UTILITIES_INFRA),
    GAS("가스", Industry.UTILITIES_INFRA),
    TELECOM("통신", Industry.UTILITIES_INFRA),
    INFRA_OPS("인프라 운영", Industry.UTILITIES_INFRA),

    // 9. COMMUNICATION_MEDIA
    ENTERTAINMENT("엔터테인먼트", Industry.COMMUNICATION_MEDIA),
    MEDIA("미디어", Industry.COMMUNICATION_MEDIA),
    CONTENT_PRODUCTION("콘텐츠 제작", Industry.COMMUNICATION_MEDIA),
    ADVERTISING("광고", Industry.COMMUNICATION_MEDIA),

    // 10. CONGLOMERATES_OTHERS
    HOLDING_COMPANY("지주회사", Industry.CONGLOMERATES_OTHERS),
    CONGLOMERATE("복합기업", Industry.CONGLOMERATES_OTHERS),
    OTHER_SERVICE("기타 서비스", Industry.CONGLOMERATES_OTHERS),

    // 11. REITS
    REITS("리츠", Industry.REITS),

    // 12. ETF
    ETF("ETF", Industry.ETF),

    UNKNOWN("미분류", Industry.UNKNOWN);

    private final String description;
    private final Industry industry;
}
