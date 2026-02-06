package com.AISA.AISA.kisStock.kisController;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisStock.Entity.stock.FuturesInvestorDaily;
import com.AISA.AISA.kisStock.enums.FuturesMarketType;
import com.AISA.AISA.kisStock.repository.FuturesInvestorDailyRepository;
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

    @GetMapping("/{marketCode}/investor-trend")
    @Operation(summary = "선물 투자자 매매동향 조회", description = "특정 지수 선물(KOSPI200, KOSDAQ150)의 투자자별 순매수 금액을 조회합니다.")
    public ResponseEntity<SuccessResponse<List<FuturesInvestorDaily>>> getInvestorTrend(
            @PathVariable String marketCode,
            @RequestParam @DateTimeFormat(pattern = "yyyyMMdd") LocalDate startDate,
            @RequestParam @DateTimeFormat(pattern = "yyyyMMdd") LocalDate endDate) {

        FuturesMarketType marketType = FuturesMarketType.valueOf(marketCode.toUpperCase());
        List<FuturesInvestorDaily> trend = repository.findAllByMarketTypeAndDateBetweenOrderByDateAsc(marketType,
                startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, marketType.getDescription() + " 투자자 매매동향 조회 성공", trend));
    }
}
