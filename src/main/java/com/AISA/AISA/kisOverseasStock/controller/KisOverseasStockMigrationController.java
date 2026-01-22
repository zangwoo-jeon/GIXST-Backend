package com.AISA.AISA.kisOverseasStock.controller;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisOverseasStock.service.KisOverseasStockMigrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/overseas-stocks/migration")
@RequiredArgsConstructor
@Tag(name = "해외 주식 마이그레이션 API", description = "데이터 복구 및 1회성 마이그레이션 전용 API")
public class KisOverseasStockMigrationController {

    private final KisOverseasStockMigrationService migrationService;

    @PostMapping("/init-missing")
    @Operation(summary = "누락된 해외 주식 데이터 복구", description = "차트 데이터가 0건인 모든 해외 주식의 과거 데이터를 수집합니다. (비동기)")
    public ResponseEntity<SuccessResponse<String>> initMissingHistoricalData(
            @RequestParam String startDate) {

        new Thread(() -> migrationService.recoverMissingHistoricalData(startDate)).start();

        return ResponseEntity.ok(new SuccessResponse<>(true,
                "누락된 데이터 복구 프로세스 시작 (백그라운드)", null));
    }
}
