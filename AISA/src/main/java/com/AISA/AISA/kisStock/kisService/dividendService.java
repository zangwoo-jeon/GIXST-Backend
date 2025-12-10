package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.config.KisApiProperties;
import com.AISA.AISA.kisStock.dto.Dividend.KisDividendApiResponse;
import com.AISA.AISA.kisStock.dto.Dividend.StockDividendInfoDto;
import com.AISA.AISA.kisStock.exception.KisApiErrorCode;
import com.AISA.AISA.kisStock.kisService.Auth.KisAuthService;
import com.AISA.AISA.kisStock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import com.AISA.AISA.kisStock.Entity.stock.StockDividendRank;
import com.AISA.AISA.kisStock.repository.StockDividendRankRepository;
import com.AISA.AISA.kisStock.dto.DividendRank.DividendRankDto;
import com.AISA.AISA.kisStock.kisService.KisStockService;
import com.AISA.AISA.kisStock.dto.StockPrice.StockPriceDto;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
@Slf4j
public class DividendService {

        private final WebClient webClient;
        private final KisAuthService kisAuthService;
        private final KisApiProperties kisApiProperties;
        private final StockRepository stockRepository;
        private final StockDividendRankRepository stockDividendRankRepository;
        private final KisStockService kisStockService;

        public List<StockDividendInfoDto> getDividendInfo(
                        String stockCode,
                        String startDate,
                        String endDate) {
                String accessToken = kisAuthService.getAccessToken();

                Stock stock = stockRepository.findByStockCode(stockCode)
                                .orElseThrow(() -> new BusinessException(KisApiErrorCode.STOCK_NOT_FOUND));

                KisDividendApiResponse apiResponse = webClient.get()
                                .uri(uriBuilder -> uriBuilder
                                                .path(kisApiProperties.getDividendUrl())
                                                .queryParam("CTS", "")
                                                .queryParam("GB1", "0") // 0:배당전체, 1:결산배당, 2:중간배당
                                                .queryParam("F_DT", startDate)
                                                .queryParam("T_DT", endDate)
                                                .queryParam("SHT_CD", stockCode)
                                                .queryParam("HIGH_GB", "")
                                                .build())
                                .header("authorization", accessToken) // 접근 토큰
                                .header("appKey", kisApiProperties.getAppkey()) // 앱키
                                .header("appSecret", kisApiProperties.getAppsecret()) // 앱시크릿키
                                .header("tr_id", "HHKDB669102C0") // 배당 조회용 tr_id
                                .retrieve()
                                .bodyToMono(KisDividendApiResponse.class)// 서버에서 온 JSON을 DTO 클래스(KisPriceApiResponse)로
                                                                         // 매핑
                                .onErrorMap(error -> {
                                        log.error("{}의 배당 정보을 불러오는 데 실패했습니다. 에러: {}", stockCode, error.getMessage());
                                        return new BusinessException(KisApiErrorCode.DIVIDEND_FETCH_FAILED);
                                })
                                .block(); // 동기식 객체로 변환

                if (apiResponse == null || !"0".equals(apiResponse.getRtCd()) || apiResponse.getOutput1() == null) {
                        log.warn("{}의 배당 정보가 없습니다.", stockCode);
                        return Collections.emptyList();
                }

                return apiResponse.getOutput1().stream()
                                .map(apiDto -> StockDividendInfoDto.builder()
                                                .stockCode(apiDto.getStockCode())
                                                .stockName(apiDto.getStockName())
                                                .recordDate(apiDto.getRecordDate())
                                                .paymentDate(apiDto.getPaymentDate())
                                                .dividendAmount(new BigDecimal(apiDto.getDividendAmount()))
                                                .dividendRate(Double.parseDouble(apiDto.getDividendRate()))
                                                .build())
                                .collect(Collectors.toList());
        }

