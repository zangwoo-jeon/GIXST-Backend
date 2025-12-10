package com.AISA.AISA.kisStock.dto.Index;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class KisIndexDailyInfoDto {
    @JsonProperty("bstp_nmix_prpr")
    private String currentIndices;

    @JsonProperty("bstp_nmix_prdy_vrss")
    private String priceChange;

    @JsonProperty("bstp_nmix_prdy_ctrt")
    private String changeRate;
}
