package com.AISA.AISA.kisStock.controller;

import com.AISA.AISA.kisStock.dto.StockPrice.EtfConstituentResponseDto;
import com.AISA.AISA.kisStock.dto.StockPrice.EtfDetailResponseDto;
import com.AISA.AISA.kisStock.kisService.EtfService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.AISA.AISA.global.response.SuccessResponse;
import java.util.List;

@RestController
@RequestMapping("/api/etf")
@RequiredArgsConstructor
@Tag(name = "ETF", description = "ETF 상세 정보 및 비교 API")
public class EtfController {

    private final EtfService etfService;

    @GetMapping("/{stockCode}/detail")
    @Operation(summary = "ETF 상세 정보 조회", description = "단축코드를 통해 ETF의 기초지수, 총보수, 운용사 등의 정보를 조회합니다.")
    public ResponseEntity<EtfDetailResponseDto> getEtfDetail(@PathVariable String stockCode) {
        return etfService.getEtfDetail(stockCode).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/rank/expense")
    @Operation(summary = "총보수 낮은 순 ETF 랭킹", description = "전체 ETF 중 총보수가 낮은 순으로 상위 N개를 조회합니다.")
    public ResponseEntity<List<EtfDetailResponseDto>> getLowExpenseEtfs(@RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(etfService.getTopEtfsByLowExpense(limit));
    }

    @GetMapping("/search/index")
    @Operation(summary = "기초지수명으로 ETF 검색", description = "기초지수명에 특정 단어가 포함된 ETF 목록을 조회합니다.")
    public ResponseEntity<List<EtfDetailResponseDto>> searchEtfsByIndex(@RequestParam String indexName) {
        return ResponseEntity.ok(etfService.getEtfsByUnderlyingIndex(indexName));
    }

    @PostMapping("/{stockCode}/constituents/sync")
    @Operation(summary = "ETF 구성 종목 동기화", description = "네이버 금융에서 특정 ETF의 구성 종목 및 비중 정보를 가져와 DB에 저장합니다.")
    public ResponseEntity<Void> syncEtfConstituents(@PathVariable String stockCode) {
        etfService.syncConstituents(stockCode);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/constituents/sync-all")
    @Operation(summary = "ETF 구성종목 전체 동기화 (국내/해외 ETF)", description = "국내/해외(국내상장) ETF의 구성종목을 Naver Finance API를 통해 일괄 동기화합니다.")
    public ResponseEntity<SuccessResponse<Void>> syncAllConstituents() {
        etfService.syncAllConstituents();
        return ResponseEntity.ok(new SuccessResponse<>(true, "모든 국내/해외 ETF 구성종목 동기화 작업이 시작되었습니다.", null));
    }

    @PostMapping("/constituents/sync-us")
    @Operation(summary = "미국 ETF 구성종목 전체 동기화", description = "미국 ETF(US_ETF)의 구성종목을 Yahoo Finance(Python)를 통해 일괄 동기화합니다.")
    public ResponseEntity<SuccessResponse<Void>> syncAllUsConstituents() {
        etfService.syncAllUsEtfConstituents();
        return ResponseEntity.ok(new SuccessResponse<>(true, "모든 미국 ETF 구성종목 동기화 작업이 시작되었습니다.", null));
    }

    @GetMapping("/{stockCode}/constituents")
    @Operation(summary = "ETF 구성 종목 조회", description = "DB에 저장된 ETF의 구성 종목 목록과 비중을 조회합니다.")
    public ResponseEntity<List<EtfConstituentResponseDto>> getEtfConstituents(@PathVariable String stockCode) {
        return ResponseEntity.ok(etfService.getConstituents(stockCode));
    }
}
