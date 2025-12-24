package com.AISA.AISA.kisStock.kisController;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisStock.dto.FinancialRank.BalanceSheetDto;
import com.AISA.AISA.kisStock.dto.FinancialRank.FinancialStatementDto;
import com.AISA.AISA.kisStock.dto.FinancialRank.FinancialStatementDto;
import com.AISA.AISA.kisStock.dto.FinancialRank.FinancialRatioRankDto;
import com.AISA.AISA.kisStock.dto.FinancialRank.InvestmentMetricDto;
import com.AISA.AISA.kisStock.kisService.KisInformationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stocks/financial")
@RequiredArgsConstructor
@Tag(name = "주식 정보 API", description = "주식 재무/정보 관련 API")
public class KisInformationController {

    private final KisInformationService kisInformationService;

    @GetMapping("/balance-sheet/{stockCode}")
    @Operation(summary = "특정 종목 대차대조표 조회 (DB)", description = "특정 종목의 자산/부채/자본 정보를 DB에서 조회합니다. (0:년, 1:분기)")
    public ResponseEntity<SuccessResponse<List<BalanceSheetDto>>> getBalanceSheet(
            @PathVariable String stockCode,
            @RequestParam(required = false, defaultValue = "0") String divCode) {
        return ResponseEntity
                .ok(new SuccessResponse<>(true, "대차대조표 조회 성공",
                        kisInformationService.getBalanceSheet(stockCode, divCode)));
    }

    @PostMapping("/balance-sheet/refresh/{stockCode}")
    @Operation(summary = "특정 종목 대차대조표 갱신 (API -> DB)", description = "KIS API에서 최신 대차대조표 정보를 가져와 DB에 저장 후 반환합니다. (0:년, 1:분기)")
    public ResponseEntity<SuccessResponse<List<BalanceSheetDto>>> refreshBalanceSheet(
            @PathVariable String stockCode,
            @RequestParam(required = false, defaultValue = "0") String divCode) {
        return ResponseEntity
                .ok(new SuccessResponse<>(true, "대차대조표 갱신 성공",
                        kisInformationService.refreshBalanceSheet(stockCode, divCode)));
    }

    @PostMapping("/balance-sheet/init-all")
    @Operation(summary = "전체 주식 대차대조표 DB 초기화/갱신", description = "모든 종목의 대차대조표 정보를 KIS API에서 가져와 DB에 저장합니다. (비동기, 0:년, 1:분기)")
    public ResponseEntity<SuccessResponse<String>> initAllBalanceSheets(
            @RequestParam(required = false, defaultValue = "0") String divCode) {
        new Thread(() -> kisInformationService.refreshAllBalanceSheets(divCode)).start();
        return ResponseEntity
                .ok(new SuccessResponse<>(true, "전체 종목 대차대조표 갱신 시작 (백그라운드)",
                        "Started background task for divCode=" + divCode));
    }

    @GetMapping("/{stockCode}")
    @Operation(summary = "재무제표(손익계산서) 조회", description = "특정 주식의 매출액, 영업이익, 당기순이익 등 재무 정보를 조회합니다. (0:년, 1:분기)")
    public ResponseEntity<SuccessResponse<List<FinancialStatementDto>>> getIncomeStatement(
            @PathVariable String stockCode,
            @RequestParam(required = false, defaultValue = "0") String divCode) {
        return ResponseEntity.ok(new SuccessResponse<>(true, "손익계산서 조회 성공",
                kisInformationService.getIncomeStatement(stockCode, divCode)));
    }

    @PostMapping("/init-all")
    @Operation(summary = "전체 주식 재무제표(손익계산서+랭킹) 초기 데이터 구축", description = "모든 주식에 대해 최근 5년치 재무제표 데이터를 수집하여 DB에 저장합니다. (비동기 실행)")
    public ResponseEntity<SuccessResponse<String>> initFinancialStatementsAll() {
        new Thread(() -> kisInformationService.fetchAndSaveAllFinancialStatements()).start();
        return ResponseEntity
                .ok(new SuccessResponse<>(true, "전체 주식 재무제표 데이터 구축 시작 (백그라운드 실행)", "Started background task"));
    }

    @PostMapping("/ratio/init-all")
    @Operation(summary = "전체 주식 재무비율(ROE/EPS 등) 갱신", description = "모든 주식에 대해 KIS API를 통해 재무비율을 조회하고 DB에 저장합니다. (분기/연간 선택 가능)")
    public ResponseEntity<SuccessResponse<String>> initAllFinancialRatios(
            @RequestParam(required = false, defaultValue = "0") String divCode) {
        new Thread(() -> kisInformationService.refreshAllFinancialRatios(divCode)).start();
        return ResponseEntity
                .ok(new SuccessResponse<>(true, "전체 주식 재무비율 갱신 시작 (백그라운드 실행)",
                        "Started background task for divCode=" + divCode));
    }

    @GetMapping("/rank/ratio")
    @Operation(summary = "재무비율 기반 랭킹 조회", description = "DB에 저장된 재무비율을 기준으로 랭킹을 조회합니다. (eps, debt, roe, per, pbr, psr)")
    public ResponseEntity<SuccessResponse<FinancialRatioRankDto>> getFinancialRatioRank(
            @RequestParam(defaultValue = "roe") String sort,
            @RequestParam(required = false, defaultValue = "0") String divCode) {
        return ResponseEntity
                .ok(new SuccessResponse<>(true, "재무비율 랭킹 조회 성공 (" + sort + ")",
                        kisInformationService.getFinancialRatioRank(sort, divCode)));
    }

    @GetMapping("/metrics/{stockCode}")
    @Operation(summary = "특정 종목 투자 지표 조회", description = "특정 종목의 PER, PBR, PSR, EPS, ROE, BPS 정보를 조회합니다.")
    public ResponseEntity<SuccessResponse<InvestmentMetricDto>> getInvestmentMetrics(
            @PathVariable String stockCode) {
        return ResponseEntity
                .ok(new SuccessResponse<>(true, "투자 지표 조회 성공",
                        kisInformationService.getInvestmentMetrics(stockCode)));
    }

}
