package com.AISA.AISA.kisStock.dto.FinancialRank;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.Getter;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "투자 지표 응답 DTO")
public class InvestmentMetricDto {
    @Schema(description = "종목 코드")
    private String stockCode;

    @Schema(description = "결산 연월")
    private String stacYymm;

    @Schema(description = "PER (주가수익비율)")
    private String per;

    @Schema(description = "PBR (주가순자산비율)")
    private String pbr;

    @Schema(description = "PSR (주가매출비율)")
    private String psr;

    @Schema(description = "EPS (주당순이익)")
    private String eps;

    @Schema(description = "ROE (자기자본이익률)")
    private String roe;

    @Schema(description = "BPS (주당순자산가치)")
    private String bps;
}
