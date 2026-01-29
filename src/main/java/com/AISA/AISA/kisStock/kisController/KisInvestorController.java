package com.AISA.AISA.kisStock.kisController;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisStock.dto.InvestorTrend.AccumulatedInvestorTrendDto;
import com.AISA.AISA.kisStock.dto.MarketAccumulatedTrendDto;
import com.AISA.AISA.kisStock.dto.MarketInvestorTrendDto;
import com.AISA.AISA.kisStock.kisService.KisInvestorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

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

    @GetMapping("/market/{marketCode}")
    @Operation(summary = "시장별 투자자 매매동향 조회", description = "코스피(0001) / 코스닥(1001) 시장의 일자별 투자자 매매동향을 조회합니다.")
    public ResponseEntity<SuccessResponse<MarketInvestorTrendDto>> getMarketInvestorTrend(
            @PathVariable @Parameter(description = "시장 코드 (0001: 코스피, 1001: 코스닥)", example = "0001") String marketCode) {
        return ResponseEntity.ok(new SuccessResponse<>(true, "시장별 투자자 매매동향 조회 성공",
                kisInvestorService.getMarketInvestorTrend(marketCode)));
    }

    @GetMapping("/market/{marketCode}/accumulated")
    @Operation(summary = "시장별 기간 누적 수급 조회", description = "코스피/코스닥 시장의 지정된 기간 동안의 투자자별 누적 순매수 금액을 조회합니다.")
    public ResponseEntity<SuccessResponse<MarketAccumulatedTrendDto>> getAccumulatedMarketInvestorTrend(
            @PathVariable @Parameter(description = "시장 코드 (0001: 코스피, 1001: 코스닥)", example = "0001") String marketCode,
            @Parameter(description = "조회 기간 (1w: 1주일, 1m: 1개월, 3m: 3개월, 1y: 1년)", example = "1m") @RequestParam(defaultValue = "1m") String period) {
        return ResponseEntity.ok(new SuccessResponse<>(true, "시장별 기간 누적 수급 조회 성공",
                kisInvestorService.getAccumulatedMarketInvestorTrend(marketCode, period)));
    }

    @PostMapping("/market/{marketCode}/sync")
    @Operation(summary = "시장별 과거 수급 데이터 동기화", description = "지정된 시장의 지정된 기간 수급 데이터를 DB에 동기화합니다. (파라미터 없으면 과거 1년)")
    public ResponseEntity<SuccessResponse<Void>> syncHistoricalMarketData(
            @PathVariable @Parameter(description = "시장 코드 (0001: 코스피, 1001: 코스닥)", example = "0001") String marketCode,
            @Parameter(description = "시작 날짜 (yyyyMMdd)", example = "20240101") @RequestParam(required = false) String startDate,
            @Parameter(description = "종료 날짜 (yyyyMMdd)", example = "20241231") @RequestParam(required = false) String endDate) {

        LocalDate start = (startDate != null) ? LocalDate.parse(startDate, DateTimeFormatter.ofPattern("yyyyMMdd"))
                : null;
        LocalDate end = (endDate != null) ? LocalDate.parse(endDate, DateTimeFormatter.ofPattern("yyyyMMdd")) : null;

        kisInvestorService.fetchHistoricalMarketData(marketCode, start, end);
        return ResponseEntity.ok(new SuccessResponse<>(true, "시장 과거 데이터 동기화 시작 (백그라운드)", null));
    }
}
