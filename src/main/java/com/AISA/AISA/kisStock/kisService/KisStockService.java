package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.global.exception.BusinessException;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockDailyData;
import com.AISA.AISA.kisStock.Entity.stock.StockMarketCap;
import com.AISA.AISA.kisStock.config.KisApiProperties;
import com.AISA.AISA.kisStock.dto.StockSearchResponseDto;
import com.AISA.AISA.kisStock.dto.VolumeRank.KisVolumeRankApiResponse;
import com.AISA.AISA.kisStock.dto.VolumeRank.VolumeRankDto;
import com.AISA.AISA.kisStock.dto.StockPrice.KisPriceApiResponse;
import com.AISA.AISA.kisStock.dto.StockPrice.StockChartPriceDto;
import com.AISA.AISA.kisStock.dto.StockPrice.StockChartResponseDto;
import com.AISA.AISA.kisStock.dto.StockPrice.StockPriceDto;
import com.AISA.AISA.kisStock.dto.StockPrice.StockPriceResponse;
import com.AISA.AISA.kisStock.exception.KisApiErrorCode;
import com.AISA.AISA.kisStock.kisService.Auth.KisAuthService;
import com.AISA.AISA.kisStock.dto.InvestorTrend.InvestorTrendDto;
import com.AISA.AISA.kisStock.dto.InvestorTrend.StockInvestorDailyDto; // New DTO
import com.AISA.AISA.kisStock.dto.InvestorTrend.KisInvestorDailyResponse; // New DTO
import com.AISA.AISA.kisStock.Entity.stock.StockInvestorDaily;
import com.AISA.AISA.kisStock.repository.StockDailyDataRepository;
import com.AISA.AISA.kisStock.repository.StockMarketCapRepository;
import com.AISA.AISA.kisStock.repository.StockRepository;
import com.AISA.AISA.kisStock.repository.StockInvestorDailyRepository; // New Repo
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.data.domain.Pageable;
import com.AISA.AISA.global.util.OffsetBasedPageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.time.temporal.WeekFields;

