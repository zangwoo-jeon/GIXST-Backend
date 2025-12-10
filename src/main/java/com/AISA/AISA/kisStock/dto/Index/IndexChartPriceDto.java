package com.AISA.AISA.kisStock.dto.Index;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexChartPriceDto {
    private String date;
    private String price;
    private String openPrice;
    private String highPrice;
    private String lowPrice;
}
