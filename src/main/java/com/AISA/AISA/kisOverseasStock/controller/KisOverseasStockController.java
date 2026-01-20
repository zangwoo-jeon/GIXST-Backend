package com.AISA.AISA.kisOverseasStock.controller;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisOverseasStock.service.KisOverseasStockService;
import com.AISA.AISA.kisOverseasStock.dto.KisOverseasStockChartDto; // Import New DTO
import com.AISA.AISA.kisStock.dto.StockPrice.StockPriceDto; // Import StockPriceDto
import com.AISA.AISA.kisStock.dto.StockSimpleSearchResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/overseas-stocks")
@RequiredArgsConstructor
@Tag(name = "해외 주식 API", description = "미국 주식 등 해외 주식 관련 API")
public class KisOverseasStockController {

    private final KisOverseasStockService kisOverseasStockService;

    @GetMapping("/search")
    @Operation(summary = "해외 주식 검색", description = "미국 주식(US_STOCK)을 종목코드 또는 종목명으로 검색합니다.")
    public ResponseEntity<SuccessResponse<List<StockSimpleSearchResponseDto>>> searchOverseasStocks(
            @RequestParam String keyword) {
        return ResponseEntity.ok(new SuccessResponse<>(true, "해외 주식 검색 성공",
                kisOverseasStockService.searchOverseasStocks(keyword)));
    }

    @GetMapping("/{stockCode}/price")
    @Operation(summary = "해외 주식 현재가 조회", description = "해외 주식(미국)의 현재가 상세 정보를 조회합니다.")
    public ResponseEntity<SuccessResponse<StockPriceDto>> getOverseasStockPrice(@PathVariable String stockCode) {
        return ResponseEntity.ok(new SuccessResponse<>(true, "해외 주식 현재가 조회 성공",
                kisOverseasStockService.getOverseasStockPrice(stockCode)));
    }

    @GetMapping("/{stockCode}/chart")
    @Operation(summary = "해외 주식 차트 데이터 조회", description = "해외 주식(미국)의 차트 데이터를 조회합니다.")
    public ResponseEntity<SuccessResponse<List<KisOverseasStockChartDto>>> getOverseasStockChart(
            @PathVariable String stockCode,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "D") String periodType) {
        return ResponseEntity.ok(new SuccessResponse<>(true, "해외 주식 차트 조회 성공",
                kisOverseasStockService.getOverseasStockChart(stockCode, startDate, endDate, periodType)));
    }

    @PostMapping("/init-history/{stockCode}")
    @Operation(summary = "해외 주식 초기 데이터 구축", description = "특정 해외 주식의 과거 데이터를 수집하여 DB에 저장합니다.")
    public ResponseEntity<SuccessResponse<String>> initHistoricalData(
            @PathVariable String stockCode,
            @RequestParam String startDate) {
        // Run asynchronously to avoid blocking
        new Thread(() -> kisOverseasStockService.fetchAndSaveHistoricalOverseasStockData(stockCode, startDate)).start();
        return ResponseEntity.ok(new SuccessResponse<>(true, "해외 주식 초기 데이터 구축 시작 (백그라운드)", null));
    }

    @PostMapping("/init-history/all")
    @Operation(summary = "전체 해외 주식 초기 데이터 구축", description = "모든 해외 주식의 과거 데이터를 수집하여 DB에 저장합니다. (비동기 실행)")
    public ResponseEntity<SuccessResponse<String>> initAllHistoricalData(@RequestParam String startDate) {
        new Thread(() -> kisOverseasStockService.fetchAllOverseasStocksHistoricalData(startDate)).start();
        return ResponseEntity.ok(new SuccessResponse<>(true, "전체 해외 주식 초기 데이터 구축 시작 (백그라운드)", null));
    }

    @PostMapping("/init-history/from/{stockId}")
    @Operation(summary = "특정 ID부터 해외 주식 초기 데이터 구축", description = "특정 stockId부터 시작하여 나머지 모든 해외 주식의 과거 데이터를 수집합니다. (중단된 작업 재개용)")
    public ResponseEntity<SuccessResponse<String>> initHistoricalDataFromId(
            @PathVariable Long stockId,
            @RequestParam String startDate) {
        new Thread(() -> kisOverseasStockService.fetchOverseasStocksHistoricalDataFromStockId(stockId, startDate))
                .start();
        return ResponseEntity.ok(new SuccessResponse<>(true, "해외 주식 초기 데이터 구축 시작 (stockId: " + stockId + " 부터)", null));
    }
}
