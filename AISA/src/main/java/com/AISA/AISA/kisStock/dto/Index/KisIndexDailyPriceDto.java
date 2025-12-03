package com.AISA.AISA.kisStock.dto.Index;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
public class KisIndexDailyPriceDto {
    @JsonProperty("stck_bsop_date")
    private String date;

    @JsonProperty("bstp_nmix_prpr")
    private String price;

    @JsonProperty("bstp_nmix_oprc")
    private String openPrice;

    @JsonProperty("bstp_nmix_hgpr")
    private String highPrice;

    @JsonProperty("bstp_nmix_lwpr")
    private String lowPrice;
}
