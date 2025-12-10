package com.AISA.AISA.kisStock.kisController;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisStock.dto.FinancialRank.FinancialRankDto;
import com.AISA.AISA.kisStock.kisService.KisInformationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rank/financial")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Financial Rank", description = "재무(매출, 영업이익, 순이익) 순위 API")
public class FinancialRankController {

    private final KisInformationService kisInformationService;

    @GetMapping
    @Operation(summary = "재무 순위 조회", description = "매출액(sale), 영업이익(operating), 순이익(netincome) 순위를 조회합니다. (기본값: sale)")
    public ResponseEntity<SuccessResponse<FinancialRankDto>> getFinancialRank(
            @RequestParam(required = false, defaultValue = "sale") String sort) {
        return ResponseEntity
                .ok(new SuccessResponse<>(true, "재무 순위 조회 성공", kisInformationService.getFinancialRank(sort)));
    }

    @PostMapping("/refresh")
    @Operation(summary = "재무 순위 데이터 새로고침", description = "재무 순위 데이터를 한국투자증권 API로부터 새로고침합니다.")
    public ResponseEntity<Void> refreshFinancialRank() {
        kisInformationService.refreshFinancialRank();
        return ResponseEntity.ok().build();
    }
}
