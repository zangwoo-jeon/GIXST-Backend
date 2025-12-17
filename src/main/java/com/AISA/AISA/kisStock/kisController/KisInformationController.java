package com.AISA.AISA.kisStock.kisController;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisStock.dto.FinancialRank.BalanceSheetDto;
import com.AISA.AISA.kisStock.dto.FinancialRank.FinancialStatementDto;
import com.AISA.AISA.kisStock.dto.FinancialRank.KisIncomeStatementApiResponse;
import com.AISA.AISA.kisStock.dto.FinancialRank.FinancialRatioRankDto;
import com.AISA.AISA.kisStock.dto.FinancialRank.InvestmentMetricDto;
import com.AISA.AISA.kisStock.kisService.KisInformationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
        KisIncomeStatementApiResponse response = kisInformationService.getIncomeStatement(stockCode, divCode);

        List<FinancialStatementDto> simplifiedList = new ArrayList<>();
        if (response != null && response.getOutput() != null) {
            // 1. Sort ascending by Date (stac_yymm) to simplify calculation
            List<KisIncomeStatementApiResponse.IncomeStatementOutput> sortedOutput = response.getOutput().stream()
                    .sorted((o1, o2) -> o1.getStacYymm().compareTo(o2.getStacYymm()))
                    .collect(Collectors.toList());

            for (int i = 0; i < sortedOutput.size(); i++) {
                KisIncomeStatementApiResponse.IncomeStatementOutput current = sortedOutput.get(i);

                String date = current.getStacYymm();
                long sales = parseLongSafe(current.getSaleAccount());
                long operatingProfit = parseLongSafe(current.getBsopPrti());
                long netIncome = parseLongSafe(current.getThtrNtin());

                // Logic: If divCode is "1" (Quarter) and month is not 03 (March), subtract
                // previous quarter's accumulated value
                if ("1".equals(divCode) && date.length() == 6 && !date.endsWith("03")) {
                    // Check if previous entry is from the same year and is the previous quarter
                    if (i > 0) {
                        KisIncomeStatementApiResponse.IncomeStatementOutput prev = sortedOutput.get(i - 1);
                        if (prev.getStacYymm().startsWith(date.substring(0, 4))) {
                            sales -= parseLongSafe(prev.getSaleAccount());
                            operatingProfit -= parseLongSafe(prev.getBsopPrti());
                            netIncome -= parseLongSafe(prev.getThtrNtin());
                        }
                    }
                }

                simplifiedList.add(FinancialStatementDto.builder()
                        .stacYymm(date)
                        .saleAccount(String.valueOf(sales))
                        .operatingProfit(String.valueOf(operatingProfit))
                        .netIncome(String.valueOf(netIncome))
                        .build());
            }

            // 2. Sort descending by Date (stac_yymm) for final output as requested
            simplifiedList.sort((o1, o2) -> o2.getStacYymm().compareTo(o1.getStacYymm()));
        }

        return ResponseEntity.ok(new SuccessResponse<>(true, "손익계산서 조회 성공", simplifiedList));
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

    private long parseLongSafe(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            // Remove comma or decimal points if any (API might return "1234.00")
            if (value.contains(".")) {
                return (long) Double.parseDouble(value);
            }
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
