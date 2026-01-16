package com.AISA.AISA.kisStock.dto.InvestorTrend;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class InvestorRankResponseDto {
    private List<InvestorRankDto> ranks;
}
