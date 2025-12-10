package com.AISA.AISA.portfolio.PortfolioStock;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.portfolio.PortfolioStock.dto.PortStockAddRequest;
import com.AISA.AISA.portfolio.PortfolioStock.dto.PortStockResponse;
import com.AISA.AISA.portfolio.PortfolioStock.dto.PortStockUpdateRequest;
import com.AISA.AISA.portfolio.PortfolioStock.dto.PortfolioReturnResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RequestMapping("/api/port-stock")
@RestController
@RequiredArgsConstructor
@Tag(name = "포트폴리오 주식 API", description = "포트폴리오 내의 주식 관련 API")
public class PortStockController {
    private final PortStockService portStockService;

    @PostMapping("/add/{portId}")
    @Operation(summary = "포트폴리오 종목 추가", description = "특정 포트폴리오에 종목을 추가합니다.")
    public ResponseEntity<SuccessResponse<PortStock>> addPortStock(
            @PathVariable UUID portId,
            @RequestBody PortStockAddRequest request) {
        PortStock addedStock = portStockService.addStock(portId, request);
        return ResponseEntity.ok(new SuccessResponse<>(true, "포트폴리오 종목 추가 성공", addedStock));
    }

    @DeleteMapping("/{portStockId}")
    @Operation(summary = "포트폴리오 종목 삭제", description = "포트폴리오 내의 종목을 삭제합니다.")
    public ResponseEntity<SuccessResponse<Void>> removePortStock(
            @PathVariable UUID portStockId) {
        portStockService.removeStock(portStockId);
        return ResponseEntity.ok(new SuccessResponse<>(true, "포트폴리오 종목 삭제 성공", null));
    }

    @PutMapping("/{portStockId}")
    @Operation(summary = "포트폴리오 종목 수정", description = "포트폴리오 내의 종목 정보(수량, 평단가, 순서)를 수정합니다.")
    public ResponseEntity<SuccessResponse<Void>> updatePortStock(
            @PathVariable UUID portStockId,
            @RequestBody PortStockUpdateRequest request) {
        portStockService.updateStock(portStockId, request);
        return ResponseEntity.ok(new SuccessResponse<>(true, "포트폴리오 종목 수정 성공", null));
    }

    @GetMapping("/{portId}")
    @Operation(summary = "포트폴리오 종목 조회", description = "특정 포트폴리오에 포함된 종목 목록을 조회합니다.")
    public ResponseEntity<SuccessResponse<List<PortStockResponse>>> getPortStocks(
            @PathVariable UUID portId) {
        return ResponseEntity.ok(new SuccessResponse<>(true, "포트폴리오 종목 조회 성공", portStockService.getPortStocks(portId)));
    }

    @GetMapping("/return/{portId}")
    @Operation(summary = "포트폴리오 수익률 조회", description = "특정 포트폴리오의 종목별 및 전체 수익률을 조회합니다.")
    public ResponseEntity<SuccessResponse<PortfolioReturnResponse>> getPortfolioReturn(
            @PathVariable UUID portId) {
        return ResponseEntity
                .ok(new SuccessResponse<>(true, "포트폴리오 수익률 조회 성공", portStockService.getPortfolioReturn(portId)));
    }
}
