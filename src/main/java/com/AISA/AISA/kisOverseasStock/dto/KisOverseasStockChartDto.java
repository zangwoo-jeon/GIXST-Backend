package com.AISA.AISA.kisOverseasStock.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class KisOverseasStockChartDto {
    @JsonProperty("stck_bsop_date")
    private String date;

    @JsonProperty("open_usd")
    private String openPrice; // USD

    @JsonProperty("high_usd")
    private String highPrice; // USD

    @JsonProperty("low_usd")
    private String lowPrice; // USD

    @JsonProperty("close_usd")
    private String closePrice; // USD

    @JsonProperty("diff")
    private String diff;

    @JsonProperty("rate")
    private String rate;

    @JsonProperty("open_krw")
    private String openPriceKrw;

    @JsonProperty("high_krw")
    private String highPriceKrw;

    @JsonProperty("low_krw")
    private String lowPriceKrw;

    @JsonProperty("close_krw")
    private String closePriceKrw;

    @JsonProperty("exchange_rate")
    private String exchangeRate;
}
