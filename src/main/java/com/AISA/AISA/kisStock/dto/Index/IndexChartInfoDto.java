package com.AISA.AISA.kisStock.dto.Index;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexChartInfoDto {
    private String marketName;
    private String currentIndices;
    private String priceChange;
    private String changeRate;
    private String grade;
}
