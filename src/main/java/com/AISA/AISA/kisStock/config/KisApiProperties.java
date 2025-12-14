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
    private String overseaUrl; // Renamed from overSeaUrl to match KIS_OVERSEA_URL
    private String volumeRankUrl;
    private String incomeStatementUrl;
    private String balanceSheetUrl;
    private String dividendRankUrl;
    private String financialRatioUrl;
    private String appkey;
    private String appsecret;
}
