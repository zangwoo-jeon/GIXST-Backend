package com.AISA.AISA.kisStock.kisController;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisStock.Entity.Index.MarketStatusHistory;
import com.AISA.AISA.kisStock.dto.Index.IndexChartInfoDto;
import com.AISA.AISA.kisStock.dto.Index.IndexChartResponseDto;
import com.AISA.AISA.kisStock.enums.MarketType;
import com.AISA.AISA.kisStock.kisService.KisIndexService;
import com.AISA.AISA.analysis.service.MarketValuationService;
import com.AISA.AISA.kisStock.dto.Index.IndexChartPriceDto;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.AISA.AISA.kisStock.kisService.MarketStatusHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/indices")
@RequiredArgsConstructor
@Tag(name = "지수 API", description = "지수 관련 API")
public class KisIndexController {
    private final KisIndexService kisIndexService;
    private final MarketStatusHistoryService marketStatusHistoryService;
    private final MarketValuationService marketValuationService;

    @GetMapping("/{marketCode}/status")
    @Operation(summary = "지수 현재 상태 조회", description = "코스피(kospi) / 코스닥(kosdaq)의 실시간 지수 정보를 조회합니다.")
    public ResponseEntity<SuccessResponse<IndexChartInfoDto>> getIndexStatus(@PathVariable String marketCode) {
        IndexChartInfoDto statusData = kisIndexService.getIndexStatus(marketCode);

        // 밸류에이션 등급 정보 추가
        try {
            MarketType marketType = MarketType.valueOf(marketCode.toUpperCase());
            if (marketType == MarketType.KOSPI || marketType == MarketType.KOSDAQ) {
                var valuation = marketValuationService.calculateMarketValuation(marketType);
                if (valuation != null && valuation.getValuationAnalysis() != null) {
                    statusData.setGrade(valuation.getValuationAnalysis().getState());
                }
            }
        } catch (Exception e) {
            // 등급 추가 실패 시 무시하고 데이터만 반환
        }

        return ResponseEntity.ok(new SuccessResponse<>(true, "지수 현재 상태 조회 성공", statusData));
    }

