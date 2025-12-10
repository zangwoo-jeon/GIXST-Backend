package com.AISA.AISA.kisStock.dto.Index;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexChartResponseDto {
    private IndexChartInfoDto info;
    private List<IndexChartPriceDto> priceList;
}
