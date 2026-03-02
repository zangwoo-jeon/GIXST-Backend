package com.AISA.AISA.fred.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FredObservationDto {

    @JsonProperty("date")
    private String date; // yyyy-MM-dd

    @JsonProperty("value")
    private String value; // 숫자 문자열 또는 "." (주말/공휴일)
}
