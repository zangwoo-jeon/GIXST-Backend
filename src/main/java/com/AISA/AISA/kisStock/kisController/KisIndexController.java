package com.AISA.AISA.kisStock.kisController;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisStock.dto.Index.IndexChartInfoDto;
import com.AISA.AISA.kisStock.dto.Index.IndexChartResponseDto;
import com.AISA.AISA.kisStock.kisService.KisIndexService;
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
}