    @GetMapping("/{marketCode}/chart")
    @Operation(summary = "지수 조회", description = "코스피(kospi) / 코스닥(kosdaq)의 날짜별 정보를 조회합니다.")
    public ResponseEntity<SuccessResponse<IndexChartResponseDto>> getIndexChart(
            @PathVariable String marketCode,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "D") String dateType) {
        IndexChartResponseDto chartData = kisIndexService.getIndexChart(marketCode, startDate, endDate, dateType);
        return ResponseEntity.ok(new SuccessResponse<>(true, "기간별 지수 정보 조회 성공", chartData));
    }

    @GetMapping("/{marketCode}/chart/excluding-latest")
    @Operation(summary = "지수 조회 (최신 데이터 제외)", description = "코스피(kospi) / 코스닥(kosdaq)의 날짜별 정보를 조회합니다. DB에 저장된 가장 최신 날짜의 데이터는 제외됩니다. (예: 최신 데이터가 2026-02-27이면 2026-02-26까지만 반환)")
    public ResponseEntity<SuccessResponse<List<IndexChartPriceDto>>> getIndexChartExcludingLatest(
            @PathVariable String marketCode,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "D") String dateType) {
        List<IndexChartPriceDto> priceList = kisIndexService.getIndexChartExcludingLatest(marketCode, startDate, endDate, dateType);
        return ResponseEntity.ok(new SuccessResponse<>(true, "기간별 지수 정보 조회 성공 (최신 제외)", priceList));
    }

    @PostMapping("/{marketCode}/save")
    @Operation(summary = "지수 데이터 저장", description = "특정 날짜의 지수 데이터를 조회하여 DB에 저장합니다.")
    public ResponseEntity<SuccessResponse<Void>> saveIndexDailyData(
            @PathVariable String marketCode,
            @RequestParam String date,
            @RequestParam(defaultValue = "D") String dateType) {
        kisIndexService.saveIndexDailyData(marketCode, date, dateType);
        return ResponseEntity.ok(new SuccessResponse<>(true, "지수 데이터 저장 성공", null));
    }

    @PostMapping("/{marketCode}/init-history")
    @Operation(summary = "초기 지수 데이터 구축", description = "현재부터 특정 과거 시점까지의 데이터를 반복적으로 수집하여 DB에 저장합니다.")
    public ResponseEntity<SuccessResponse<Void>> initHistoricalData(
            @PathVariable String marketCode,
            @RequestParam String startDate) {
        kisIndexService.fetchAndSaveHistoricalData(marketCode, startDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "초기 데이터 구축 시작", null));
    }

    @GetMapping("/kospi-usd-ratio")
    @Operation(summary = "달러 환산 코스피 지수 조회", description = "코스피 지수를 원/달러 환율로 나누어 달러 기준 가치를 계산합니다. (KOSPI / (환율 / 1000))")
    public ResponseEntity<SuccessResponse<List<IndexChartPriceDto>>> getKospiUsdRatio(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        List<IndexChartPriceDto> ratioList = kisIndexService.getKospiUsdRatio(startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "달러 환산 코스피 조회 성공", ratioList));
    }

    @GetMapping("/kospi-usd-ratio/excluding-latest")
    @Operation(summary = "달러 환산 코스피 지수 조회 (최신 데이터 제외)", description = "코스피 지수를 원/달러 환율로 나누어 달러 기준 가치를 계산합니다. 가장 최신 날짜의 데이터는 제외됩니다.")
    public ResponseEntity<SuccessResponse<List<IndexChartPriceDto>>> getKospiUsdRatioExcludingLatest(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        List<IndexChartPriceDto> ratioList = kisIndexService.getKospiUsdRatioExcludingLatest(startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "달러 환산 코스피 조회 성공 (최신 제외)", ratioList));
    }

    @GetMapping("/kosdaq-usd-ratio")
    @Operation(summary = "달러 환산 코스닥 지수 조회", description = "코스닥 지수를 원/달러 환율로 나누어 달러 기준 가치를 계산합니다. (KOSDAQ / (환율 / 1000))")
    public ResponseEntity<SuccessResponse<List<IndexChartPriceDto>>> getKosdaqUsdRatio(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        List<IndexChartPriceDto> ratioList = kisIndexService.getKosdaqUsdRatio(startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "달러 환산 코스닥 조회 성공", ratioList));
    }

    @GetMapping("/kosdaq-usd-ratio/excluding-latest")
    @Operation(summary = "달러 환산 코스닥 지수 조회 (최신 데이터 제외)", description = "코스닥 지수를 원/달러 환율로 나누어 달러 기준 가치를 계산합니다. 가장 최신 날짜의 데이터는 제외됩니다.")
    public ResponseEntity<SuccessResponse<List<IndexChartPriceDto>>> getKosdaqUsdRatioExcludingLatest(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        List<IndexChartPriceDto> ratioList = kisIndexService.getKosdaqUsdRatioExcludingLatest(startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "달러 환산 코스닥 조회 성공 (최신 제외)", ratioList));
    }

    @GetMapping("/vkospi-usd-ratio")
    @Operation(summary = "달러 환산 VKOSPI 지수 조회", description = "VKOSPI 지수를 원/달러 환율로 나누어 달러 기준 가치를 계산합니다. (VKOSPI / (환율 / 1000))")
    public ResponseEntity<SuccessResponse<List<IndexChartPriceDto>>> getVkospiUsdRatio(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        List<IndexChartPriceDto> ratioList = kisIndexService.getVkospiUsdRatio(startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "달러 환산 VKOSPI 조회 성공", ratioList));
    }

    @GetMapping("/vkospi-usd-ratio/excluding-latest")
    @Operation(summary = "달러 환산 VKOSPI 지수 조회 (최신 데이터 제외)", description = "VKOSPI 지수를 원/달러 환율로 나누어 달러 기준 가치를 계산합니다. 가장 최신 날짜의 데이터는 제외됩니다.")
    public ResponseEntity<SuccessResponse<List<IndexChartPriceDto>>> getVkospiUsdRatioExcludingLatest(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        List<IndexChartPriceDto> ratioList = kisIndexService.getVkospiUsdRatioExcludingLatest(startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "달러 환산 VKOSPI 조회 성공 (최신 제외)", ratioList));
    }

    @GetMapping("/status/history")
    @Operation(summary = "시장 등락 종목 수 히스토리 조회", description = "DB에 저장된 날짜별 코스피/코스닥 상승/하락 종목 수를 조회합니다.")
    public ResponseEntity<SuccessResponse<List<MarketStatusHistory>>> getMarketStatusHistory(
            @RequestParam String marketCode,
            @RequestParam String startDate,
            @RequestParam String endDate) {

        MarketType marketType = MarketType
                .valueOf(marketCode.toUpperCase());
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        LocalDate start = LocalDate.parse(startDate, formatter);
        LocalDate end = LocalDate.parse(endDate, formatter);

        List<MarketStatusHistory> history = marketStatusHistoryService
                .getHistory(marketType, start, end);

        return ResponseEntity.ok(new SuccessResponse<>(true, "시장 등락 히스토리 조회 성공", history));
    }

    @PostMapping("/status/history/generate")
    @Operation(summary = "시장 등락 종목 수 히스토리 생성", description = "특정 기간의 주식 데이터를 기반으로 상승/하락 종목 수를 계산하여 DB에 저장합니다.")
    public ResponseEntity<SuccessResponse<Void>> generateMarketStatusHistory(
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(required = false) String marketCode) {

        MarketType marketType = null;
        if (marketCode != null && !marketCode.isEmpty()) {
            marketType = MarketType.valueOf(marketCode.toUpperCase());
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        LocalDate start = LocalDate.parse(startDate, formatter);
        LocalDate end = LocalDate.parse(endDate, formatter);

        marketStatusHistoryService.generateHistory(start, end, marketType);

        return ResponseEntity.ok(new SuccessResponse<>(true, "시장 등락 히스토리 생성 완료", null));
    }

}
