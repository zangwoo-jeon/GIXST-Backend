package com.AISA.AISA.portfolio.macro.controller;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisStock.enums.BondYield;

import com.AISA.AISA.kisStock.kisService.KisMacroService;
import com.AISA.AISA.portfolio.macro.dto.ExchangeRateStatusDto;
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
    @Operation(summary = "원/달러 환율 조회", description = "한국투자증권 API를 통해 원/달러 환율을 조회합니다. (DB 우선, 부족 시 API)")
    public ResponseEntity<SuccessResponse<List<MacroIndicatorDto>>> getExchangeRate(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        List<MacroIndicatorDto> data = kisMacroService.fetchExchangeRate("FX@KRW", startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "환율 조회 성공", data));
    }

    @GetMapping("/exchange-rate/status")
    @Operation(summary = "원/달러 환율 현재가 조회", description = "한국투자증권 API를 통해 실시간 원/달러 환율, 변동액, 등락률을 조회합니다.")
    public ResponseEntity<SuccessResponse<ExchangeRateStatusDto>> getExchangeRateStatus() {
        ExchangeRateStatusDto data = kisMacroService.getExchangeRateStatus();
        return ResponseEntity.ok(new SuccessResponse<>(true, "환율 현재가 조회 성공", data));
    }

    @PostMapping("/exchange-rate/init")
    @Operation(summary = "원/달러 환율 데이터 초기화/업데이트", description = "한국투자증권 API에서 원/달러 환율 데이터를 가져와 DB에 저장합니다. (대량 데이터 적재용)")
    public ResponseEntity<SuccessResponse<Void>> initExchangeRate(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        kisMacroService.fetchAndSaveExchangeRate("FX@KRW", startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "환율 데이터 저장 성공", null));
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

    @GetMapping("/kospi-usd-ratio")
    @Operation(summary = "달러 환산 코스피 지수 조회", description = "코스피 지수를 원/달러 환율로 나누어 달러 기준 가치를 계산합니다. (KOSPI / (환율 / 1000))")
    public ResponseEntity<SuccessResponse<List<MacroIndicatorDto>>> getKospiUsdRatio(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        List<MacroIndicatorDto> ratioList = macroService.getKospiUsdRatio(startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "달러 환산 코스피 조회 성공", ratioList));
    }

    @GetMapping("/kosdaq-usd-ratio")
    @Operation(summary = "달러 환산 코스닥 지수 조회", description = "코스닥 지수를 원/달러 환율로 나누어 달러 기준 가치를 계산합니다. (KOSDAQ / (환율 / 1000))")
    public ResponseEntity<SuccessResponse<List<MacroIndicatorDto>>> getKosdaqUsdRatio(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        List<MacroIndicatorDto> ratioList = macroService.getKosdaqUsdRatio(startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, "달러 환산 코스닥 조회 성공", ratioList));
    }

    @GetMapping("/bond/{bondName}")
    @Operation(summary = "채권 금리 조회", description = "주요 국채 금리(한국, 미국)를 조회합니다. (가능한 bondName: KR_1Y, KR_3Y, KR_10Y, US_1Y, US_10Y, US_30Y)")
    public ResponseEntity<SuccessResponse<List<MacroIndicatorDto>>> getBondYield(
            @PathVariable String bondName,
            @RequestParam String startDate,
            @RequestParam String endDate) {
        BondYield bond = BondYield.valueOf(bondName.toUpperCase());
        List<MacroIndicatorDto> data = kisMacroService.fetchBondYield(bond, startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, bond.getDescription() + " 조회 성공", data));
    }

    @PostMapping("/bond/init")
    @Operation(summary = "채권 금리 데이터 초기화/업데이트", description = "채권 금리 데이터를 KIS API에서 가져와 DB에 저장합니다. (가능한 bondName: KR_1Y, KR_3Y, KR_10Y, US_1Y, US_10Y, US_30Y)")
    public ResponseEntity<SuccessResponse<Void>> initBondYield(
            @RequestParam String bondName,
            @RequestParam String startDate,
            @RequestParam String endDate) {
        BondYield bond = BondYield.valueOf(bondName.toUpperCase());
        kisMacroService.fetchAndSaveBondYield(bond, startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, bond.getDescription() + " 데이터 저장 성공", null));
    }

    @GetMapping("/overseas-index-krw/{indexName}")
    @Operation(summary = "원화 환산 해외 지수 조회", description = "해외 지수(NASDAQ, SP500 등)를 원화 가치로 환산하여 조회합니다. (해외지수 * 환율)")
    public ResponseEntity<SuccessResponse<List<MacroIndicatorDto>>> getOverseasIndexKrw(
            @PathVariable String indexName,
            @RequestParam String startDate,
            @RequestParam String endDate) {
        List<MacroIndicatorDto> data = macroService.getWonConvertedOverseasIndex(indexName, startDate, endDate);
        return ResponseEntity.ok(new SuccessResponse<>(true, indexName + " 원화 환산 조회 성공", data));
    }
}
