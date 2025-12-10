package com.AISA.AISA.kisStock.dto.Index;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
public class KisIndexChartApiResponse {

    @JsonProperty("rt_cd")
    private String rtCd;

    @JsonProperty("msg_cd")
    private String msgCd;

    @JsonProperty("msg1")
    private String msg1;

    @JsonProperty("output1")
    private KisIndexDailyInfoDto todayInfo;

    @JsonProperty("output2")
    private List<KisIndexDailyPriceDto> dateInfoList;

}