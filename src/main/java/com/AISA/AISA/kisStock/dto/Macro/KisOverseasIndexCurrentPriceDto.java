package com.AISA.AISA.kisStock.dto.Macro;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class KisOverseasIndexCurrentPriceDto {

    @JsonProperty("ovrs_nmix_prpr")
    private String price; // 현재가

    @JsonProperty("ovrs_nmix_prdy_vrss")
    private String priceChange; // 전일 대비

    @JsonProperty("prdy_ctrt")
    private String changeRate; // 등락률

    @JsonProperty("stck_bsop_date") // Assuming API returns date, or I use local date if missing
    private String date;
}
