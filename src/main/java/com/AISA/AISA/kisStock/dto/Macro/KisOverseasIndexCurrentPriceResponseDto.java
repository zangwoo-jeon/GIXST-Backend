package com.AISA.AISA.kisStock.dto.Macro;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class KisOverseasIndexCurrentPriceResponseDto {
    @JsonProperty("rt_cd")
    private String returnCode;

    @JsonProperty("msg1")
    private String message;

    @JsonProperty("output")
    private KisOverseasIndexCurrentPriceDto output;
}