        public DividendRankDto getDividendRank() {
                List<StockDividendRank> rankList = stockDividendRankRepository.findAll();

                // 1. 배당수익률 내림차순 정렬
                rankList.sort(Comparator.comparing(StockDividendRank::getDividendRate, (s1, s2) -> {
                        Double d1 = Double.parseDouble(s1);
                        Double d2 = Double.parseDouble(s2);
                        return d2.compareTo(d1);
                }));

                // 2. 상위 20개만 추출
                List<StockDividendRank> top20 = rankList.stream()
                                .limit(20)
                                .collect(Collectors.toList());

                // 3. 실시간 현재가 조회 및 DTO 변환
                List<DividendRankDto.DividendRankEntry> entries = top20.stream()
                                .map(entity -> {
                                        String currentPrice = "0";
                                        try {
                                                StockPriceDto priceDto = kisStockService
                                                                .getStockPrice(entity.getStockCode());
                                                if (priceDto != null) {
                                                        currentPrice = priceDto.getStockPrice();
                                                }
                                        } catch (Exception e) {
                                                log.warn("Failed to fetch real-time price for {}: {}",
                                                                entity.getStockCode(), e.getMessage());
                                        }

                                        return DividendRankDto.DividendRankEntry.builder()
                                                        .rank(entity.getRank())
                                                        .stockCode(entity.getStockCode())
                                                        .stockName(entity.getStockName())
                                                        .dividendAmount(entity.getDividendAmount())
                                                        .dividendRate(entity.getDividendRate())
                                                        .currentPrice(currentPrice)
                                                        .build();
                                })
                                .collect(Collectors.toList());

                return DividendRankDto.builder()
                                .ranks(entries)
                                .build();
        }

        @Transactional
        public void refreshDividendRank() {
                log.info("Starting batch dividend rank refresh...");

                // 1. Get all stocks
                List<Stock> allStocks = stockRepository.findAll();
                List<StockDividendRank> newRankList = new ArrayList<>();

                // Date range for 'Last Year' dividend
                String endDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                String startDate = LocalDate.now().minusYears(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));

                int count = 0;

                for (Stock stock : allStocks) {
                        try {
                                // Rate limit
                                TimeUnit.MILLISECONDS.sleep(100);

                                // 2. Get Dividend Info
                                List<StockDividendInfoDto> dividends = getDividendInfo(stock.getStockCode(), startDate,
                                                endDate);

                                if (dividends.isEmpty())
                                        continue;

                                BigDecimal totalDividend = BigDecimal.ZERO;
                                for (StockDividendInfoDto info : dividends) {
                                        totalDividend = totalDividend.add(info.getDividendAmount());
                                }

                                if (totalDividend.compareTo(BigDecimal.ZERO) == 0)
                                        continue;

                                // 3. Get Current Price (Only for calculation)
                                StockPriceDto priceDto = kisStockService.getStockPrice(stock.getStockCode());
                                if (priceDto == null || priceDto.getStockPrice() == null)
                                        continue;

                                BigDecimal currentPrice = new BigDecimal(priceDto.getStockPrice());

                                if (currentPrice.compareTo(BigDecimal.ZERO) == 0)
                                        continue;

                                // 4. Calculate Yield
                                // Yield = (Dividend / Price) * 100
                                double yield = totalDividend.divide(currentPrice, 4, java.math.RoundingMode.HALF_UP)
                                                .multiply(new BigDecimal(100)).doubleValue();

                                // Add to list (without saving currentPrice)
                                StockDividendRank rankEntity = StockDividendRank.builder()
                                                .stockCode(stock.getStockCode())
                                                .stockName(stock.getStockName())
                                                .dividendAmount(totalDividend.toString())
                                                .dividendRate(String.format("%.2f", yield))
                                                .rank("0") // Will assign later
                                                .build();

                                newRankList.add(rankEntity);
                                count++;

                                if (count % 10 == 0) {
                                        log.info("Processed {} stocks...", count);
                                }

                        } catch (Exception e) {
                                log.warn("Failed to process dividend for {}: {}", stock.getStockCode(), e.getMessage());
                        }
                }

                // 5. Sort and Assign Rank
                newRankList.sort((r1, r2) -> {
                        Double d1 = Double.parseDouble(r1.getDividendRate());
                        Double d2 = Double.parseDouble(r2.getDividendRate());
                        return d2.compareTo(d1);
                });

                for (int i = 0; i < newRankList.size(); i++) {
                        StockDividendRank old = newRankList.get(i);
                        newRankList.set(i, StockDividendRank.builder()
                                        .stockCode(old.getStockCode())
                                        .stockName(old.getStockName())
                                        .dividendAmount(old.getDividendAmount())
                                        .dividendRate(old.getDividendRate())
                                        .rank(String.valueOf(i + 1))
                                        .build());
                }

                // 6. Save to DB
                stockDividendRankRepository.deleteAll();
                stockDividendRankRepository.saveAll(newRankList);
                log.info("Refreshed Dividend Rank with {} stocks.", newRankList.size());
        }
}
