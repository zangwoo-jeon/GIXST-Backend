package com.AISA.AISA.fred.controller;

import com.AISA.AISA.fred.dto.FredIndexDataDto;
import com.AISA.AISA.fred.enums.FredIndex;
import com.AISA.AISA.fred.service.FredIndexService;
import com.AISA.AISA.global.response.SuccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/indices/fred")
@RequiredArgsConstructor
@Tag(name = "FRED 지수 API", description = "미국 연방준비은행(FRED) 데이터 기반 지수 API (S&P 500, 나스닥 종합)")
public class FredIndexController {

    private final FredIndexService fredIndexService;

    @GetMapping("/{indexName}")
    @Operation(
            summary = "FRED 지수 조회",
            description = "FRED에서 가져온 지수 데이터(종가)를 조회합니다. indexName: SP500, NASDAQ"
    )
    public ResponseEntity<SuccessResponse<List<FredIndexDataDto>>> getFredIndexChart(
            @PathVariable String indexName,
            @RequestParam String startDate,
            @RequestParam String endDate) {
        FredIndex index = FredIndex.valueOf(indexName.toUpperCase());
        List<FredIndexDataDto> data = fredIndexService.getFredIndexChart(index, startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, index.getDescription() + " 조회 성공", data));
    }

    @GetMapping("/{indexName}/krw")
    @Operation(
            summary = "FRED 지수 원화 환산 조회",
            description = "FRED 지수 데이터를 원화로 환산하여 조회합니다. 계산식: (지수 * 원/달러 환율) / 1000. indexName: SP500, NASDAQ"
    )
    public ResponseEntity<SuccessResponse<List<FredIndexDataDto>>> getFredIndexChartKrw(
            @PathVariable String indexName,
            @RequestParam String startDate,
            @RequestParam String endDate) {
        FredIndex index = FredIndex.valueOf(indexName.toUpperCase());
        List<FredIndexDataDto> data = fredIndexService.getFredIndexChartKrw(index, startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, index.getDescription() + " 원화 환산 조회 성공", data));
    }

    @PostMapping("/{indexName}/init")
    @Operation(
            summary = "FRED 지수 데이터 초기화/업데이트",
            description = "FRED에서 지수 데이터를 가져와 DB에 저장합니다. indexName: SP500, NASDAQ"
    )
    public ResponseEntity<SuccessResponse<Void>> initFredIndex(
            @PathVariable String indexName,
            @RequestParam String startDate,
            @RequestParam String endDate) {
        FredIndex index = FredIndex.valueOf(indexName.toUpperCase());
        fredIndexService.fetchAndSave(index, startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, index.getDescription() + " 데이터 저장 성공", null));
    }
}
