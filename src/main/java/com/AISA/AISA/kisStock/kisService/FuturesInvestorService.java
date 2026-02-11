package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.kisStock.Entity.stock.FuturesInvestorDaily;
import com.AISA.AISA.kisStock.config.KisApiProperties;
import com.AISA.AISA.kisStock.dto.Index.KisFuturesInvestorApiResponse;
import com.AISA.AISA.kisStock.dto.Index.KisFuturesInvestorDto;
import com.AISA.AISA.kisStock.enums.FuturesMarketType;
import com.AISA.AISA.kisStock.repository.FuturesInvestorDailyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
@Slf4j
public class FuturesInvestorService {

    private final WebClient webClient;
    private final KisApiProperties kisApiProperties;
    private final KisApiClient kisApiClient;
    private final FuturesInvestorDailyRepository repository;

    @Transactional
    @Scheduled(cron = "0 30-59/5 8 * * MON-FRI", zone = "Asia/Seoul")
    @Scheduled(cron = "0 0/5 9-15 * * MON-FRI", zone = "Asia/Seoul")
    @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Seoul")
    public void refreshFuturesInvestorTrend() {
        // KOSPI 200 선물
        getTodayTrend(FuturesMarketType.KOSPI200);
        // KOSDAQ 150 선물
        getTodayTrend(FuturesMarketType.KOSDAQ150);
    }

    @Transactional
    public FuturesInvestorDaily getTodayTrend(FuturesMarketType marketType) {
        String iscd = switch (marketType) {
            case KOSPI200 -> "K2I";
            case KOSDAQ150 -> "KQI";
        };
        String iscd2 = switch (marketType) {
            case KOSPI200 -> "F001";
            case KOSDAQ150 -> "F002";
        };
        return fetchAndSave(marketType, iscd, iscd2);
    }

    private FuturesInvestorDaily fetchAndSave(FuturesMarketType marketType, String iscd, String iscd2) {
        log.info("Fetching futures investor trend for {} (iscd={}, iscd2={})", marketType, iscd, iscd2);
        log.info("Using URL: {}", kisApiProperties.getFuturesInvestorTrendUrl());

        String url = kisApiProperties.getFuturesInvestorTrendUrl();
        if (url == null) {
            log.error("futuresInvestorTrendUrl is not configured in application.yml");
            return null;
        }

        try {
            KisFuturesInvestorApiResponse response = kisApiClient.fetch(token -> {
                String authorizationHeader = token.startsWith("Bearer ") ? token : "Bearer " + token;
                return webClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path(url)
                                .queryParam("fid_input_iscd", iscd)
                                .queryParam("fid_input_iscd_2", iscd2)
                                .build())
                        .header("authorization", authorizationHeader)
                        .header("appkey", kisApiProperties.getAppkey())
                        .header("appsecret", kisApiProperties.getAppsecret())
                        .header("tr_id", "FHPTJ04030000")
                        .header("custtype", "P");
            }, KisFuturesInvestorApiResponse.class);

            if (response == null) {
                log.error("KIS API Response is null for {}", marketType);
                return null;
            }

            log.info("KIS API Raw Response: rt_cd={}, msg_cd={}, msg1={}",
                    response.getRtCd(), response.getMsgCd(), response.getMsg1());

            if ("0".equals(response.getRtCd()) && response.getOutput() != null && !response.getOutput().isEmpty()) {
                // Get the first item (today's latest or total)
                KisFuturesInvestorDto data = response.getOutput().get(0);
                log.info("Successfully fetched data: personal={}, foreign={}, org={}",
                        data.getPrsnNtbyTrPbmn(), data.getFrgnNtbyTrPbmn(), data.getOrgnNtbyTrPbmn());

                LocalDate today = LocalDate.now();

                FuturesInvestorDaily entity = repository.findByDateAndMarketType(today, marketType)
                        .orElse(FuturesInvestorDaily.builder()
                                .date(today)
                                .marketType(marketType)
                                .build());

                entity.setPersonalNetBuyAmount(new BigDecimal(data.getPrsnNtbyTrPbmn()));
                entity.setForeignerNetBuyAmount(new BigDecimal(data.getFrgnNtbyTrPbmn()));
                entity.setInstitutionNetBuyAmount(new BigDecimal(data.getOrgnNtbyTrPbmn()));

                repository.save(entity);
                log.info("Successfully updated futures investor trend for {} on {}", marketType, today);
                return entity;
            } else {
                log.warn("Failed to fetch or output is null/empty for {}: rt_cd={}, msg={}, output={}",
                        marketType, response.getRtCd(), response.getMsg1(), response.getOutput());
            }
        } catch (Exception e) {
            log.error("Error fetching futures investor trend for {}: {}", marketType, e.getMessage(), e);
        }
        return null;
    }
}
