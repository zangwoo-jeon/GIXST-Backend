package com.AISA.AISA.kisStock.kisController;

import com.AISA.AISA.global.response.SuccessResponse;

import com.AISA.AISA.kisStock.dto.StockPrice.StockChartPriceDto;
import com.AISA.AISA.kisStock.dto.StockPrice.StockPriceDto;
import com.AISA.AISA.kisStock.dto.StockSimpleSearchResponseDto; // Simplified DTO
import com.AISA.AISA.kisStock.dto.VolumeRank.VolumeRankDto;
import com.AISA.AISA.kisStock.kisService.CommonStockClassificationService;
import com.AISA.AISA.kisStock.kisService.Auth.KisAuthService;
import com.AISA.AISA.kisStock.kisService.KisStockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static org.springframework.http.MediaType.APPLICATION_JSON;

@RestController
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
@Tag(name = "주식 API", description = "주식 관련 API")
public class KisStockController {
    private final KisStockService kisStockService;
    private final KisAuthService kisAuthService;
    private final CommonStockClassificationService commonStockClassificationService;

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
    public ResponseEntity<String> getStockChart(
            @PathVariable String stockCode,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "D") String dateType) {
        String chartDataJson = kisStockService.getStockChartJson(stockCode, startDate, endDate, dateType);

        // Manual JSON construction to wrap with SuccessResponse structure
        // Avoiding object serialization overhead
        String responseJson = String.format(
                "{\"Success\":true,\"message\":\"주식 차트 데이터 조회 성공\",\"data\":%s}",
                chartDataJson);

        return ResponseEntity.ok()
                .contentType(APPLICATION_JSON)
                .body(responseJson);
    }

    @PostMapping("/init-history/{stockCode}")
    @Operation(summary = "특정 종목 초기 데이터 구축", description = "특정 종목의 과거 데이터를 수집하여 DB에 저장합니다.")
    public ResponseEntity<SuccessResponse<String>> initHistoricalData(
            @PathVariable String stockCode,
            @RequestParam String startDate,
            @RequestParam(defaultValue = "J") String marketCode) {
        kisStockService.fetchAndSaveHistoricalStockData(stockCode, startDate, marketCode);
        return ResponseEntity.ok(new SuccessResponse<>(true, "초기 데이터 구축 시작", null));
    }

    @GetMapping("/{stockCode}/chart/excluding-latest")
    @Operation(summary = "주식 차트 데이터 조회 (최신 데이터 제외)", description = "특정 주식의 차트 데이터를 조회합니다. DB에 저장된 가장 최신 날짜의 데이터는 제외됩니다. (예: 최신 데이터가 2026-02-27이면 2026-02-26까지만 반환)")
    public ResponseEntity<SuccessResponse<List<StockChartPriceDto>>> getStockChartExcludingLatest(
            @PathVariable String stockCode,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "D") String dateType) {
        List<StockChartPriceDto> priceList = kisStockService.getStockChartExcludingLatest(stockCode, startDate, endDate, dateType);
        return ResponseEntity.ok(new SuccessResponse<>(true, "주식 차트 데이터 조회 성공 (최신 제외)", priceList));
    }

    @PostMapping("/init-history/all")
    @Operation(summary = "전체 주식 초기 데이터 구축", description = "모든 주식에 대해 현재부터 특정 과거 시점까지의 데이터를 반복적으로 수집하여 DB에 저장합니다. (비동기 실행 권장)")
    public ResponseEntity<SuccessResponse<String>> initHistoricalDataAll(
            @RequestParam String startDate,
            @RequestParam(defaultValue = "J") String marketCode) {
        new Thread(() -> kisStockService.fetchAllStocksHistoricalData(startDate, marketCode)).start();

        return ResponseEntity
                .ok(new SuccessResponse<>(true, "전체 주식 초기 데이터 구축 시작 (백그라운드 실행)", "Started background task"));
    }

    @PostMapping("/init-history/range")
    @Operation(summary = "주식 ID 범위 초기 데이터 구축", description = "주식 ID 범위를 지정하여 초기 데이터를 구축합니다.")
    public ResponseEntity<SuccessResponse<String>> initHistoricalDataByRange(
            @RequestParam Long startId,
            @RequestParam Long endId,
            @RequestParam String startDate,
            @RequestParam(defaultValue = "J") String marketCode) {

        new Thread(() -> kisStockService.fetchStocksHistoricalDataByRange(startId, endId, startDate, marketCode)).start();

        return ResponseEntity
                .ok(new SuccessResponse<>(true, "ID 범위 주식 초기 데이터 구축 시작 (백그라운드 실행)",
                        "Started background task for range"));
    }

    @GetMapping("/search")
    @Operation(summary = "주식 검색", description = "종목 코드 또는 종목명으로 주식을 검색합니다 (포함 검색, 국내 주식 전용).")
    public ResponseEntity<SuccessResponse<List<StockSimpleSearchResponseDto>>> searchStocks(
            @RequestParam String keyword) {
        List<StockSimpleSearchResponseDto> result = kisStockService
                .searchStocks(keyword);
        return ResponseEntity.ok(new SuccessResponse<>(true, "주식 검색 성공", result));
    }

    @PostMapping("/classify-common")
    @Operation(summary = "보통주 분류 및 데이터 저장", description = "국내 주식들을 분석하여 보통주 여부(isCommon)를 판단하고 DB에 저장합니다.")
    public ResponseEntity<SuccessResponse<String>> classifyCommonStocks() {
        new Thread(commonStockClassificationService::classifyAllDomesticStocks).start();
        return ResponseEntity.ok(new SuccessResponse<>(true, "보통주 분류 작업 시작 (백그라운드 실행)", "Started"));
    }
}
