package com.AISA.AISA.kisStock.dto.StockPrice;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockChartPriceDto {
    @JsonProperty("stck_bsop_date")
    private String date;

    @JsonProperty("stck_clpr")
    private String closePrice;

    @JsonProperty("stck_oprc")
    private String openPrice;

    @JsonProperty("stck_hgpr")
    private String highPrice;

    @JsonProperty("stck_lwpr")
    private String lowPrice;

    @JsonProperty("acml_vol")
    private String volume;

    // USD Fields
    @JsonProperty("stck_clpr_usd")
    private String closePriceUsd;

    @JsonProperty("stck_oprc_usd")
    private String openPriceUsd;

    @JsonProperty("stck_hgpr_usd")
    private String highPriceUsd;

    @JsonProperty("stck_lwpr_usd")
    private String lowPriceUsd;

    @JsonProperty("exchange_rate")
    private String exchangeRate;
}
