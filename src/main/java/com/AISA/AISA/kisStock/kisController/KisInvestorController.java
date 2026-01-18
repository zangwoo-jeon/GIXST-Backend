package com.AISA.AISA.kisStock.kisController;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisStock.dto.InvestorTrend.AccumulatedInvestorTrendDto;
import com.AISA.AISA.kisStock.kisService.KisInvestorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/stocks/investor")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "KIS Investor API", description = "투자자별 수급 관련 API")
public class KisInvestorController {

    private final KisInvestorService kisInvestorService;

    @GetMapping("/{stockCode}/accumulated")
    @Operation(summary = "특정 종목 기간별 누적 수급 조회", description = "특정 종목의 지정된 기간 동안의 투자자별(개인, 외국인, 기관) 누적 순매수 금액을 조회합니다.")
    public ResponseEntity<SuccessResponse<AccumulatedInvestorTrendDto>> getAccumulatedInvestorTrend(
            @PathVariable String stockCode,
            @Parameter(description = "조회 기간 (1m: 1개월, 3m: 3개월, 1y: 1년)", example = "3m") @RequestParam(defaultValue = "3m") String period) {
        return ResponseEntity.ok(new SuccessResponse<>(true, "기간별 누적 수급 조회 성공",
                kisInvestorService.getAccumulatedInvestorTrend(stockCode, period)));
    }
}
