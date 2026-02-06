package com.AISA.AISA.kisStock.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "kis")
@Getter
@Setter
public class KisApiProperties {
    private String baseUrl;
    private String authUrl;
    private String priceUrl;
    private String indexPriceUrl;
    private String indexChartUrl;
    private String stockChartUrl;
    private String dividendUrl;
    private String volumeRankUrl;
    private String incomeStatementUrl;
    private String balanceSheetUrl;
    private String dividendRankUrl;
    private String periodPriceUrl; // 기간별 시세
    private String investorTrendUrl; // 투자자별 매매동향 (기간 합계)
    private String investorTrendDailyUrl; // 투자자별 매매동향 (일자별) - New Logic
    private String investorTrendMarketUrl; // 시장별 투자자 매매동향 (일자별) - TR FHPTJ04040000
    private String financialRatioUrl;
    private String otherMajorRatiosUrl; // EV/EBITDA 등 기타 지표
    private String searchStockInfoUrl; // 종목정보조회 (상장일 등)
    private String etfPriceUrl; // ETF/ETN 가격 조회 (ETF 여부 확인용)
    private String appkey;
    private String appsecret;
}
