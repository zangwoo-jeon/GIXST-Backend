package com.AISA.AISA.kisStock.kisController;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisStock.dto.Dividend.DividendCalendarRequestDto;
import com.AISA.AISA.kisStock.dto.Dividend.DividendCalendarResponseDto;
import com.AISA.AISA.kisStock.dto.Dividend.StockDividendInfoDto;
import com.AISA.AISA.kisStock.dto.Dividend.DividendDetailDto;
import com.AISA.AISA.kisStock.dto.DividendRank.DividendRankDto;

import com.AISA.AISA.kisStock.kisService.DividendService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequestMapping("/api/dividend")
@RequiredArgsConstructor
@Tag(name = "배당 API", description = "배당 관련 API")
public class DividendController {

    private final DividendService dividendService;

    @GetMapping("/{stockCode}/dividend")
    @Operation(summary = "주식 배당 내역 조회", description = "특정 주식의 과거 배당금 지급 내역을 조회합니다.")
    public ResponseEntity<SuccessResponse<List<StockDividendInfoDto>>> getDividendInfo(
            @PathVariable String stockCode,
            @RequestParam String startDate,
            @RequestParam String endDate) {
        List<StockDividendInfoDto> dividendInfoList = dividendService.getDividendInfo(stockCode, startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "주식 배당금 조회 성공", dividendInfoList));
    }

    @GetMapping("/{stockCode}/detail")
    @Operation(summary = "주식 배당 상세 정보 조회", description = "특정 주식의 배당수익률, 배당성향, 배당주기 등 상세 정보를 조회합니다.")
    public ResponseEntity<SuccessResponse<DividendDetailDto>> getDividendDetail(@PathVariable String stockCode) {
        DividendDetailDto detail = dividendService.getDividendDetail(stockCode);
        return ResponseEntity.ok(new SuccessResponse<>(true, "주식 배당 상세 정보 조회 성공", detail));
    }

    @PostMapping("/rank/refresh")
    @Operation(summary = "배당률 순위 갱신 (DB 기반)", description = "DB에 저장된 배당 정보를 바탕으로 배당수익률 순위를 갱신합니다. (API 호출 없음, 빠름)")
    public ResponseEntity<SuccessResponse<String>> refreshDividendRank() {
        dividendService.refreshDividendRank();
        return ResponseEntity.ok(new SuccessResponse<>(true, "배당률 순위 갱신 완료", null));
    }

    @PostMapping("/refresh-all")
    @Operation(summary = "전체 배당 데이터 갱신 (KIS API)", description = "모든 주식의 배당 정보를 KIS API를 통해 최신화합니다. (시간이 오래 걸림)")
    public ResponseEntity<SuccessResponse<String>> refreshAllDividends(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        if (startDate == null || endDate == null) {
            // Default to last 1 year
            endDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            startDate = LocalDate.now().minusYears(1)
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        }

        dividendService.refreshAllDividends(startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "전체 배당 데이터 갱신 완료", null));
    }

    @PostMapping("/{stockCode}/refresh")
    @Operation(summary = "특정 종목 배당 데이터 갱신 (KIS API)", description = "특정 종목의 배당 정보를 KIS API를 통해 최신화합니다.")
    public ResponseEntity<SuccessResponse<String>> refreshStockDividend(
            @PathVariable String stockCode,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        if (startDate == null || endDate == null) {
            // Default to last 1 year
            endDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            startDate = LocalDate.now().minusYears(1)
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        }

        dividendService.refreshStockDividend(stockCode, startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, stockCode + " 배당 데이터 갱신 완료", null));
    }

    @GetMapping("/{stockCode}/fetch-kis")
    @Operation(summary = "특정 종목 배당 KIS API 직접 조회", description = "DB를 거치지 않고 KIS API에서 직접 배당 정보를 조회하여 반환합니다. (동시에 DB 저장도 수행됨)")
    public ResponseEntity<SuccessResponse<List<StockDividendInfoDto>>> fetchDividendFromKis(
            @PathVariable String stockCode,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        if (startDate == null || endDate == null) {
            endDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            startDate = LocalDate.now().minusYears(1)
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        }

        List<StockDividendInfoDto> result = dividendService.refreshStockDividend(stockCode, startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "KIS API 배당 정보 직접 조회 성공", result));
    }

    @GetMapping("/rank")
    @Operation(summary = "배당률 순위 조회", description = "저장된 배당수익률 상위 순위를 조회합니다.")
    public ResponseEntity<SuccessResponse<DividendRankDto>> getDividendRank() {
        DividendRankDto rank = dividendService.getDividendRank();
        return ResponseEntity.ok(new SuccessResponse<>(true, "배당률 순위 조회 성공", rank));
    }

    @GetMapping("/calendar")
    @Operation(summary = "배당 캘린더 조회", description = "특정 월에 배당락일이 포함된 종목들의 리스트를 조회합니다. (Frontend 필터링용 전체 데이터 반환)")
    public ResponseEntity<SuccessResponse<List<StockDividendInfoDto>>> getDividendCalendar(
            @RequestParam int year,
            @RequestParam int month) {
        List<StockDividendInfoDto> dividends = dividendService.getDividendCalendar(year, month);
        return ResponseEntity.ok(new SuccessResponse<>(true, "배당 캘린더 조회 성공", dividends));
    }

    @GetMapping("/calendar/list")
    @Operation(summary = "내 포트폴리오 배당 캘린더 조회", description = "내 포트폴리오에 포함된 종목들의 특정 월 배당 정보를 조회합니다.")
    public ResponseEntity<SuccessResponse<DividendCalendarResponseDto>> getPortfolioDividendCalendar(
            @ModelAttribute DividendCalendarRequestDto request) {
        DividendCalendarResponseDto response = dividendService
                .getPortfolioDividendCalendar(request);
        return ResponseEntity.ok(new SuccessResponse<>(true, "내 포트폴리오 배당 캘린더 조회 성공", response));
    }

}
