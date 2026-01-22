package com.AISA.AISA.kisOverseasStock.controller;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisOverseasStock.service.OverseasDividendService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/overseas/dividend")
public class OverseasDividendController {

    private final OverseasDividendService overseasDividendService;

    @PostMapping("/refresh/{stockCode}")
    public ResponseEntity<SuccessResponse<String>> refreshDividend(@PathVariable String stockCode) {
        overseasDividendService.refreshDividendInfo(stockCode);
        return ResponseEntity.ok(new SuccessResponse<>(true, "Dividend refresh triggered for " + stockCode, null));
    }

    @PostMapping("/refresh/all")
    public ResponseEntity<SuccessResponse<String>> refreshAllDividends() {
        overseasDividendService.refreshAllOverseasDividends();
        return ResponseEntity
                .ok(new SuccessResponse<>(true, "Batch dividend refresh triggered for all US stocks", null));
    }
}
