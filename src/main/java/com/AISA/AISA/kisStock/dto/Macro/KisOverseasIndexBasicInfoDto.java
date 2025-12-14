package com.AISA.AISA.kisStock.dto.Macro;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class KisOverseasIndexBasicInfoDto {

    @JsonProperty("ovrs_nmix_prpr")
    private String price; // 현재가

    @JsonProperty("ovrs_nmix_prdy_vrss")
    private String priceChange; // 전일 대비

    @JsonProperty("prdy_ctrt")
    private String changeRate; // 등락률

    @JsonProperty("stck_bsop_date")
    private String date; // 영업 일자 (output2에는 있으나 output1에는 없을 수 있음, 확인 필요하지만 필수 아니면 생략 가능. 여기서는 output2에서
                         // 날짜 가져오거나 오늘 날짜 사용)
    // 문서상 output1에는 date 필드가 없으므로, 날짜는 별도로 처리하거나 생략. (current status니까)
}
