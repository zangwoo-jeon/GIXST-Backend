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
    private String overseaUrl;
    private String volumeRankUrl;
    private String incomeStatementUrl;
    private String balanceSheetUrl;
    private String dividendRankUrl;
    private String periodPriceUrl; // 기간별 시세
    private String investorTrendUrl; // 투자자별 매매동향 (기간 합계)
    private String investorTrendDailyUrl; // 투자자별 매매동향 (일자별) - New Logic
    private String financialRatioUrl;
    private String appkey;
    private String appsecret;
}
