package com.AISA.AISA.kisStock.dto.Dividend;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class KisDividendHistoryDto {

    @JsonProperty("record_date") // 배당락일
    private String recordDate;

    @JsonProperty("sht_cd") // 종목 코드
    private String stockCode;

    @JsonProperty("isin_name") // 종목 이름
    private String stockName;

    @JsonProperty("per_sto_divi_amt") //현금배당금
    private String dividendAmount;

    @JsonProperty("divi_rate") // 현금 배당률(%)
    private String dividendRate;

    @JsonProperty("divi_pay_dt") // 배당금 지급일
    private String paymentDate;

}
