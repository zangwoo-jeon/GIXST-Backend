package com.AISA.AISA.kisStock.kisController;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisStock.Entity.stock.FuturesInvestorDaily;
import com.AISA.AISA.kisStock.enums.FuturesMarketType;
import com.AISA.AISA.kisStock.repository.FuturesInvestorDailyRepository;
import com.AISA.AISA.kisStock.kisService.FuturesInvestorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/futures")
@RequiredArgsConstructor
@Tag(name = "선물 API", description = "선물 관련 데이터 조회 API")
public class FuturesInvestorController {

    private final FuturesInvestorDailyRepository repository;
    private final FuturesInvestorService futuresInvestorService;

    @GetMapping("/{marketCode}/investor-trend")
    @Operation(summary = "선물 투자자 매매동향 조회", description = "특정 지수 선물(KOSPI200, KOSDAQ150)의 투자자별 순매수 금액을 조회합니다.")
    public ResponseEntity<SuccessResponse<List<FuturesInvestorDaily>>> getInvestorTrend(
            @PathVariable String marketCode,
            @RequestParam @DateTimeFormat(pattern = "yyyyMMdd") LocalDate startDate,
            @RequestParam @DateTimeFormat(pattern = "yyyyMMdd") LocalDate endDate) {

        FuturesMarketType marketType = mapMarketCode(marketCode);
        List<FuturesInvestorDaily> trend = repository.findAllByMarketTypeAndDateBetweenOrderByDateAsc(marketType,
                startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, marketType.getDescription() + " 투자자 매매동향 조회 성공", trend));
    }

    @PostMapping("/refresh")
    @Operation(summary = "선물 투자자 매매동향 갱신", description = "KIS API를 호출하여 최신 선물 투자자 매매동향 데이터를 동기화합니다.")
    public ResponseEntity<SuccessResponse<Void>> refreshInvestorTrend() {
        futuresInvestorService.refreshFuturesInvestorTrend();
        return ResponseEntity.ok(new SuccessResponse<>(true, "선물 투자자 매매동향 갱신 성공", null));
    }

    @GetMapping("/{marketCode}/today")
    @Operation(summary = "오늘의 선물 투자자 매매동향 조회", description = "KIS API를 실시간으로 호출하여 특정 지수 선물(KOSPI200, KOSDAQ150)의 오늘 투자자별 순매수 금액을 조회합니다.")
    public ResponseEntity<SuccessResponse<FuturesInvestorDaily>> getTodayInvestorTrend(
            @PathVariable String marketCode) {

        FuturesMarketType marketType = mapMarketCode(marketCode);
        FuturesInvestorDaily todayTrend = futuresInvestorService.getTodayTrend(marketType);
        return ResponseEntity
                .ok(new SuccessResponse<>(true, marketType.getDescription() + " 오늘의 투자자 매매동향 조회 성공", todayTrend));
    }

    private FuturesMarketType mapMarketCode(String marketCode) {
        String code = marketCode.toUpperCase();
        return switch (code) {
            case "KOSPI", "KOSPI200" -> FuturesMarketType.KOSPI200;
            case "KOSDAQ", "KOSDAQ150" -> FuturesMarketType.KOSDAQ150;
            default -> FuturesMarketType.valueOf(code);
        };
    }
}
