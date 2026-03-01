package com.AISA.AISA.portfolio.macro.controller;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisStock.enums.BondYield;
import com.AISA.AISA.kisStock.enums.ExchangeRateCode;

import com.AISA.AISA.kisStock.kisService.KisMacroService;
import com.AISA.AISA.portfolio.macro.dto.ExchangeRateStatusDto;
import com.AISA.AISA.kisStock.dto.Index.IndexChartPriceDto;
import com.AISA.AISA.portfolio.macro.dto.MacroIndicatorDto;
import com.AISA.AISA.portfolio.macro.service.EcosService;
import com.AISA.AISA.portfolio.macro.service.MacroService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/macro")
@RequiredArgsConstructor
@Tag(name = "거시경제 지표 API", description = "환율, 통화량 등 거시경제 데이터 조회")
public class MacroController {

    private final EcosService ecosService;
    private final MacroService macroService;
    private final KisMacroService kisMacroService;

    @GetMapping("/exchange-rate")
    @Operation(summary = "원/달러 환율 조회", description = "한국은행 ECOS API 기반 원/달러 환율을 조회합니다.")
    public ResponseEntity<SuccessResponse<List<MacroIndicatorDto>>> getExchangeRate(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        List<MacroIndicatorDto> data = kisMacroService.fetchExchangeRate("USD", startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "환율 조회 성공", data));
    }

    @GetMapping("/exchange-rate/status")
    @Operation(summary = "원/달러 환율 현재가 조회", description = "DB에 저장된 최신 원/달러 환율 및 전일 대비 변동 정보를 조회합니다.")
    public ResponseEntity<SuccessResponse<ExchangeRateStatusDto>> getExchangeRateStatus() {
        ExchangeRateStatusDto data = kisMacroService.getExchangeRateStatus();
        return ResponseEntity.ok(new SuccessResponse<>(true, "환율 현재가 조회 성공", data));
    }

    @PostMapping("/exchange-rate/init")
    @Operation(summary = "원/달러 환율 데이터 초기화/업데이트", description = "한국은행 ECOS API에서 원/달러 환율 데이터를 가져와 DB에 저장합니다.")
    public ResponseEntity<SuccessResponse<Void>> initExchangeRate(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        kisMacroService.fetchAndSaveExchangeRate("USD", startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "환율 데이터 저장 성공", null));
    }

    @GetMapping("/exchange-rate/jpy")
    @Operation(summary = "원/엔(100엔) 환율 조회", description = "한국은행 ECOS API 기반 원/엔(100엔) 환율을 조회합니다.")
    public ResponseEntity<SuccessResponse<List<MacroIndicatorDto>>> getJpyExchangeRate(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        List<MacroIndicatorDto> data = kisMacroService.fetchExchangeRate("JPY", startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "원/엔 환율 조회 성공", data));
    }

    @GetMapping("/exchange-rate/jpy/status")
    @Operation(summary = "원/엔(100엔) 환율 현재가 조회", description = "DB에 저장된 최신 원/엔(100엔) 환율 및 전일 대비 변동 정보를 조회합니다.")
    public ResponseEntity<SuccessResponse<ExchangeRateStatusDto>> getJpyExchangeRateStatus() {
        ExchangeRateStatusDto data = kisMacroService.getExchangeRateStatus(ExchangeRateCode.JPY);
        return ResponseEntity.ok(new SuccessResponse<>(true, "원/엔 환율 현재가 조회 성공", data));
    }

    @PostMapping("/exchange-rate/jpy/init")
    @Operation(summary = "원/엔(100엔) 환율 데이터 초기화/업데이트", description = "한국은행 ECOS API에서 원/엔(100엔) 환율 데이터를 가져와 DB에 저장합니다.")
    public ResponseEntity<SuccessResponse<Void>> initJpyExchangeRate(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        kisMacroService.fetchAndSaveExchangeRate("JPY", startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "원/엔 환율 데이터 저장 성공", null));
    }

    @GetMapping("/exchange-rate/eur")
    @Operation(summary = "원/유로 환율 조회", description = "한국은행 ECOS API 기반 원/유로 환율을 조회합니다.")
    public ResponseEntity<SuccessResponse<List<MacroIndicatorDto>>> getEurExchangeRate(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        List<MacroIndicatorDto> data = kisMacroService.fetchExchangeRate("EUR", startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "원/유로 환율 조회 성공", data));
    }

    @GetMapping("/exchange-rate/eur/status")
    @Operation(summary = "원/유로 환율 현재가 조회", description = "DB에 저장된 최신 원/유로 환율 및 전일 대비 변동 정보를 조회합니다.")
    public ResponseEntity<SuccessResponse<ExchangeRateStatusDto>> getEurExchangeRateStatus() {
        ExchangeRateStatusDto data = kisMacroService.getExchangeRateStatus(ExchangeRateCode.EUR);
        return ResponseEntity.ok(new SuccessResponse<>(true, "원/유로 환율 현재가 조회 성공", data));
    }

    @PostMapping("/exchange-rate/eur/init")
    @Operation(summary = "원/유로 환율 데이터 초기화/업데이트", description = "한국은행 ECOS API에서 원/유로 환율 데이터를 가져와 DB에 저장합니다.")
    public ResponseEntity<SuccessResponse<Void>> initEurExchangeRate(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        kisMacroService.fetchAndSaveExchangeRate("EUR", startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "원/유로 환율 데이터 저장 성공", null));
    }

    @GetMapping("/exchange-rate/hkd")
    @Operation(summary = "원/홍콩달러 환율 조회", description = "한국은행 ECOS API 기반 원/홍콩달러 환율을 조회합니다.")
    public ResponseEntity<SuccessResponse<List<MacroIndicatorDto>>> getHkdExchangeRate(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        List<MacroIndicatorDto> data = kisMacroService.fetchExchangeRate("HKD", startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "원/홍콩달러 환율 조회 성공", data));
    }

    @GetMapping("/exchange-rate/hkd/status")
    @Operation(summary = "원/홍콩달러 환율 현재가 조회", description = "DB에 저장된 최신 원/홍콩달러 환율 및 전일 대비 변동 정보를 조회합니다.")
    public ResponseEntity<SuccessResponse<ExchangeRateStatusDto>> getHkdExchangeRateStatus() {
        ExchangeRateStatusDto data = kisMacroService.getExchangeRateStatus(ExchangeRateCode.HKD);
        return ResponseEntity.ok(new SuccessResponse<>(true, "원/홍콩달러 환율 현재가 조회 성공", data));
    }

    @PostMapping("/exchange-rate/hkd/init")
    @Operation(summary = "원/홍콩달러 환율 데이터 초기화/업데이트", description = "한국은행 ECOS API에서 원/홍콩달러 환율 데이터를 가져와 DB에 저장합니다.")
    public ResponseEntity<SuccessResponse<Void>> initHkdExchangeRate(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        kisMacroService.fetchAndSaveExchangeRate("HKD", startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "원/홍콩달러 환율 데이터 저장 성공", null));
    }

    @GetMapping("/exchange-rate/cny")
    @Operation(summary = "원/위안 환율 조회", description = "한국은행 ECOS API 기반 원/위안 환율을 조회합니다.")
    public ResponseEntity<SuccessResponse<List<MacroIndicatorDto>>> getCnyExchangeRate(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        List<MacroIndicatorDto> data = kisMacroService.fetchExchangeRate("CNY", startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "원/위안 환율 조회 성공", data));
    }

    @GetMapping("/exchange-rate/cny/status")
    @Operation(summary = "원/위안 환율 현재가 조회", description = "DB에 저장된 최신 원/위안 환율 및 전일 대비 변동 정보를 조회합니다.")
    public ResponseEntity<SuccessResponse<ExchangeRateStatusDto>> getCnyExchangeRateStatus() {
        ExchangeRateStatusDto data = kisMacroService.getExchangeRateStatus(ExchangeRateCode.CNY);
        return ResponseEntity.ok(new SuccessResponse<>(true, "원/위안 환율 현재가 조회 성공", data));
    }

    @PostMapping("/exchange-rate/cny/init")
    @Operation(summary = "원/위안 환율 데이터 초기화/업데이트", description = "한국은행 ECOS API에서 원/위안 환율 데이터를 가져와 DB에 저장합니다.")
    public ResponseEntity<SuccessResponse<Void>> initCnyExchangeRate(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        kisMacroService.fetchAndSaveExchangeRate("CNY", startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "원/위안 환율 데이터 저장 성공", null));
    }

    @GetMapping("/m2")
    @Operation(summary = "M2 통화량 조회", description = "DB에 저장된 M2(광의통화)를 조회합니다. (데이터가 없으면 POST /init을 호출하세요)")
    public ResponseEntity<SuccessResponse<List<MacroIndicatorDto>>> getM2MoneySupply(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        List<MacroIndicatorDto> data = ecosService.fetchM2MoneySupply(startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "M2 통화량 조회 성공", data));
    }

    @PostMapping("/m2/init")
    @Operation(summary = "M2 통화량 데이터 초기화/업데이트", description = "한국은행 ECOS API에서 M2 데이터를 가져와 DB에 저장합니다.")
    public ResponseEntity<SuccessResponse<Void>> initM2MoneySupply(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        ecosService.saveM2Data(startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "M2 데이터 저장 성공", null));
    }

    @GetMapping("/base-rate")
    @Operation(summary = "기준금리 조회", description = "DB에 저장된 한국은행 기준금리를 조회합니다. (데이터가 없으면 POST /init을 호출하세요)")
    public ResponseEntity<SuccessResponse<List<MacroIndicatorDto>>> getBaseRate(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        List<MacroIndicatorDto> data = ecosService.fetchBaseRate(startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "기준금리 조회 성공", data));
    }

    @PostMapping("/base-rate/init")
    @Operation(summary = "기준금리 데이터 초기화/업데이트", description = "한국은행 ECOS API에서 기준금리 데이터를 가져와 DB에 저장합니다.")
    public ResponseEntity<SuccessResponse<Void>> initBaseRate(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        ecosService.saveBaseRate(startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "기준금리 데이터 저장 성공", null));
    }

    @GetMapping("/cpi")
    @Operation(summary = "소비자물가지수(CPI) 조회", description = "DB에 저장된 소비자물가지수(총지수)를 조회합니다.")
    public ResponseEntity<SuccessResponse<List<MacroIndicatorDto>>> getCPI(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        List<MacroIndicatorDto> data = ecosService.fetchCPI(startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "CPI 조회 성공", data));
    }

    @PostMapping("/cpi/init")
    @Operation(summary = "CPI 데이터 초기화/업데이트", description = "한국은행 ECOS API에서 소비자물가지수 데이터를 가져와 DB에 저장합니다.")
    public ResponseEntity<SuccessResponse<Void>> initCPI(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        ecosService.saveCPI(startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "CPI 데이터 저장 성공", null));
    }

    // Endpoints moved to KisIndexController

    @GetMapping("/bond/{bondName}")
    @Operation(summary = "채권 금리 조회", description = "주요 채권 금리(한국, 미국)를 조회합니다. (가능한 bondName: KR_1Y, KR_3Y, KR_10Y, KR_CORP_3Y, US_1Y, US_10Y, US_30Y)")
    public ResponseEntity<SuccessResponse<List<MacroIndicatorDto>>> getBondYield(
            @PathVariable String bondName,
            @RequestParam String startDate,
            @RequestParam String endDate) {
        BondYield bond = BondYield.valueOf(bondName.toUpperCase());
        List<MacroIndicatorDto> data = kisMacroService.fetchBondYield(bond, startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, bond.getDescription() + " 조회 성공", data));
    }

    @PostMapping("/bond/init")
    @Operation(summary = "채권 금리 데이터 초기화/업데이트", description = "채권 금리 데이터를 DB에 저장합니다. 한국 채권(KR_*)은 한국은행 ECOS API, 미국 채권(US_*)은 KIS API를 사용합니다. (가능한 bondName: KR_1Y, KR_3Y, KR_10Y, KR_CORP_3Y, US_1Y, US_10Y, US_30Y)")
    public ResponseEntity<SuccessResponse<Void>> initBondYield(
            @RequestParam String bondName,
            @RequestParam String startDate,
            @RequestParam String endDate) {
        BondYield bond = BondYield.valueOf(bondName.toUpperCase());
        kisMacroService.fetchAndSaveBondYield(bond, startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, bond.getDescription() + " 데이터 저장 성공", null));
    }

}
