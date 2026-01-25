package com.AISA.AISA.kisOverseasStock.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "kis")
@Getter
@Setter
public class KisOverseasApiProperties {
    private String overseaUrl; // /uapi/overseas-price/v1/quotations/inquire-daily-chartprice
    private String overseaPriceUrl; // /uapi/overseas-price/v1/quotations/price-detail
    private String overseaChartPriceUrl; // /uapi/overseas-price/v1/quotations/dailyprice
    private String overseaSearchInfoUrl; // /uapi/overseas-price/v1/quotations/search-info
}
