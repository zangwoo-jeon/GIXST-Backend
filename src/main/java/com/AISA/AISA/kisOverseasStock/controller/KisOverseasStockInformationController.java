package com.AISA.AISA.kisOverseasStock.controller;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisOverseasStock.dto.OverseasStockBalanceSheetDto;
import com.AISA.AISA.kisOverseasStock.dto.OverseasStockFinancialStatementDto;
import com.AISA.AISA.kisOverseasStock.dto.OverseasStockPriceDetailDto;
import com.AISA.AISA.kisOverseasStock.dto.OverseasFinancialRatioDto;
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
@Tag(name = "해외 주식 정보 API", description = "해외 주식의 재무 및 가격 정보 관련 API")
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

    @GetMapping("/shareholder-return/{stockCode}")
    @Operation(summary = "해외 주식 주주환원율 정보 조회", description = "특정 종목의 자사주 매입 및 배당금 지급 데이터를 조회합니다. (연간 데이터 기준)")
    public ResponseEntity<SuccessResponse<List<com.AISA.AISA.kisOverseasStock.dto.OverseasStockCashFlowDto>>> getShareholderReturnInfo(
            @PathVariable String stockCode) {
        var result = informationService.getShareholderReturnInfo(stockCode);
        return ResponseEntity.ok(new SuccessResponse<>(true, "해외 주식 주주환원율 정보 조회 성공", result));
    }

    @PostMapping("/suspension-status/{stockCode}")
    @Operation(summary = "해외 주식 거래정지 여부 업데이트", description = "KIS 상품 정보 조회 API를 통해 특정 종목의 거래정지 여부를 확인하고 업데이트합니다.")
    public ResponseEntity<SuccessResponse<String>> updateSuspensionStatus(
            @PathVariable String stockCode) {
        informationService.updateSuspensionStatus(stockCode);
        return ResponseEntity.ok(new SuccessResponse<>(true, "해외 주식 거래정지 여부 업데이트 성공", null));
    }

    @PostMapping("/suspension-status/refresh-all")
    @Operation(summary = "전체 해외 주식 거래정지 여부 업데이트", description = "모든 해외 주식(US_STOCK)에 대해 거래정지 여부를 확인하고 일괄 업데이트합니다.")
    public ResponseEntity<SuccessResponse<String>> updateAllSuspensionStatus() {
        informationService.updateAllSuspensionStatus();
        return ResponseEntity.ok(new SuccessResponse<>(true, "전체 해외 주식 거래정지 여부 업데이트 시작", null));
    }

    @GetMapping("/price-detail/{stockCode}")
    @Operation(summary = "해외 주식 가격 상세 정보 조회", description = "특정 종목의 시가총액, 상장주수 등 상세 가격 정보를 조회합니다.")
    public ResponseEntity<SuccessResponse<OverseasStockPriceDetailDto>> getPriceDetail(
            @PathVariable String stockCode) {

        OverseasStockPriceDetailDto result = informationService.getPriceDetail(stockCode);
        return ResponseEntity.ok(new SuccessResponse<>(true, "해외 주식 가격 상세 정보 조회 성공", result));
    }

    @GetMapping("/financial-ratio/{stockCode}")
    @Operation(summary = "해외 주식 실시간 투자 지표 조회", description = "특정 종목의 실시간 PER, PBR, PSR과 DB의 최신 EPS, BPS, ROE를 조합하여 조회합니다.")
    public ResponseEntity<SuccessResponse<OverseasFinancialRatioDto>> getFinancialRatios(
            @PathVariable String stockCode) {
        OverseasFinancialRatioDto result = informationService.getRealTimeFinancialRatio(stockCode);
        return ResponseEntity.ok(new SuccessResponse<>(true, "해외 주식 실시간 투자 지표 조회 성공", result));
    }

    @PostMapping("/financial-ratio/refresh/{stockCode}")
    @Operation(summary = "해외 주식 투자 지표 갱신", description = "실시간 시세와 DB에 저장된 재무 데이터를 기반으로 투자 지표를 계산하고 갱신합니다.")
    public ResponseEntity<SuccessResponse<String>> refreshFinancialRatios(
            @PathVariable String stockCode,
            @RequestParam(defaultValue = "0") String divCode) {
        informationService.calculateAndSaveFinancialRatios(stockCode, divCode);
        return ResponseEntity.ok(new SuccessResponse<>(true, "해외 주식 투자 지표 갱신 성공", null));
    }

    @PostMapping("/financial-ratio/refresh-all")
    @Operation(summary = "전체 해외 주식 투자 지표 갱신", description = "모든 해외 종목의 투자 지표를 일괄적으로 계산하고 갱신합니다.")
    public ResponseEntity<SuccessResponse<String>> refreshAllFinancialRatios(
            @RequestParam(defaultValue = "0") String divCode) {
        informationService.refreshAllFinancialRatios(divCode);
        return ResponseEntity.ok(new SuccessResponse<>(true, "전체 해외 주식 투자 지표 갱신 시작", null));
    }

}
