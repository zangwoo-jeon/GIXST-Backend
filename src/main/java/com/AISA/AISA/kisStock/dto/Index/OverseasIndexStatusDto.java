package com.AISA.AISA.kisStock.dto.Index;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverseasIndexStatusDto {
    private String date;
    private String price;
    private String priceChange;
    private String changeRate;
}
