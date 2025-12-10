package com.AISA.AISA.kisStock.dto.Macro;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

@Getter
@Setter
@ToString
public class KisOverseasDailyPriceResponseDto {

    @JsonProperty("rt_cd")
    private String returnCode; // 성공 실패 여부

    @JsonProperty("msg_cd")
    private String messageCode; // 응답코드

    @JsonProperty("msg1")
    private String message; // 응답메세지

    @JsonProperty("output2")
    private List<KisOverseasDailyPriceDto> dailyPriceList; // 일자별 정보
}
