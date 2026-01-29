package com.AISA.AISA.kisOverseasStock.controller;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisOverseasStock.dto.OverseasStockRankDto;
import com.AISA.AISA.kisOverseasStock.service.KisOverseasStockRankService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/overseas-stocks/rank")
@RequiredArgsConstructor
@Tag(name = "해외 주식 랭킹 API", description = "해외 주식의 시가총액 등 다양한 순위 정보를 제공합니다.")
public class KisOverseasStockRankController {

    private final KisOverseasStockRankService rankService;

    @GetMapping("/market-cap")
    @Operation(summary = "해외 주식 시가총액 순위 조회", description = "미국 시장(나스닥, 뉴욕, 아멕스)의 시가총액 순위를 조회합니다.")
    public ResponseEntity<SuccessResponse<OverseasStockRankDto>> getMarketCapRanking(
            @Parameter(description = "거래소 코드 (NAS: 나스닥, NYS: 뉴욕, AMS: 아멕스)", example = "NAS") @RequestParam(defaultValue = "NAS") String exchangeCode) {

        OverseasStockRankDto result = rankService.getMarketCapRanking(exchangeCode);
        return ResponseEntity.ok(new SuccessResponse<>(true, "해외 주식 시가총액 순위 조회 성공", result));
    }
}
