package com.AISA.AISA.kisStock.kisController;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisStock.dto.InvestorTrend.InvestorRankResponseDto;
import com.AISA.AISA.kisStock.kisService.KisRankService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rank")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "KIS Rank API", description = "주식 순위(수급 등) 관련 API")
public class KisRankController {

        private final KisRankService kisRankService;

        @GetMapping("/investor")
        @Operation(summary = "투자자별 수급 랭킹 조회", description = "최근 1주/1개월/3개월/6개월간 개인/외국인/기관 투자자별 순매수/순매도 랭킹을 조회합니다. "
                        + "(period: 1w, 1m, 3m, 6m / type: personal_buy, personal_sell, foreigner_buy, foreigner_sell, institution_buy, institution_sell)")
        public ResponseEntity<SuccessResponse<InvestorRankResponseDto>> getInvestorRanking(
                        @RequestParam(defaultValue = "3m") String period,
                        @RequestParam(defaultValue = "foreigner_buy") String type,
                        @RequestParam(defaultValue = "20") int limit) {
                return ResponseEntity.ok(new SuccessResponse<>(true, "투자자별 수급 랭킹 조회 성공",
                                kisRankService.getInvestorRanking(period, type, limit)));
        }
}
