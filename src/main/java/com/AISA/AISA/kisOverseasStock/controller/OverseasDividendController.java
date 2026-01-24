package com.AISA.AISA.kisOverseasStock.controller;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisStock.dto.Dividend.StockDividendInfoDto;
import com.AISA.AISA.kisOverseasStock.service.OverseasDividendService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/overseas/dividend")
@Tag(name = "해외 주식 배당 API", description = "해외 주식 배당 관련 API (Gemini 활용)")
public class OverseasDividendController {

    private final OverseasDividendService overseasDividendService;

    @GetMapping("/{stockCode}")
    @Operation(summary = "해외 주식 배당 내역 조회", description = "특정 해외 주식의 과거 배당금 지급 내역을 DB에서 조회합니다.")
    public ResponseEntity<SuccessResponse<List<StockDividendInfoDto>>> getDividendInfo(
            @PathVariable String stockCode,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        if (startDate == null || endDate == null) {
            endDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            startDate = LocalDate.now().minusYears(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        }

        List<StockDividendInfoDto> dividendInfoList = overseasDividendService.getDividendInfo(stockCode, startDate,
                endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "Overseas dividend query successful", dividendInfoList));
    }

    @PostMapping("/refresh/{stockCode}")
    @Operation(summary = "특정 해외 종목 배당 데이터 갱신", description = "Gemini API를 사용하여 특정 해외 종목의 배당 정보를 최신화합니다.")
    public ResponseEntity<SuccessResponse<String>> refreshDividend(@PathVariable String stockCode) {
        overseasDividendService.refreshDividendInfo(stockCode);
        return ResponseEntity.ok(new SuccessResponse<>(true, "Dividend refresh triggered for " + stockCode, null));
    }

    @PostMapping("/refresh/all")
    @Operation(summary = "전체 해외 종목 배당 데이터 갱신", description = "Gemini API를 사용하여 모든 해외 종목의 배당 정보를 순차적으로 최신화합니다.")
    public ResponseEntity<SuccessResponse<String>> refreshAllDividends() {
        overseasDividendService.refreshAllOverseasDividends();
        return ResponseEntity
                .ok(new SuccessResponse<>(true, "Batch dividend refresh triggered for all US stocks", null));
    }

    @PostMapping("/refresh/from/{stockCode}")
    @Operation(summary = "해외 종목 배당 데이터 갱신 재개", description = "특정 종목부터 시작하여 나머지 해외 종목들의 배당 정보를 최신화합니다.")
    public ResponseEntity<SuccessResponse<String>> refreshAllDividendsFrom(@PathVariable String stockCode) {
        overseasDividendService.refreshAllOverseasDividendsFrom(stockCode);
        return ResponseEntity
                .ok(new SuccessResponse<>(true, "Batch dividend refresh resumed from " + stockCode, null));
    }
}
