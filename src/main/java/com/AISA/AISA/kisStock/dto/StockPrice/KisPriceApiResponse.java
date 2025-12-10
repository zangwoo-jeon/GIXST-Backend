package com.AISA.AISA.kisStock.dto.StockPrice;


import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class KisPriceApiResponse {
    @JsonProperty("output")
    private StockPriceResponse output;
}
