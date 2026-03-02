package com.AISA.AISA.fred.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class FredSeriesResponseDto {

    @JsonProperty("observations")
    private List<FredObservationDto> observations;
}
