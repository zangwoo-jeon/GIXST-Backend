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

    @GetMapping("/dividend")
    @Operation(summary = "해외 주식 배당 수익률 순위 조회", description = "배당 수익률이 높은 상위 50개 해외 주식을 조회합니다.")
    public ResponseEntity<SuccessResponse<com.AISA.AISA.kisStock.dto.DividendRank.DividendRankDto>> getOverseasDividendRank() {
        return ResponseEntity
                .ok(new SuccessResponse<>(true, "해외 주식 배당 수익률 순위 조회 성공", rankService.getOverseasDividendRank()));
    }

    @PostMapping("/dividend/refresh")
    @Operation(summary = "해외 주식 배당 순위 데이터 갱신", description = "DB에 저장된 배당 데이터를 기반으로 배당 순위를 재계산합니다.")
    public ResponseEntity<SuccessResponse<Void>> refreshOverseasDividendRank() {
        rankService.refreshOverseasDividendRank();
        return ResponseEntity.ok(new SuccessResponse<>(true, "해외 주식 배당 순위 갱신 성공", null));
    }

    @GetMapping("/financial-ratio")
    @Operation(summary = "해외 주식 재무 비율 순위 조회", description = "PER, PBR, ROE 등을 기준으로 순위를 조회합니다.")
    public ResponseEntity<SuccessResponse<com.AISA.AISA.kisOverseasStock.dto.FinancialRatioRankDto>> getFinancialRatioRanking(
            @Parameter(description = "정렬 기준 (PER, PBR, ROE)", example = "PER") @RequestParam(defaultValue = "PER") String sortType,
            @Parameter(description = "정렬 방향 (ASC, DESC)", example = "ASC") @RequestParam(defaultValue = "ASC") String direction,
            @Parameter(description = "페이지 번호 (0부터 시작)", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기", example = "20") @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(new SuccessResponse<>(true, "해외 주식 재무 비율 순위 조회 성공",
                rankService.getFinancialRatioRanking(sortType, direction, page, size)));
    }
}