import com.fasterxml.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class KisStockService {

        private final WebClient webClient;
        private final KisApiProperties kisApiProperties;
        private final KisAuthService kisAuthService;
        private final StockRepository stockRepository;
        private final StockDailyDataRepository stockDailyDataRepository;
        private final StockMarketCapRepository stockMarketCapRepository; // Added repo
        private final StockInvestorDailyRepository stockInvestorDailyRepository; // New Repo field
        private final KisMacroService kisMacroService;
        private final ObjectMapper objectMapper;

        @Cacheable(value = "stockPrice", key = "#stockCode", sync = true)
        public StockPriceDto getStockPrice(String stockCode) {
                String accessToken = kisAuthService.getAccessToken();

                Stock stock = stockRepository.findByStockCode(stockCode)
                                .orElseThrow(() -> new BusinessException(
                                                KisApiErrorCode.STOCK_NOT_FOUND));

                KisPriceApiResponse apiResponse = webClient.get()
                                .uri(uriBuilder -> uriBuilder
                                                .path(kisApiProperties.getPriceUrl())
                                                .queryParam("fid_cond_mrkt_div_code", "J")
                                                .queryParam("fid_input_iscd", stockCode)
                                                .build())
                                .header("Authorization", accessToken)
                                .header("appKey", kisApiProperties.getAppkey())
                                .header("appSecret", kisApiProperties.getAppsecret())
                                .header("tr_id", "FHKST01010100")
                                .retrieve()
                                .bodyToMono(KisPriceApiResponse.class)
                                .onErrorMap(error -> {
                                        log.error("{}의 주식 가격을 불러오는 데 실패했습니다. 에러: {}", stockCode, error.getMessage());
                                        return new BusinessException(KisApiErrorCode.STOCK_PRICE_FETCH_FAILED);
                                })
                                .block();

                StockPriceResponse raw = apiResponse.getOutput();

                // Calculate USD Price
                String usdPrice = "0.00";
                String exchangeRateStr = "0.00";
                try {
                        Double exchangeRate = kisMacroService.getLatestExchangeRate();
                        if (exchangeRate != null && exchangeRate > 0) {
                                double priceKrw = Double.parseDouble(raw.getStockPriceRaw());
                                double priceUsd = priceKrw / exchangeRate;
                                usdPrice = String.format("%.2f", priceUsd);
                                exchangeRateStr = String.format("%.2f", exchangeRate);
                        }
                } catch (Exception e) {
                        log.warn("Failed to calculate USD price for {}: {}", stockCode, e.getMessage());
                }

                try {
                        if (raw.getMarketCapRaw() != null && !raw.getMarketCapRaw().isEmpty()) {
                                BigDecimal marketCap = new BigDecimal(raw.getMarketCapRaw().replace(",", ""));
                                // Update StockMarketCap Entity
                                StockMarketCap marketCapEntity = stockMarketCapRepository
                                                .findByStock(stock).orElse(null);

                                if (marketCapEntity == null) {
                                        marketCapEntity = StockMarketCap
                                                        .create(stock, marketCap);
                                } else {
                                        marketCapEntity.updateMarketCap(marketCap);
                                }
                                stockMarketCapRepository.save(marketCapEntity);
                        }
                } catch (Exception e) {
                        log.warn("Failed to update market cap for {}: {}", stockCode, e.getMessage());
                }

                return new StockPriceDto(
                                raw.getStockCode(),
                                stock.getStockName(),
                                stock.getMarketName(),
                                raw.getStockPriceRaw(),
                                raw.getPriceChangeRaw(),
                                raw.getChangeRateRaw(),
                                raw.getAccumulatedVolumeRaw(),
                                raw.getOpeningPriceRaw(),
                                usdPrice,
                                exchangeRateStr,
                                raw.getListedSharesCount(),
                                raw.getMarketCapRaw());
        }

        // Hybrid Caching: Past Data (Cached) + Today's Data (Real-time)
        public String getStockChartJson(String stockCode, String startDate, String endDate,
                        String dateType) {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
                LocalDate targetStartDate = LocalDate.parse(startDate, formatter);
                LocalDate targetEndDate = LocalDate.parse(endDate, formatter);
                LocalDate today = LocalDate.now();

                // 1. Fetch Past Data (Cached JSON)
                LocalDate pastEndDate = targetEndDate;
                if (!pastEndDate.isBefore(today)) {
                        pastEndDate = today.minusDays(1);
                }

                String pastJson = "[]";
                if (!pastEndDate.isBefore(targetStartDate)) {
                        pastJson = getPastStockChartJson(stockCode, startDate,
                                        pastEndDate.format(formatter), dateType);
                }

                // 2. Fetch Today's Data (Cached JSON)
                String todayJson = "[]";
                if (!targetEndDate.isBefore(today) && "D".equals(dateType)) {
                        todayJson = getTodayStockChartJson(stockCode);
                }

                // 3. Merge JSON Strings directly (Zero-Copy)
                String mergedListJson = mergeJsonArrays(todayJson, pastJson);

                // 4. Construct Final Response JSON
                return String.format("{\"rt_cd\":\"0\", \"msg1\":\"Success\", \"output2\":%s}", mergedListJson);
        }

        public StockChartResponseDto getStockChart(String stockCode, String startDate, String endDate,
                        String dateType) {
                String json = getStockChartJson(stockCode, startDate, endDate, dateType);
                try {
                        return objectMapper.readValue(json, StockChartResponseDto.class);
                } catch (Exception e) {
                        log.error("Failed to parse stock chart JSON for internal use", e);
                        return null; // Or throw custom exception
                }
        }

        private String mergeJsonArrays(String json1, String json2) {
                // Handle nulls or empty strings
                boolean isEmpty1 = (json1 == null || json1.equals("[]") || json1.isEmpty());
                boolean isEmpty2 = (json2 == null || json2.equals("[]") || json2.isEmpty());

                if (isEmpty1 && isEmpty2)
                        return "[]";
                if (isEmpty1)
                        return json2;
                if (isEmpty2)
                        return json1;

                // Remove closing bracket of json1 and opening bracket of json2
                // json1: "[{...}]" -> "[{...}"
                // json2: "[{...}]" -> ",{...}]"
                return json1.substring(0, json1.length() - 1) + "," + json2.substring(1);
        }

        @Cacheable(value = "stockChartJson", key = "#stockCode + '-' + #startDate + '-' + #endDate + '-' + #dateType")
        public String getPastStockChartJson(String stockCode, String startDate, String endDate, String dateType) {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
                LocalDate targetStartDate = LocalDate.parse(startDate, formatter);
                LocalDate targetEndDate = LocalDate.parse(endDate, formatter);

                Stock stock = stockRepository.findByStockCode(stockCode)
                                .orElseThrow(() -> new BusinessException(KisApiErrorCode.STOCK_NOT_FOUND));

                List<StockChartPriceDto> resultList = null;

                try {
                        LocalDate apiStartDate = targetEndDate.minusDays(150);
                        if (apiStartDate.isBefore(targetStartDate)) {
                                apiStartDate = targetStartDate;
                        }
                        String apiStartDateStr = apiStartDate.format(formatter);

                        StockChartResponseDto apiResponse = fetchStockChartFromApi(stockCode, apiStartDateStr, endDate,
                                        dateType);

                        if (apiResponse.getPriceList() != null && !apiResponse.getPriceList().isEmpty()) {
                                for (StockChartPriceDto priceDto : apiResponse.getPriceList()) {
                                        LocalDate parsedDate = LocalDate.parse(priceDto.getDate(), formatter);
                                        if (stockDailyDataRepository.findByStockAndDate(stock, parsedDate)
                                                        .isPresent()) {
                                                continue;
                                        }

                                        StockDailyData entity = StockDailyData.builder()
                                                        .stock(stock)
                                                        .date(parsedDate)
                                                        .closingPrice(new BigDecimal(priceDto.getClosePrice()))
                                                        .openingPrice(new BigDecimal(priceDto.getOpenPrice()))
                                                        .highPrice(new BigDecimal(priceDto.getHighPrice()))
                                                        .lowPrice(new BigDecimal(priceDto.getLowPrice()))
                                                        .volume(new BigDecimal(priceDto.getVolume()))
                                                        .priceChange(null)
                                                        .changeRate(null)
                                                        .build();
                                        stockDailyDataRepository.save(entity);
                                }
                        }

                        List<StockChartPriceDto> filteredApiList = new ArrayList<>();
                        if (apiResponse.getPriceList() != null) {
                                filteredApiList = apiResponse.getPriceList().stream()
                                                .filter(dto -> {
                                                        LocalDate dtoDate = LocalDate.parse(dto.getDate(), formatter);
                                                        return !dtoDate.isBefore(targetStartDate)
                                                                        && !dtoDate.isAfter(targetEndDate);
                                                })
                                                .collect(Collectors.toList());
                        }

                        List<StockChartPriceDto> dbPriceList = new ArrayList<>();
                        if (!filteredApiList.isEmpty()) {
                                String oldestApiDateStr = filteredApiList.get(filteredApiList.size() - 1).getDate();
                                LocalDate oldestApiDate = LocalDate.parse(oldestApiDateStr, formatter);

                                if (oldestApiDate.isAfter(targetStartDate)) {
                                        List<StockDailyData> pastDataList = stockDailyDataRepository
                                                        .findAllByStock_StockCodeAndDateBetweenOrderByDateDesc(
                                                                        stockCode, targetStartDate,
                                                                        oldestApiDate.minusDays(1));

                                        pastDataList = filterHistoricalData(pastDataList, dateType);

                                        dbPriceList = pastDataList.stream()
                                                        .map(this::convertToDto)
                                                        .collect(Collectors.toList());
                                }
                        } else {
                                List<StockDailyData> pastDataList = stockDailyDataRepository
                                                .findAllByStock_StockCodeAndDateBetweenOrderByDateDesc(
                                                                stockCode, targetStartDate, targetEndDate);

                                pastDataList = filterHistoricalData(pastDataList, dateType);

                                dbPriceList = pastDataList.stream()
                                                .map(this::convertToDto)
                                                .collect(Collectors.toList());
                        }

                        List<StockChartPriceDto> mergedList = new ArrayList<>(filteredApiList);
                        mergedList.addAll(dbPriceList);

                        // Apply USD Conversion before caching
                        resultList = applyUsdConversion(mergedList, startDate, endDate);

                } catch (Exception e) {
                        log.warn("API 조회 실패, DB 데이터로 대체합니다: {}", e.getMessage());
                        List<StockDailyData> pastDataList = stockDailyDataRepository
                                        .findAllByStock_StockCodeAndDateBetweenOrderByDateDesc(
                                                        stockCode, targetStartDate, targetEndDate);

                        pastDataList = filterHistoricalData(pastDataList, dateType);

                        List<StockChartPriceDto> dtos = pastDataList.stream()
                                        .map(this::convertToDto)
                                        .collect(Collectors.toList());

                        // Apply USD Conversion before caching
                        resultList = applyUsdConversion(dtos, startDate, endDate);
                }

                if (resultList == null)
                        return "[]";

                try {
                        return objectMapper.writeValueAsString(resultList);
                } catch (Exception e) {
                        log.error("JSON Serialization Failed for Past Chart", e);
                        return "[]";
                }
        }

        @Cacheable(value = "stockChartTodayJson", key = "#stockCode", sync = true)
        public String getTodayStockChartJson(String stockCode) {
                String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                try {
                        StockChartResponseDto response = fetchStockChartFromApi(stockCode, today, today, "D");
                        if (response.getPriceList() != null && !response.getPriceList().isEmpty()) {
                                List<StockChartPriceDto> singleList = new ArrayList<>();
                                singleList.add(response.getPriceList().get(0));
                                List<StockChartPriceDto> converted = applyUsdConversion(singleList, today, today);
                                return objectMapper.writeValueAsString(converted);
                        }
                } catch (Exception e) {
                        log.warn("Failed to fetch today's data for {}: {}", stockCode, e.getMessage());
                }
                return "[]";
        }

        private StockChartResponseDto fetchStockChartFromApi(String stockCode, String startDate, String endDate,
                        String dateType) {
                String accessToken = kisAuthService.getAccessToken();

                StockChartResponseDto apiResponse;
                try {
                        apiResponse = webClient.get()
                                        .uri(uriBuilder -> uriBuilder
                                                        .path(kisApiProperties.getStockChartUrl())
                                                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                                                        .queryParam("FID_INPUT_ISCD", stockCode)
                                                        .queryParam("FID_INPUT_DATE_1", startDate)
                                                        .queryParam("FID_INPUT_DATE_2", endDate)
                                                        .queryParam("FID_PERIOD_DIV_CODE", dateType)
                                                        .queryParam("FID_ORG_ADJ_PRC", "0")
                                                        .build())
                                        .header("authorization", accessToken)
                                        .header("appkey", kisApiProperties.getAppkey())
                                        .header("appsecret", kisApiProperties.getAppsecret())
                                        .header("tr_id", "FHKST03010100")
                                        .header("custtype", "P")
                                        .retrieve()
                                        .bodyToMono(StockChartResponseDto.class)
                                        .block();
                } catch (Exception e) {
                        log.error("KIS API WebClient Error for {}: {}", stockCode, e.getMessage(), e);
                        throw new BusinessException(KisApiErrorCode.INDEX_FETCH_FAILED);
                }

                if (apiResponse == null || !"0".equals(apiResponse.getRtCd())) {
                        log.error("API Response is valid but list is empty or error. RtCd: {}, Msg: {}",
                                        apiResponse != null ? apiResponse.getRtCd() : "null",
                                        apiResponse != null ? apiResponse.getMsg1() : "null response");
                        throw new BusinessException(KisApiErrorCode.INDEX_FETCH_FAILED);
                }

                return apiResponse;
        }

        @Transactional
        public void fetchAndSaveHistoricalStockData(String stockCode, String startDateStr) {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
                LocalDate targetStartDate = LocalDate.parse(startDateStr, formatter);
                LocalDate currentDate = LocalDate.now();

                log.info("Starting historical stock data fetch for {} from {} to {}", stockCode, currentDate,
                                targetStartDate);

                Stock stock = stockRepository.findByStockCode(stockCode)
                                .orElseThrow(() -> new BusinessException(KisApiErrorCode.STOCK_NOT_FOUND));

                int consecutiveFailures = 0;
                final int MAX_CONSECUTIVE_FAILURES = 5;

                while (currentDate.isAfter(targetStartDate)) {
                        String currentDateStr = currentDate.format(formatter);
                        LocalDate queryStartDate = currentDate.minusDays(100);
                        if (queryStartDate.isBefore(targetStartDate)) {
                                queryStartDate = targetStartDate;
                        }
                        String queryStartDateStr = queryStartDate.format(formatter);

                        log.info("Fetching data for {} from {} to {}", stockCode, queryStartDateStr, currentDateStr);

                        try {
                                StockChartResponseDto response = fetchStockChartFromApi(stockCode, queryStartDateStr,
                                                currentDateStr, "D");

                                if (response.getPriceList() == null || response.getPriceList().isEmpty()) {
                                        log.warn("No data returned for {} from {} to {}. Retrying with previous period.",
                                                        stockCode, queryStartDateStr, currentDateStr);
                                        currentDate = queryStartDate.minusDays(1);
                                        consecutiveFailures++;
                                        if (consecutiveFailures > MAX_CONSECUTIVE_FAILURES) {
                                                log.error("Too many consecutive failures ({}). Stopping.",
                                                                consecutiveFailures);
                                                break;
                                        }
                                        continue;
                                }

                                consecutiveFailures = 0;

                                for (StockChartPriceDto priceDto : response.getPriceList()) {
                                        LocalDate parsedDate = LocalDate.parse(priceDto.getDate(), formatter);
                                        if (stockDailyDataRepository.findByStockAndDate(stock, parsedDate)
                                                        .isPresent()) {
                                                continue;
                                        }
                                        StockDailyData entity = StockDailyData.builder()
                                                        .stock(stock)
                                                        .date(parsedDate)
                                                        .closingPrice(new BigDecimal(priceDto.getClosePrice()))
                                                        .openingPrice(new BigDecimal(priceDto.getOpenPrice()))
                                                        .highPrice(new BigDecimal(priceDto.getHighPrice()))
                                                        .lowPrice(new BigDecimal(priceDto.getLowPrice()))
                                                        .volume(new BigDecimal(priceDto.getVolume()))
                                                        .build();
                                        stockDailyDataRepository.save(entity);
                                }

                                String oldestDateStr = response.getPriceList().get(response.getPriceList().size() - 1)
                                                .getDate();
                                LocalDate oldestDate = LocalDate.parse(oldestDateStr, formatter);

                                if (!oldestDate.isBefore(currentDate)) {
                                        log.warn("가장 오래된 날짜 {}가 현재 날짜 {}보다 이전이 아닙니다. 수동으로 이동합니다.", oldestDate,
                                                        currentDate);
                                        currentDate = currentDate.minusDays(100);
                                } else {
                                        currentDate = oldestDate.minusDays(1);
                                }

                                Thread.sleep(100);

                        } catch (Exception e) {
                                log.error("Error fetching historical data for {}: {}", stockCode, e.getMessage());
                                currentDate = currentDate.minusDays(1);
                                consecutiveFailures++;
                                if (consecutiveFailures > MAX_CONSECUTIVE_FAILURES) {
                                        break;
                                }
                        }
                }
                log.info("Finished historical stock data fetch for {}", stockCode);
        }

        public void fetchAllStocksHistoricalData(String startDateStr) {
                log.info("Starting batch historical stock data fetch from {}", startDateStr);
                List<Stock> allStocks = stockRepository.findAll();

                for (Stock stock : allStocks) {
                        try {
                                log.info("Updating data for stock: {} ({})", stock.getStockName(),
                                                stock.getStockCode());
                                fetchAndSaveHistoricalStockData(stock.getStockCode(), startDateStr);
                                Thread.sleep(500);
                        } catch (Exception e) {
                                log.error("Failed to update stock: {} ({}) - {}", stock.getStockName(),
                                                stock.getStockCode(), e.getMessage());
                        }
                }
                log.info("Completed batch historical stock data fetch.");
        }

        public void fetchStocksHistoricalDataByRange(Long startId, Long endId, String startDateStr) {
                log.info("Starting historical stock data fetch for stock_id range {} to {} from {}", startId, endId,
                                startDateStr);
                List<Stock> stocks = stockRepository.findAllByStockIdBetween(startId, endId);

                for (Stock stock : stocks) {
                        try {
                                log.info("Updating data for stock: {} ({}) - ID: {}", stock.getStockName(),
                                                stock.getStockCode(), stock.getStockId());
                                fetchAndSaveHistoricalStockData(stock.getStockCode(), startDateStr);
                                Thread.sleep(500);
                        } catch (Exception e) {
                                log.error("Failed to update stock: {} ({}) - {}", stock.getStockName(),
                                                stock.getStockCode(), e.getMessage());
                        }
                }
                log.info("Completed historical stock data fetch for range {} to {}.", startId, endId);
        }

        private List<StockDailyData> filterHistoricalData(List<StockDailyData> dataList, String dateType) {
                if ("D".equalsIgnoreCase(dateType)) {
                        return dataList;
                }

                List<StockDailyData> filtered = new ArrayList<>();
                Set<String> visitedPeriods = new HashSet<>();
                DateTimeFormatter monthFormatter = DateTimeFormatter.ofPattern("yyyyMM");

                for (StockDailyData data : dataList) {
                        String key = "";
                        if ("M".equalsIgnoreCase(dateType)) {
                                key = data.getDate().format(monthFormatter);
                        } else if ("W".equalsIgnoreCase(dateType)) {
                                int year = data.getDate().get(WeekFields.ISO.weekBasedYear());
                                int week = data.getDate().get(WeekFields.ISO.weekOfWeekBasedYear());
                                key = year + "-W" + week;
                        }

                        if (!visitedPeriods.contains(key)) {
                                filtered.add(data);
                                visitedPeriods.add(key);
                        }
                }
                return filtered;
        }

        private StockChartPriceDto convertToDto(StockDailyData entity) {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
                return StockChartPriceDto.builder()
                                .date(entity.getDate().format(formatter))
                                .closePrice(entity.getClosingPrice().toString())
                                .openPrice(entity.getOpeningPrice().toString())
                                .highPrice(entity.getHighPrice().toString())
                                .lowPrice(entity.getLowPrice().toString())
                                .volume(entity.getVolume().toString())
                                .build();
        }

        public VolumeRankDto getVolumeRank() {
                String accessToken = kisAuthService.getAccessToken();

                try {
                        KisVolumeRankApiResponse response = webClient.get()
                                        .uri(uriBuilder -> uriBuilder
                                                        .path(kisApiProperties.getVolumeRankUrl())
                                                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                                                        .queryParam("FID_COND_SCR_DIV_CODE", "20171")
                                                        .queryParam("FID_INPUT_ISCD", "0000")
                                                        .queryParam("FID_DIV_CLS_CODE", "0")
                                                        .queryParam("FID_BLNG_CLS_CODE", "0")
                                                        .queryParam("FID_TRGT_CLS_CODE", "111111111")
                                                        .queryParam("FID_TRGT_EXLS_CLS_CODE", "000000")
                                                        .queryParam("FID_INPUT_PRICE_1", "")
                                                        .queryParam("FID_INPUT_PRICE_2", "")
                                                        .queryParam("FID_VOL_CNT", "")
                                                        .queryParam("FID_INPUT_DATE_1", "")
                                                        .build())
                                        .header("authorization", accessToken)
                                        .header("appkey", kisApiProperties.getAppkey())
                                        .header("appsecret", kisApiProperties.getAppsecret())
                                        .header("tr_id", "FHPST01710000")
                                        .header("custtype", "P")
                                        .retrieve()
                                        .bodyToMono(KisVolumeRankApiResponse.class)
                                        .block();

                        if (response == null || response.getOutput() == null) {
                                return VolumeRankDto.builder().ranks(new ArrayList<>()).build();
                        }

                        List<VolumeRankDto.VolumeRankEntry> ranks = response.getOutput().stream()
                                        .map(item -> VolumeRankDto.VolumeRankEntry.builder()
                                                        .stockName(item.getStockName())
                                                        .stockCode(item.getStockCode())
                                                        .rank(item.getRank())
                                                        .currentPrice(item.getCurrentPrice())
                                                        .priceChangeSign(item.getPriceChangeSign())
                                                        .priceChange(item.getPriceChange())
                                                        .priceChangeRate(item.getPriceChangeRate())
                                                        .accumulatedVolume(item.getAccumulatedVolume())
                                                        .previousDayVolume(item.getPreviousDayVolume())
                                                        .averageVolume(item.getAverageVolume())
                                                        .build())
                                        .collect(Collectors.toList());

                        return VolumeRankDto.builder().ranks(ranks).build();

                } catch (Exception e) {
                        log.error("Failed to fetch volume rank: {}", e.getMessage());
                        return null;
                }
        }

        public List<StockSearchResponseDto> searchStocks(String keyword) {
                List<Stock> stocks = stockRepository.findByStockCodeContainingOrStockNameContaining(keyword, keyword);
                return stocks.stream()
                                .map(stock -> StockSearchResponseDto.builder()
                                                .stockCode(stock.getStockCode())
                                                .stockName(stock.getStockName())
                                                .marketName(stock.getMarketName())
                                                .build())
                                .collect(Collectors.toList());
        }

        public List<StockSearchResponseDto> getMarketCapRanking() {
                return getMarketCapRanking(1, 100);
        }

        public List<StockSearchResponseDto> getMarketCapRanking(int start, int end) {
                if (start < 1)
                        start = 1;
                if (end < start)
                        end = start;

                int limit = end - start + 1;
                long offset = start - 1;

                Pageable pageable = new OffsetBasedPageRequest(offset, limit);
                List<StockMarketCap> marketCaps = stockMarketCapRepository.findByOrderByMarketCapDesc(pageable);

                return marketCaps.stream()
                                .map(smc -> {
                                        Stock stock = smc.getStock();

                                        String currentPrice = null;
                                        String priceChange = null;
                                        String changeRate = null;

                                        try {
                                                // Fetch from Cache (Redis)
                                                // Since we have a warmer, this should be fast.
                                                StockPriceDto priceDto = getStockPrice(stock.getStockCode());
                                                if (priceDto != null) {
                                                        currentPrice = priceDto.getStockPrice();
                                                        priceChange = priceDto.getPriceChange();
                                                        changeRate = priceDto.getChangeRate();
                                                }
                                        } catch (Exception e) {
                                                // Ignore error to avoid breaking the whole list
                                        }

                                        return StockSearchResponseDto.builder()
                                                        .stockCode(stock.getStockCode())
                                                        .stockName(stock.getStockName())
                                                        .marketName(stock.getMarketName())
                                                        .marketCap(smc.getMarketCap() != null
                                                                        ? smc.getMarketCap().toString()
                                                                        : null)
                                                        .currentPrice(currentPrice)
                                                        .priceChange(priceChange)
                                                        .changeRate(changeRate)
                                                        .build();
                                })
                                .collect(Collectors.toList());
        }

        public void refreshTopMarketCapPrices() {
                List<StockMarketCap> top100 = stockMarketCapRepository.findTop100ByOrderByMarketCapDesc();
                log.info("Warming up prices for Top {} Market Cap stocks...", top100.size());

                for (StockMarketCap smc : top100) {
                        try {
                                // Call getStockPrice to refresh cache
                                getStockPrice(smc.getStock().getStockCode());
                                Thread.sleep(50); // Small delay to prevent API rate limit issues
                        } catch (Exception e) {
                                log.warn("Failed to warm up price for {}: {}", smc.getStock().getStockCode(),
                                                e.getMessage());
                        }
                }
                log.info("Finished warming up prices.");
        }

        public void initAllStocksMarketCap() {
                List<Stock> allStocks = stockRepository.findAll();
                log.info("Starting initialization of Market Cap for {} stocks...", allStocks.size());

                for (Stock stock : allStocks) {
                        try {
                                // Call getStockPrice to fetch & save market cap
                                // Note: This makes an API call per stock. KIS API limit check required.
                                // For now, process sequentially with delay.
                                getStockPrice(stock.getStockCode());
                                Thread.sleep(100); // Prevent rate limit (10 req/sec usually safe, but being
                                                   // conservative)
                        } catch (Exception e) {
                                log.error("Failed to init market cap for {}: {}", stock.getStockCode(), e.getMessage());
                        }
                }
                log.info("Finished initialization of Market Cap.");
        }

        private List<StockChartPriceDto> applyUsdConversion(List<StockChartPriceDto> list, String startDate,
                        String endDate) {
                if (list == null || list.isEmpty()) {
                        return list;
                }
                Map<String, Double> exchangeRateMap = kisMacroService.getExchangeRateMap(startDate, endDate);
                DateTimeFormatter dtFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");

                List<StockChartPriceDto> result = new ArrayList<>();
                for (StockChartPriceDto dto : list) {
                        StockChartPriceDto newDto = dto;
                        try {
                                LocalDate date = LocalDate.parse(dto.getDate(), dtFormatter);
                                Double rate = null;

                                for (int i = 0; i < 7; i++) {
                                        String queryDate = date.minusDays(i).format(dtFormatter);
                                        rate = exchangeRateMap.get(queryDate);
                                        if (rate != null)
                                                break;
                                }

                                if (rate != null && rate > 0) {
                                        newDto = StockChartPriceDto.builder()
                                                        .date(dto.getDate())
                                                        .closePrice(dto.getClosePrice())
                                                        .openPrice(dto.getOpenPrice())
                                                        .highPrice(dto.getHighPrice())
                                                        .lowPrice(dto.getLowPrice())
                                                        .volume(dto.getVolume())
                                                        .closePriceUsd(String.format("%.2f",
                                                                        Double.parseDouble(dto.getClosePrice()) / rate))
                                                        .openPriceUsd(String.format("%.2f",
                                                                        Double.parseDouble(dto.getOpenPrice()) / rate))
                                                        .highPriceUsd(String.format("%.2f",
                                                                        Double.parseDouble(dto.getHighPrice()) / rate))
                                                        .lowPriceUsd(String.format("%.2f",
                                                                        Double.parseDouble(dto.getLowPrice()) / rate))
                                                        .exchangeRate(String.format("%.2f", rate))
                                                        .build();
                                }
                        } catch (Exception e) {
                                // ignore
                        }
                        result.add(newDto);
                }
                return result;
        }

        @Transactional(readOnly = true)
        public List<StockInvestorDailyDto> getDailyInvestorTrend(String stockCode) {
                Stock stock = stockRepository.findByStockCode(stockCode)
                                .orElseThrow(() -> new IllegalArgumentException("Stock not found"));

                // Fetch last 3 months
                LocalDate today = LocalDate.now();
                LocalDate threeMonthsAgo = today.minusMonths(3);

                List<StockInvestorDaily> dailyData = stockInvestorDailyRepository
                                .findByStockAndDateBetweenOrderByDateAsc(stock, threeMonthsAgo, today);

                return dailyData.stream().map(d -> StockInvestorDailyDto.builder()
                                .date(d.getDate().toString())
                                .foreignerNetBuyAmount(String.valueOf(d.getForeignerNetBuyAmount().longValue()))
                                .institutionNetBuyAmount(String.valueOf(d.getInstitutionNetBuyAmount().longValue()))
                                .personalNetBuyAmount(String.valueOf(d.getPersonalNetBuyAmount().longValue()))
                                .etcCorporateNetBuyAmount(d.getEtcCorporateNetBuyAmount() != null
                                                ? String.valueOf(d.getEtcCorporateNetBuyAmount().longValue())
                                                : "0")
                                .foreignerNetBuyQuantity(d.getForeignerNetBuyQuantity() != null
                                                ? String.valueOf(d.getForeignerNetBuyQuantity())
                                                : "0")
                                .institutionNetBuyQuantity(d.getInstitutionNetBuyQuantity() != null
                                                ? String.valueOf(d.getInstitutionNetBuyQuantity())
                                                : "0")
                                .personalNetBuyQuantity(d.getPersonalNetBuyQuantity() != null
                                                ? String.valueOf(d.getPersonalNetBuyQuantity())
                                                : "0")
                                .build())
                                .collect(Collectors.toList());
        }

        @Transactional
        public InvestorTrendDto getInvestorTrend(String stockCode) {
                // 1. Check if we have recent data in DB (e.g. within last 3 days or today's
                // data depending on batch)
                // For simplicity, we check if ANY data exists for recent 3 months.
                // Or simplified: Just Query DB. If empty, try fetch.

                Stock stock = stockRepository.findByStockCode(stockCode)
                                .orElseThrow(() -> new IllegalArgumentException("Stock not found"));

                LocalDate today = LocalDate.now();
                LocalDate threeMonthsAgo = today.minusMonths(3);

                // Lazy Load Check
                boolean hasData = stockInvestorDailyRepository.findLatestDateByStock(stock).isPresent();
                if (!hasData) {
                        log.info("No investor trend data for {}. Fetching from API...", stockCode);
                        fetchAndSaveInvestorTrend(stockCode);
                }

                // 2. Query DB
                List<StockInvestorDaily> dailyData = stockInvestorDailyRepository
                                .findByStockAndDateBetweenOrderByDateAsc(stock, threeMonthsAgo, today);

                // 3. Sum up
                BigDecimal foreignerSum = BigDecimal.ZERO;
                BigDecimal institutionSum = BigDecimal.ZERO;

                for (StockInvestorDaily daily : dailyData) {
                        foreignerSum = foreignerSum.add(daily.getForeignerNetBuyAmount());
                        institutionSum = institutionSum.add(daily.getInstitutionNetBuyAmount());
                }

                // 4. Determine Trend
                String trend = "중립";
                BigDecimal tenBillion = new BigDecimal("10000000000");

                boolean isFrgnBuy = foreignerSum.compareTo(tenBillion) > 0;
                boolean isInstBuy = institutionSum.compareTo(tenBillion) > 0;
                boolean isFrgnSell = foreignerSum.compareTo(tenBillion.negate()) < 0;
                boolean isInstSell = institutionSum.compareTo(tenBillion.negate()) < 0;

                if (isFrgnBuy && isInstBuy)
                        trend = "양매수 (강력 매수 우위)";
                else if (isFrgnBuy)
                        trend = "외국인 주도 매수";
                else if (isInstBuy)
                        trend = "기관 주도 매수";
                else if (isFrgnSell && isInstSell)
                        trend = "양매도 (매도 우위)";
                else if (isFrgnSell)
                        trend = "외국인 매도세";
                else if (isInstSell)
                        trend = "기관 매도세";

                return InvestorTrendDto.builder()
                                .recent3MonthForeignerNetBuy(String.valueOf(foreignerSum.longValue()))
                                .recent3MonthInstitutionNetBuy(String.valueOf(institutionSum.longValue()))
                                .trendStatus(trend)
                                .build();
        }

        @Transactional
        public void fetchAndSaveInvestorTrend(String stockCode) {
                String accessToken = kisAuthService.getAccessToken();
                String url = kisApiProperties.getInvestorTrendDailyUrl();

                if (url == null || url.isEmpty()) {
                        log.error("KisApiProperties.investorTrendDailyUrl is NULL. Check application.yml");
                        return;
                }

                Stock stock = stockRepository.findByStockCode(stockCode)
                                .orElseThrow(() -> new IllegalArgumentException("Stock not found"));

                try {
                        // API Call
                        KisInvestorDailyResponse response = webClient.get()
                                        .uri(uriBuilder -> uriBuilder
                                                        .path(url)
                                                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                                                        .queryParam("FID_INPUT_ISCD", stockCode)
                                                        .queryParam("FID_INPUT_DATE_1", LocalDate.now().format(
                                                                        DateTimeFormatter.BASIC_ISO_DATE))
                                                        .queryParam("FID_ORG_ADJ_PRC", "0")
                                                        .queryParam("FID_ETC_CLS_CODE", "0")
                                                        .build())
                                        .header("content-type", "application/json; charset=utf-8")
                                        .header("authorization", accessToken)
                                        .header("appkey", kisApiProperties.getAppkey())
                                        .header("appsecret", kisApiProperties.getAppsecret())
                                        .header("tr_id", "FHPTJ04160001")
                                        .header("custtype", "P")
                                        .retrieve()
                                        .bodyToMono(KisInvestorDailyResponse.class)
                                        .block();

                        if (response == null || response.getOutput() == null) {
                                log.warn("No daily investor data received for {}", stockCode);
                                return;
                        }

                        // Parse and Save (Shifted: Value[i] -> Date[i+1])
                        List<KisInvestorDailyResponse.InvestorDailyOutput> list = response.getOutput();
                        for (int i = 0; i < list.size() - 1; i++) {
                                KisInvestorDailyResponse.InvestorDailyOutput valueItem = list.get(i); // Values from
                                                                                                      // here
                                KisInvestorDailyResponse.InvestorDailyOutput dateItem = list.get(i + 1); // Date from
                                                                                                         // here
                                                                                                         // (Previous
                                                                                                         // Business
                                                                                                         // Day)

                                LocalDate date = LocalDate.parse(dateItem.getStckBsopDate(),
                                                DateTimeFormatter.BASIC_ISO_DATE);

                                // Check duplicate
                                if (stockInvestorDailyRepository.existsByStockAndDate(stock, date)) {
                                        continue; // Skip if exists
                                }

                                BigDecimal frgn = parseBigDec(valueItem.getFrgnNtbyTrPbmn());
                                BigDecimal prsn = parseBigDec(valueItem.getPrsnNtbyTrPbmn());
                                BigDecimal orgn = parseBigDec(valueItem.getOrgnNtbyTrPbmn());
                                BigDecimal etc = parseBigDec(valueItem.getEtcCorpNtbyTrPbmn());

                                Long frgnQty = parseLong(valueItem.getFrgnNtbyQty());
                                Long prsnQty = parseLong(valueItem.getPrsnNtbyQty());
                                Long orgnQty = parseLong(valueItem.getOrgnNtbyQty());

                                StockInvestorDaily entity = StockInvestorDaily.builder()
                                                .stock(stock)
                                                .date(date)
                                                .foreignerNetBuyAmount(frgn)
                                                .personalNetBuyAmount(prsn)
                                                .institutionNetBuyAmount(orgn)
                                                .etcCorporateNetBuyAmount(etc)
                                                .foreignerNetBuyQuantity(frgnQty)
                                                .personalNetBuyQuantity(prsnQty)
                                                .institutionNetBuyQuantity(orgnQty)
                                                .build();

                                stockInvestorDailyRepository.save(entity);
                        }
                        log.info("Saved investor trend data for {}", stockCode);

                } catch (Exception e) {
                        log.error("Failed to fetch/save investor trend daily for {}: {}", stockCode, e.getMessage());
                }
        }

        private BigDecimal parseBigDec(String val) {
                if (val == null || val.isEmpty() || val.equals("-"))
                        return BigDecimal.ZERO;
                try {
                        return new BigDecimal(val.replace(",", "").trim());
                } catch (Exception e) {
                        return BigDecimal.ZERO;
                }
        }

        private Long parseLong(String val) {
                if (val == null || val.isEmpty() || val.equals("-"))
                        return 0L;
                try {
                        return Long.parseLong(val.replace(",", "").trim());
                } catch (Exception e) {
                        return 0L;
                }
        }

        @Transactional
        public void deleteAllInvestorData() {
                stockInvestorDailyRepository.deleteAllInBatch();
                log.info("All investor trend data deleted.");
        }

        public void updateAllInvestorTrends() {
                List<Stock> stocks = stockRepository.findAll();
                for (Stock stock : stocks) {
                        try {
                                fetchAndSaveInvestorTrend(stock.getStockCode());
                                Thread.sleep(50); // Rate limit (20 req/sec safe limit is ~50ms)
                        } catch (Exception e) {
                                log.error("Failed to update investor trend for {}: {}", stock.getStockCode(),
                                                e.getMessage());
                        }
                }
                log.info("Completed batch update for all investor trends.");
        }

        @Transactional(readOnly = true)
        public Page<StockSearchResponseDto> getStocksBySector(String subIndustryCode,
                        Pageable pageable) {
                Page<Stock> stockPage = stockRepository
                                .findBySubIndustryCode(subIndustryCode, pageable);
                return stockPage.map(stock -> StockSearchResponseDto.builder()
                                .stockCode(stock.getStockCode())
                                .stockName(stock.getStockName())
                                .marketName(stock.getMarketName())
                                .build());
        }
}
