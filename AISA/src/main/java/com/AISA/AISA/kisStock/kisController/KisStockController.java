package com.AISA.AISA.kisStock.kisController;

import com.AISA.AISA.global.response.SuccessResponse;

import com.AISA.AISA.kisStock.dto.StockPrice.StockChartResponseDto;
import com.AISA.AISA.kisStock.dto.StockPrice.StockPriceDto;
import com.AISA.AISA.kisStock.dto.VolumeRank.VolumeRankDto;
import com.AISA.AISA.kisStock.kisService.KisStockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
@Tag(name = "주식 API", description = "주식 관련 API")
public class KisStockController {
    private final KisStockService kisStockService;
    private final com.AISA.AISA.kisStock.kisService.Auth.KisAuthService kisAuthService;

    @GetMapping("/token")
    @Operation(summary = "Access Token 조회", description = "현재 유효한 KIS Access Token을 조회합니다.")
    public ResponseEntity<SuccessResponse<String>> getAccessToken() {
        String token = kisAuthService.getAccessToken();
        return ResponseEntity.ok(new SuccessResponse<>(true, "Access Token 조회 성공", token));
    }

    @GetMapping("/{stockCode}/price")
    @Operation(summary = "주식 현재가 조회", description = "특정 주식의 현재 가격 정보를 조회합니다.")
    public ResponseEntity<SuccessResponse<StockPriceDto>> getStockPrice(@PathVariable String stockCode) {
        StockPriceDto stockPrice = kisStockService.getStockPrice(stockCode);
        return ResponseEntity.ok(new SuccessResponse<>(true, "주식 현재가 조회 성공", stockPrice));
    }

    @GetMapping("/volume-rank")
    @Operation(summary = "거래량 순위 조회", description = "거래량 상위 종목 순위를 조회합니다.")
    public ResponseEntity<SuccessResponse<VolumeRankDto>> getVolumeRank() {
        VolumeRankDto volumeRank = kisStockService.getVolumeRank();
        return ResponseEntity.ok(new SuccessResponse<>(true, "거래량 순위 조회 성공", volumeRank));
    }

    @GetMapping("/{stockCode}/chart")
    @Operation(summary = "주식 차트 데이터 조회", description = "특정 주식의 차트 데이터를 조회합니다.")
    public ResponseEntity<SuccessResponse<StockChartResponseDto>> getStockChart(
            @PathVariable String stockCode,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "D") String dateType) {
        StockChartResponseDto chartData = kisStockService.getStockChart(stockCode, startDate, endDate, dateType);
        return ResponseEntity.ok(new SuccessResponse<>(true, "주식 차트 데이터 조회 성공", chartData));
    }

    @PostMapping("/init-history/{stockCode}")
    @Operation(summary = "특정 종목 초기 데이터 구축", description = "특정 종목의 과거 데이터를 수집하여 DB에 저장합니다.")
    public ResponseEntity<SuccessResponse<String>> initHistoricalData(
            @PathVariable String stockCode,
            @RequestParam String startDate) {
        kisStockService.fetchAndSaveHistoricalStockData(stockCode, startDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "초기 데이터 구축 시작", null));
    }

}
