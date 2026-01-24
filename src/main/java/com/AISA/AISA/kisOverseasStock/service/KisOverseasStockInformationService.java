package com.AISA.AISA.kisOverseasStock.service;

import com.AISA.AISA.global.errorcode.CommonErrorCode;
import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.kisOverseasStock.config.KisOverseasApiProperties;
import com.AISA.AISA.kisOverseasStock.dto.KisOverseasPriceApiResponse;
import com.AISA.AISA.kisOverseasStock.dto.OverseasStockBalanceSheetDto;
import com.AISA.AISA.kisOverseasStock.dto.OverseasStockFinancialStatementDto;
import com.AISA.AISA.kisOverseasStock.dto.OverseasStockPriceDetailDto;
import com.AISA.AISA.kisOverseasStock.entity.OverseasStockBalanceSheet;
import com.AISA.AISA.kisOverseasStock.entity.OverseasStockFinancialRatio;
import com.AISA.AISA.kisOverseasStock.entity.OverseasStockFinancialStatement;
import com.AISA.AISA.kisOverseasStock.dto.OverseasFinancialRatioDto;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockBalanceSheetRepository;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockFinancialRatioRepository;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockFinancialStatementRepository;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockRepository;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.config.KisApiProperties;
import com.AISA.AISA.kisStock.exception.KisApiErrorCode;
import com.AISA.AISA.kisStock.kisService.KisApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class KisOverseasStockInformationService {

        private final KisOverseasStockFinancialStatementRepository financialStatementRepository;
        private final KisOverseasStockBalanceSheetRepository balanceSheetRepository;
        private final KisOverseasStockFinancialRatioRepository financialRatioRepository;
        private final KisOverseasStockRepository overseasStockRepository;
        private final WebClient webClient;
        private final KisApiProperties kisApiProperties;
        private final KisOverseasApiProperties overseasApiProperties;
        private final KisApiClient kisApiClient;

        @Autowired
        @Lazy
        private KisOverseasStockInformationService self;

        private static final BigDecimal UNIT_MULTIPLIER = new BigDecimal("1000000"); // Million USD

        /**
         * 특정 종목의 손익계산서 데이터를 조회합니다.
         */
        public List<OverseasStockFinancialStatementDto> getFinancialStatements(String stockCode, String divCode) {
                return financialStatementRepository.findByStockCodeAndDivCodeOrderByStacYymmAsc(stockCode, divCode)
                                .stream()
                                .map(fs -> OverseasStockFinancialStatementDto.builder()
                                                .stacYymm(fs.getStacYymm())
                                                .totalRevenue(fs.getTotalRevenue())
                                                .operatingIncome(fs.getOperatingIncome())
                                                .netIncome(fs.getNetIncome())
                                                .build())
                                .collect(Collectors.toList());
        }

        /**
         * 특정 종목의 재무상태표 데이터를 조회합니다.
         */
        public List<OverseasStockBalanceSheetDto> getBalanceSheets(String stockCode) {
                return balanceSheetRepository.findAllByStockCode(stockCode)
                                .stream()
                                .map(bs -> OverseasStockBalanceSheetDto.builder()
                                                .stacYymm(bs.getStacYymm())
                                                .totalAssets(bs.getTotalAssets())
                                                .totalLiabilities(bs.getTotalLiabilities())
                                                .totalCapital(bs.getTotalCapital())
                                                .build())
                                .collect(Collectors.toList());
        }

        /**
         * 특정 종목의 실시간 상세 가격 정보(시가총액, 상장주수)를 조회합니다.
         */
        public OverseasStockPriceDetailDto getPriceDetail(String stockCode) {
                Stock stock = overseasStockRepository.findByStockCodeAndStockType(stockCode, Stock.StockType.US_STOCK)
                                .orElseThrow(() -> new BusinessException(KisApiErrorCode.STOCK_NOT_FOUND));

                KisOverseasPriceApiResponse response = kisApiClient.fetch(token -> webClient.get()
                                .uri(uriBuilder -> {
                                        String url = overseasApiProperties.getOverseaPriceUrl();
                                        if (url == null)
                                                throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
                                        return uriBuilder
                                                        .path(url)
                                                        .queryParam("AUTH", "")
                                                        .queryParam("EXCD", stock.getMarketName().getExchangeCode())
                                                        .queryParam("SYMB", stock.getStockCode())
                                                        .build();
                                })
                                .header("Authorization", token)
                                .header("appKey", kisApiProperties.getAppkey())
                                .header("appSecret", kisApiProperties.getAppsecret())
                                .header("tr_id", "HHDFS76200200"), KisOverseasPriceApiResponse.class);

                KisOverseasPriceApiResponse.KisOverseasPriceOutput output = response.getOutput();

                // 원화 환산 시가총액 계산
                String marketCapKrw = "0";
                try {
                        if (output.getMarketCap() != null && output.getExchangeRate() != null) {
                                double mktCapUsd = Double.parseDouble(output.getMarketCap());
                                double rate = Double.parseDouble(output.getExchangeRate());
                                marketCapKrw = String.format("%.0f", mktCapUsd * rate);
                        }
                } catch (NumberFormatException e) {
                        log.warn("Failed to calculate KRW market cap for {}: {}", stockCode, e.getMessage());
                }

                return OverseasStockPriceDetailDto.builder()
                                .stockCode(stock.getStockCode())
                                .marketCap(output.getMarketCap())
                                .marketCapKrw(marketCapKrw)
                                .listedShares(output.getListedShares())
                                .build();
        }

        /**
         * 모든 해외 종목의 투자 지표를 일괄적으로 갱신합니다. (배치용)
         */
        public void refreshAllFinancialRatios(String divCode) {
                List<Stock> stocks = overseasStockRepository.findAllByStockType(Stock.StockType.US_STOCK);
                log.info("Starting Batch Financial Ratio Refresh for {} stocks (divCode={})", stocks.size(), divCode);

                for (Stock stock : stocks) {
                        try {
                                self.calculateAndSaveFinancialRatios(stock.getStockCode(), divCode);
                                Thread.sleep(100); // KIS API 과부하 방지 (0.1초 대기)
                        } catch (Exception e) {
                                log.error("Failed to refresh financial ratio for {}: {}", stock.getStockCode(),
                                                e.getMessage());
                        }
                }
                log.info("Completed Batch Financial Ratio Refresh");
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public List<OverseasStockFinancialRatio> calculateAndSaveFinancialRatios(String stockCode, String divCode) {
                // 1. 필요한 데이터 조회
                List<OverseasStockFinancialStatement> statements = financialStatementRepository
                                .findByStockCodeAndDivCodeOrderByStacYymmAsc(stockCode, divCode);
                List<OverseasStockBalanceSheet> balanceSheets = balanceSheetRepository
                                .findByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, divCode);

                if (statements.isEmpty()) {
                        log.warn("No financial statements found for stock: {}", stockCode);
                        return new ArrayList<>();
                }

                // 2. 현재가 및 환율 정보 조회 (PER, PBR 등 계산용)
                Stock stock = overseasStockRepository.findByStockCodeAndStockType(stockCode, Stock.StockType.US_STOCK)
                                .orElseThrow(() -> new BusinessException(KisApiErrorCode.STOCK_NOT_FOUND));

                KisOverseasPriceApiResponse priceResponse = kisApiClient.fetch(token -> webClient.get()
                                .uri(uriBuilder -> {
                                        String url = overseasApiProperties.getOverseaPriceUrl();
                                        if (url == null)
                                                throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
                                        return uriBuilder
                                                        .path(url)
                                                        .queryParam("AUTH", "")
                                                        .queryParam("EXCD", stock.getMarketName().getExchangeCode())
                                                        .queryParam("SYMB", stock.getStockCode())
                                                        .build();
                                })
                                .header("Authorization", token)
                                .header("appKey", kisApiProperties.getAppkey())
                                .header("appSecret", kisApiProperties.getAppsecret())
                                .header("tr_id", "HHDFS76200200"), KisOverseasPriceApiResponse.class);

                KisOverseasPriceApiResponse.KisOverseasPriceOutput output = priceResponse.getOutput();
                BigDecimal currentPrice = new BigDecimal(output.getPrice());
                BigDecimal exchangeRate = new BigDecimal(output.getExchangeRate());
                BigDecimal listedShares = new BigDecimal(output.getListedShares());

                List<OverseasStockFinancialRatio> ratiosToSave = new ArrayList<>();
                String latestYymm = statements.get(statements.size() - 1).getStacYymm();

                for (OverseasStockFinancialStatement stmt : statements) {
                        String yymm = stmt.getStacYymm();

                        // 2022년 이전 데이터는 계산 및 저장에서 제외
                        if (Integer.parseInt(yymm.substring(0, 4)) < 2022) {
                                continue;
                        }
                        OverseasStockBalanceSheet board = balanceSheets.stream()
                                        .filter(bs -> bs.getStacYymm().equals(yymm))
                                        .findFirst().orElse(null);

                        BigDecimal epsUsd = BigDecimal.ZERO;
                        BigDecimal bpsUsd = BigDecimal.ZERO;
                        BigDecimal per = BigDecimal.ZERO;
                        BigDecimal pbr = BigDecimal.ZERO;
                        BigDecimal roe = BigDecimal.ZERO;

                        if (yymm.equals(latestYymm)) {
                                // 최신 데이터는 한투 API에서 준 값 우선 사용
                                epsUsd = parseBigDecimalSafe(output.getEps());
                                bpsUsd = parseBigDecimalSafe(output.getBps());
                                per = parseBigDecimalSafe(output.getPer());
                                pbr = parseBigDecimalSafe(output.getPbr());

                                // 만약 한투에서 준 값이 0이면 로컬 계산으로 보완
                                if (epsUsd.compareTo(BigDecimal.ZERO) == 0 && stmt.getNetIncome() != null) {
                                        epsUsd = stmt.getNetIncome().multiply(UNIT_MULTIPLIER)
                                                        .divide(listedShares, 4, RoundingMode.HALF_UP);
                                }
                                if (bpsUsd.compareTo(BigDecimal.ZERO) == 0 && board != null
                                                && board.getTotalCapital() != null) {
                                        bpsUsd = board.getTotalCapital().multiply(UNIT_MULTIPLIER)
                                                        .divide(listedShares, 4, RoundingMode.HALF_UP);
                                }
                                if (per.compareTo(BigDecimal.ZERO) == 0 && epsUsd.compareTo(BigDecimal.ZERO) != 0) {
                                        per = currentPrice.divide(epsUsd, 2, RoundingMode.HALF_UP);
                                }
                                if (pbr.compareTo(BigDecimal.ZERO) == 0 && bpsUsd.compareTo(BigDecimal.ZERO) != 0) {
                                        pbr = currentPrice.divide(bpsUsd, 2, RoundingMode.HALF_UP);
                                }
                        } else {
                                // 과거 데이터는 로컬 계산
                                if (listedShares.compareTo(BigDecimal.ZERO) > 0 && stmt.getNetIncome() != null) {
                                        epsUsd = stmt.getNetIncome().multiply(UNIT_MULTIPLIER)
                                                        .divide(listedShares, 4, RoundingMode.HALF_UP);
                                }
                                if (board != null && board.getTotalCapital() != null
                                                && listedShares.compareTo(BigDecimal.ZERO) > 0) {
                                        bpsUsd = board.getTotalCapital().multiply(UNIT_MULTIPLIER)
                                                        .divide(listedShares, 4, RoundingMode.HALF_UP);
                                }

                                if (epsUsd.compareTo(BigDecimal.ZERO) != 0) {
                                        per = currentPrice.divide(epsUsd, 2, RoundingMode.HALF_UP);
                                }
                                if (bpsUsd.compareTo(BigDecimal.ZERO) != 0) {
                                        pbr = currentPrice.divide(bpsUsd, 2, RoundingMode.HALF_UP);
                                }
                        }

                        // 공통: 원화 환산 EPS, BPS
                        BigDecimal epsKrw = epsUsd.multiply(exchangeRate).setScale(2, RoundingMode.HALF_UP);
                        BigDecimal bpsKrw = bpsUsd.multiply(exchangeRate).setScale(2, RoundingMode.HALF_UP);

                        // 공통: ROE 계산 (Net Income / Total Capital * 100)
                        if (board != null && board.getTotalCapital() != null
                                        && board.getTotalCapital().compareTo(BigDecimal.ZERO) > 0) {
                                roe = stmt.getNetIncome().divide(board.getTotalCapital(), 4, RoundingMode.HALF_UP)
                                                .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
                        }

                        // 공통: PSR 계산 (Price / (Revenue / Shares))
                        BigDecimal psr = BigDecimal.ZERO;
                        if (stmt.getTotalRevenue() != null && listedShares.compareTo(BigDecimal.ZERO) > 0) {
                                BigDecimal sps = stmt.getTotalRevenue().multiply(UNIT_MULTIPLIER)
                                                .divide(listedShares, 4, RoundingMode.HALF_UP);
                                if (sps.compareTo(BigDecimal.ZERO) != 0) {
                                        psr = currentPrice.divide(sps, 2, RoundingMode.HALF_UP);
                                }
                        }

                        // Upsert 로직
                        OverseasStockFinancialRatio existing = financialRatioRepository
                                        .findByStockCodeAndStacYymmAndDivCode(stockCode, yymm, divCode)
                                        .orElse(null);

                        OverseasStockFinancialRatio.OverseasStockFinancialRatioBuilder builder = (existing != null)
                                        ? existing.toBuilder()
                                        : OverseasStockFinancialRatio.builder()
                                                        .stockCode(stockCode)
                                                        .stacYymm(yymm)
                                                        .divCode(divCode);

                        builder.per(per)
                                        .pbr(pbr)
                                        .psr(psr)
                                        .roe(roe)
                                        .epsUsd(epsUsd)
                                        .epsKrw(epsKrw)
                                        .bpsUsd(bpsUsd)
                                        .bpsKrw(bpsKrw)
                                        .isSuspended(false);

                        ratiosToSave.add(builder.build());
                }

                return financialRatioRepository.saveAll(ratiosToSave);
        }

        /**
         * 특정 종목의 실시간 투자 지표를 조회합니다. (하이브리드 방식)
         * - PER, PBR: 한투 API 실시간 데이터
         * - PSR: (현재가 / (최신 매출액 / 상장주식수))로 실시간 계산
         * - EPS, BPS, ROE: DB의 최신 데이터 활용
         */
        /**
         * 특정 종목의 최신 투자 지표를 DB에서 조회합니다. (실시간 API 미사용)
         */
        public OverseasFinancialRatioDto getRealTimeFinancialRatio(String stockCode) {
                // 1. DB에서 가장 최신의 재무 데이터 조회 (Annual '0' 기준 우선)
                List<OverseasStockFinancialRatio> savedRatios = financialRatioRepository
                                .findByStockCodeAndDivCodeOrderByStacYymmAsc(stockCode, "0");
                if (savedRatios.isEmpty()) {
                        // 연간 데이터가 없으면 분기 데이터라도 조회
                        savedRatios = financialRatioRepository.findByStockCodeAndDivCodeOrderByStacYymmAsc(stockCode,
                                        "1");
                }

                OverseasStockFinancialRatio latest = savedRatios.isEmpty() ? null
                                : savedRatios.get(savedRatios.size() - 1);

                if (latest == null) {
                        return OverseasFinancialRatioDto.builder()
                                        .stockCode(stockCode)
                                        .stacYymm("N/A")
                                        .divCode("0")
                                        .per(BigDecimal.ZERO)
                                        .pbr(BigDecimal.ZERO)
                                        .psr(BigDecimal.ZERO)
                                        .roe(BigDecimal.ZERO)
                                        .epsUsd(BigDecimal.ZERO)
                                        .epsKrw(BigDecimal.ZERO)
                                        .bpsUsd(BigDecimal.ZERO)
                                        .bpsKrw(BigDecimal.ZERO)
                                        .build();
                }

                return OverseasFinancialRatioDto.builder()
                                .stockCode(latest.getStockCode())
                                .stacYymm(latest.getStacYymm())
                                .divCode(latest.getDivCode())
                                .per(latest.getPer())
                                .pbr(latest.getPbr())
                                .psr(latest.getPsr())
                                .roe(latest.getRoe())
                                .epsUsd(latest.getEpsUsd())
                                .epsKrw(latest.getEpsKrw())
                                .bpsUsd(latest.getBpsUsd())
                                .bpsKrw(latest.getBpsKrw())
                                .build();
        }

        /**
         * 특정 종목의 모든 저장된 재무 지표를 조회합니다. (AI 리포트용)
         */
        public List<OverseasFinancialRatioDto> getFinancialRatios(String stockCode, String divCode) {
                return financialRatioRepository.findByStockCodeAndDivCodeOrderByStacYymmAsc(stockCode, divCode)
                                .stream()
                                .map(r -> OverseasFinancialRatioDto.builder()
                                                .stockCode(r.getStockCode())
                                                .stacYymm(r.getStacYymm())
                                                .divCode(r.getDivCode())
                                                .per(r.getPer())
                                                .pbr(r.getPbr())
                                                .psr(r.getPsr())
                                                .roe(r.getRoe())
                                                .epsUsd(r.getEpsUsd())
                                                .epsKrw(r.getEpsKrw())
                                                .bpsUsd(r.getBpsUsd())
                                                .bpsKrw(r.getBpsKrw())
                                                .build())
                                .collect(Collectors.toList());
        }

        private BigDecimal parseBigDecimalSafe(String value) {
                if (value == null || value.trim().isEmpty() || "-".equals(value.trim())) {
                        return BigDecimal.ZERO;
                }
                try {
                        return new BigDecimal(value.trim());
                } catch (NumberFormatException e) {
                        return BigDecimal.ZERO;
                }
        }
}
