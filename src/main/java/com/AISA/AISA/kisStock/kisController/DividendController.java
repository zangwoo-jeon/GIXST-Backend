package com.AISA.AISA.kisStock.kisController;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisStock.dto.Dividend.StockDividendInfoDto;
import com.AISA.AISA.kisStock.dto.Dividend.DividendDetailDto;
import com.AISA.AISA.kisStock.dto.DividendRank.DividendRankDto;

import com.AISA.AISA.kisStock.kisService.DividendService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/dividend")
@RequiredArgsConstructor
@Tag(name = "배당 API", description = "배당 관련 API")
public class DividendController {

    private final DividendService dividendService;

    @GetMapping("/{stockCode}/dividend")
    @Operation(summary = "주식 배당 내역 조회", description = "특정 주식의 과거 배당금 지급 내역을 조회합니다.")
    public ResponseEntity<SuccessResponse<List<StockDividendInfoDto>>> getDividendInfo(
            @PathVariable String stockCode,
            @RequestParam String startDate,
            @RequestParam String endDate) {
        List<StockDividendInfoDto> dividendInfoList = dividendService.getDividendInfo(stockCode, startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "주식 배당금 조회 성공", dividendInfoList));
    }

    @GetMapping("/{stockCode}/detail")
    @Operation(summary = "주식 배당 상세 정보 조회", description = "특정 주식의 배당수익률, 배당성향, 배당주기 등 상세 정보를 조회합니다.")
    public ResponseEntity<SuccessResponse<DividendDetailDto>> getDividendDetail(@PathVariable String stockCode) {
        DividendDetailDto detail = dividendService.getDividendDetail(stockCode);
        return ResponseEntity.ok(new SuccessResponse<>(true, "주식 배당 상세 정보 조회 성공", detail));
    }

    @PostMapping("/rank/refresh")
    @Operation(summary = "배당률 순위 갱신", description = "전체 주식의 작년 배당금을 조회하여 배당수익률 순위를 갱신합니다. (시간 소요 주의)")
    public ResponseEntity<SuccessResponse<String>> refreshDividendRank() {
        dividendService.refreshDividendRank();
        return ResponseEntity.ok(new SuccessResponse<>(true, "배당률 순위 갱신 완료", null));
    }

    @GetMapping("/rank")
    @Operation(summary = "배당률 순위 조회", description = "저장된 배당수익률 상위 순위를 조회합니다.")
    public ResponseEntity<SuccessResponse<DividendRankDto>> getDividendRank() {
        DividendRankDto rank = dividendService.getDividendRank();
        return ResponseEntity.ok(new SuccessResponse<>(true, "배당률 순위 조회 성공", rank));
    }

}
