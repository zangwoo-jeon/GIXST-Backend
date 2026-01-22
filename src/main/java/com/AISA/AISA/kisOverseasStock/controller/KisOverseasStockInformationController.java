package com.AISA.AISA.kisOverseasStock.controller;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisOverseasStock.dto.OverseasStockBalanceSheetDto;
import com.AISA.AISA.kisOverseasStock.dto.OverseasStockFinancialStatementDto;
import com.AISA.AISA.kisOverseasStock.dto.OverseasStockPriceDetailDto;
import com.AISA.AISA.kisOverseasStock.service.KisOverseasStockInformationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/overseas-stocks/information")
@RequiredArgsConstructor
@Tag(name = "해외 주식 정보 API", description = "해외 주식의 재무 정보를 조회하는 API")
public class KisOverseasStockInformationController {

    private final KisOverseasStockInformationService informationService;

    @GetMapping("/financial-statement/{stockCode}")
    @Operation(summary = "해외 주식 손익계산서 조회", description = "특정 종목의 손익계산서(매출, 영업이익, 당기순이익) 데이터를 조회합니다.\n(divCode: 0: 연간, 1: 분기)")
    public ResponseEntity<SuccessResponse<List<OverseasStockFinancialStatementDto>>> getFinancialStatements(
            @PathVariable String stockCode,
            @RequestParam(defaultValue = "0") String divCode) {

        List<OverseasStockFinancialStatementDto> result = informationService.getFinancialStatements(stockCode, divCode);
        return ResponseEntity.ok(new SuccessResponse<>(true, "해외 주식 손익계산서 조회 성공", result));
    }

    @GetMapping("/balance-sheet/{stockCode}")
    @Operation(summary = "해외 주식 재무상태표 조회", description = "특정 종목의 재무상태표(자산, 부채, 자본) 데이터를 조회합니다.")
    public ResponseEntity<SuccessResponse<List<OverseasStockBalanceSheetDto>>> getBalanceSheets(
            @PathVariable String stockCode) {

        List<OverseasStockBalanceSheetDto> result = informationService.getBalanceSheets(stockCode);
        return ResponseEntity.ok(new SuccessResponse<>(true, "해외 주식 재무상태표 조회 성공", result));
    }

    @GetMapping("/price-detail/{stockCode}")
    @Operation(summary = "해외 주식 가격 상세 정보 조회", description = "특정 종목의 시가총액, 상장주수 등 상세 가격 정보를 조회합니다.")
    public ResponseEntity<SuccessResponse<OverseasStockPriceDetailDto>> getPriceDetail(
            @PathVariable String stockCode) {

        OverseasStockPriceDetailDto result = informationService.getPriceDetail(stockCode);
        return ResponseEntity.ok(new SuccessResponse<>(true, "해외 주식 가격 상세 정보 조회 성공", result));
    }
}
