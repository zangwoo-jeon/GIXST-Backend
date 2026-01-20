package com.AISA.AISA.kisOverseasStock.controller;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisOverseasStock.service.KisOverseasStockService;
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
}
