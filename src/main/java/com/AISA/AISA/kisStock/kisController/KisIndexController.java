package com.AISA.AISA.kisStock.kisController;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisStock.dto.Index.IndexChartInfoDto;
import com.AISA.AISA.kisStock.dto.Index.IndexChartResponseDto;
import com.AISA.AISA.kisStock.kisService.KisIndexService;
import com.AISA.AISA.kisStock.dto.Index.OverseasIndexStatusDto;

import com.AISA.AISA.kisStock.enums.OverseasIndex;
import com.AISA.AISA.kisStock.dto.Index.IndexChartPriceDto; // [NEW] added
import java.util.List;
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
    private final com.AISA.AISA.portfolio.macro.service.MacroService macroService;

    @GetMapping("/{marketCode}/status")
    @Operation(summary = "지수 현재 상태 조회", description = "코스피(kospi) / 코스닥(kosdaq)의 실시간 지수 정보를 조회합니다.")
    public ResponseEntity<SuccessResponse<IndexChartInfoDto>> getIndexStatus(@PathVariable String marketCode) {
        IndexChartInfoDto statusData = kisIndexService.getIndexStatus(marketCode);
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

    @GetMapping("/overseas/{indexName}")
    @Operation(summary = "해외 지수 조회", description = "주요 해외 지수(NASDAQ, SP500, NIKKEI, HANGSENG, EUROSTOXX50)를 조회합니다.")
    public ResponseEntity<SuccessResponse<List<IndexChartPriceDto>>> getOverseasIndex(
            @PathVariable String indexName,
            @RequestParam String startDate,
            @RequestParam String endDate) {
        OverseasIndex index = OverseasIndex.valueOf(indexName.toUpperCase());
        List<IndexChartPriceDto> data = kisIndexService.fetchOverseasIndex(index, startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, index.getDescription() + " 조회 성공", data));
    }

    @GetMapping("/overseas/{indexName}/status")
    @Operation(summary = "해외 지수 현재가 조회", description = "해외 지수(NASDAQ, SP500, NIKKEI, HANGSENG, EUROSTOXX50)의 최신 종가를 KIS API에서 실시간으로 조회합니다.")
    public ResponseEntity<SuccessResponse<OverseasIndexStatusDto>> getOverseasIndexStatus(
            @PathVariable String indexName) {
        OverseasIndex index = OverseasIndex.valueOf(indexName.toUpperCase());
        OverseasIndexStatusDto data = kisIndexService.getOverseasIndexStatus(index);
        return ResponseEntity.ok(new SuccessResponse<>(true, index.getDescription() + " 현재가 조회 성공", data));
    }

    @PostMapping("/overseas/init")
    @Operation(summary = "해외 지수 데이터 초기화/업데이트", description = "해외 지수 데이터(NASDAQ, SP500, NIKKEI, HANGSENG, EUROSTOXX50)를 KIS API에서 가져와 DB에 저장합니다.")
    public ResponseEntity<SuccessResponse<Void>> initOverseasIndex(
            @RequestParam String indexName,
            @RequestParam String startDate,
            @RequestParam String endDate) {
        OverseasIndex index = OverseasIndex.valueOf(indexName.toUpperCase());
        kisIndexService.fetchAndSaveOverseasIndex(index, startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, index.getDescription() + " 데이터 저장 성공", null));
    }

    @GetMapping("/overseas/{indexName}/krw")
    @Operation(summary = "원화 환산 해외 지수 조회", description = "해외 지수(NASDAQ, SP500, NIKKEI, HANGSENG, EUROSTOXX50)를 원화 가치로 환산하여 조회합니다. 계산식: (지수 * 환율) / 1000")
    public ResponseEntity<SuccessResponse<List<IndexChartPriceDto>>> getOverseasIndexKrw(
            @PathVariable String indexName,
            @RequestParam String startDate,
            @RequestParam String endDate) {
        List<IndexChartPriceDto> data = macroService.getWonConvertedOverseasIndex(indexName, startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, indexName + " 원화 환산 조회 성공", data));
    }

}
