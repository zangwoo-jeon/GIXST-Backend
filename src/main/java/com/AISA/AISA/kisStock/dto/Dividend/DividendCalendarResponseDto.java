package com.AISA.AISA.kisStock.dto.Dividend;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DividendCalendarResponseDto {
    private List<StockDividendInfoDto> dividends;
    private BigDecimal totalMonthlyDividend; // 해당 월 총 배당 예상금
}
