package com.AISA.AISA.kisOverseasStock.service;

import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.kisOverseasStock.config.KisOverseasApiProperties;
import com.AISA.AISA.kisOverseasStock.dto.KisOverseasPriceApiResponse;
import com.AISA.AISA.kisOverseasStock.dto.OverseasStockBalanceSheetDto;
import com.AISA.AISA.kisOverseasStock.dto.OverseasStockFinancialStatementDto;
import com.AISA.AISA.kisOverseasStock.dto.OverseasStockPriceDetailDto;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockBalanceSheetRepository;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockFinancialStatementRepository;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockRepository;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.config.KisApiProperties;
import com.AISA.AISA.kisStock.exception.KisApiErrorCode;
import com.AISA.AISA.kisStock.kisService.KisApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class KisOverseasStockInformationService {

        private final KisOverseasStockFinancialStatementRepository financialStatementRepository;
        private final KisOverseasStockBalanceSheetRepository balanceSheetRepository;
        private final KisOverseasStockRepository overseasStockRepository;
        private final WebClient webClient;
        private final KisApiProperties kisApiProperties;
        private final KisOverseasApiProperties overseasApiProperties;
        private final KisApiClient kisApiClient;

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
                                .uri(uriBuilder -> uriBuilder
                                                .path(overseasApiProperties.getOverseaPriceUrl())
                                                .queryParam("AUTH", "")
                                                .queryParam("EXCD", stock.getMarketName().getExchangeCode())
                                                .queryParam("SYMB", stock.getStockCode())
                                                .build())
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
}
