package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.kisStock.enums.MarketType;
import com.AISA.AISA.global.exception.BusinessException;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockDailyData;
import com.AISA.AISA.kisStock.Entity.stock.StockMarketCap;
import com.AISA.AISA.kisStock.config.KisApiProperties;
import com.AISA.AISA.kisStock.dto.StockSearchResponseDto;
import com.AISA.AISA.kisStock.dto.StockSimpleSearchResponseDto; // Import New DTO
import com.AISA.AISA.kisStock.dto.VolumeRank.KisVolumeRankApiResponse;
import com.AISA.AISA.kisStock.dto.VolumeRank.VolumeRankDto;
import com.AISA.AISA.kisStock.dto.StockPrice.KisPriceApiResponse;
import com.AISA.AISA.kisStock.dto.StockPrice.StockChartPriceDto;
import com.AISA.AISA.kisStock.dto.StockPrice.StockChartResponseDto;
import com.AISA.AISA.kisStock.dto.StockPrice.StockPriceDto;
import com.AISA.AISA.kisStock.dto.StockPrice.StockPriceResponse;
import com.AISA.AISA.kisStock.exception.KisApiErrorCode;

import com.AISA.AISA.kisStock.dto.InvestorTrend.InvestorTrendDto;
import com.AISA.AISA.kisStock.dto.InvestorTrend.StockInvestorDailyDto; // New DTO
import com.AISA.AISA.kisStock.dto.InvestorTrend.KisInvestorDailyResponse; // New DTO
import com.AISA.AISA.kisStock.Entity.stock.StockInvestorDaily;
import com.AISA.AISA.kisStock.repository.StockDailyDataRepository;
import com.AISA.AISA.kisStock.repository.StockFinancialRatioRepository;
import com.AISA.AISA.kisStock.repository.StockMarketCapRepository;
import com.AISA.AISA.kisStock.repository.StockRepository;
import com.AISA.AISA.kisStock.repository.StockInvestorDailyRepository; // New Repo
import com.AISA.AISA.analysis.repository.StockAiSummaryRepository; // New for Cache Clear
import com.AISA.AISA.analysis.repository.StockStaticAnalysisRepository; // New for Cache Clear
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.AISA.AISA.global.util.OffsetBasedPageRequest;

@Slf4j
@Service
@RequiredArgsConstructor
public class KisStockService {

        private final WebClient webClient;
        private final KisApiProperties kisApiProperties;
        private final StockRepository stockRepository;
        private final StockDailyDataRepository stockDailyDataRepository;
        private final StockMarketCapRepository stockMarketCapRepository; // Added repo
        private final StockFinancialRatioRepository stockFinancialRatioRepository; // Added
                                                                                   // repo
        private final CacheManager cacheManager;
        private final StockInvestorDailyRepository stockInvestorDailyRepository; // New Repo field
        private final StockAiSummaryRepository stockAiSummaryRepository;
        private final StockStaticAnalysisRepository stockStaticAnalysisRepository;
        private final KisMacroService kisMacroService;
        private final ObjectMapper objectMapper;
        private final KisApiClient kisApiClient;

        @Cacheable(value = "stockPrice", key = "#stockCode", sync = true)
        public StockPriceDto getStockPrice(String stockCode) {
                Stock stock = stockRepository.findByStockCode(stockCode)
                                .orElseThrow(() -> new BusinessException(
                                                KisApiErrorCode.STOCK_NOT_FOUND));

                if (stock.getStockType() != Stock.StockType.DOMESTIC) {
                        throw new BusinessException(KisApiErrorCode.INVALID_STOCK_TYPE);
                }

                KisPriceApiResponse apiResponse;
                try {
                        apiResponse = kisApiClient.fetch(token -> webClient.get()
                                        .uri(uriBuilder -> uriBuilder
                                                        .path(kisApiProperties.getPriceUrl())
                                                        .queryParam("fid_cond_mrkt_div_code", "J")
                                                        .queryParam("fid_input_iscd", stockCode)
                                                        .build())
                                        .header("Authorization", token)
                                        .header("appKey", kisApiProperties.getAppkey())
                                        .header("appSecret", kisApiProperties.getAppsecret())
                                        .header("tr_id", "FHKST01010100"), KisPriceApiResponse.class);
                } catch (Exception e) {
                        log.error("{}의 주식 가격을 불러오는 데 실패했습니다. 에러: {}", stockCode, e.getMessage());
                        throw new BusinessException(KisApiErrorCode.STOCK_PRICE_FETCH_FAILED);
                }

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
                        // Update Suspension Status
                        boolean isSuspended = "58".equals(raw.getStatusCode());
                        stock.updateSuspensionStatus(isSuspended);
                        stockRepository.save(stock);

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
                validateDomesticStock(stockCode);
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
                StockChartResponseDto apiResponse;
                try {
                        apiResponse = kisApiClient.fetch(token -> webClient.get()
                                        .uri(uriBuilder -> uriBuilder
                                                        .path(kisApiProperties.getStockChartUrl())
                                                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                                                        .queryParam("FID_INPUT_ISCD", stockCode)
                                                        .queryParam("FID_INPUT_DATE_1", startDate)
                                                        .queryParam("FID_INPUT_DATE_2", endDate)
                                                        .queryParam("FID_PERIOD_DIV_CODE", dateType)
                                                        .queryParam("FID_ORG_ADJ_PRC", "0")
                                                        .build())
                                        .header("authorization", token)
                                        .header("appkey", kisApiProperties.getAppkey())
                                        .header("appsecret", kisApiProperties.getAppsecret())
                                        .header("tr_id", "FHKST03010100")
                                        .header("custtype", "P"), StockChartResponseDto.class);
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

                if (stock.getStockType() != Stock.StockType.DOMESTIC) {
                        throw new BusinessException(KisApiErrorCode.INVALID_STOCK_TYPE);
                }

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
                try {
                        KisVolumeRankApiResponse response = kisApiClient.fetch(token -> webClient.get()
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
                                        .header("authorization", token)
                                        .header("appkey", kisApiProperties.getAppkey())
                                        .header("appsecret", kisApiProperties.getAppsecret())
                                        .header("tr_id", "FHPST01710000")
                                        .header("custtype", "P"), KisVolumeRankApiResponse.class);

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

        public List<StockSimpleSearchResponseDto> searchStocks(String keyword) {
                // Modified to search only DOMESTIC stocks using domestic-specific query
                List<Stock> stocks = stockRepository.findDomesticByKeyword(keyword);
                return stocks.stream()
                                .map(stock -> StockSimpleSearchResponseDto.builder()
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
                                Thread.sleep(100); // Increase delay for API rate limit safety
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
        public List<StockInvestorDailyDto> getDailyInvestorTrend(String stockCode, String period) {
                Stock stock = stockRepository.findByStockCode(stockCode)
                                .orElseThrow(() -> new BusinessException(KisApiErrorCode.STOCK_NOT_FOUND));

                if (stock.getStockType() != Stock.StockType.DOMESTIC) {
                        throw new BusinessException(KisApiErrorCode.INVALID_STOCK_TYPE);
                }

                // Fetch based on period
                LocalDate today = LocalDate.now();
                LocalDate startDate;
                if ("1y".equalsIgnoreCase(period)) {
                        startDate = today.minusYears(1);
                } else if ("1m".equalsIgnoreCase(period)) {
                        startDate = today.minusMonths(1);
                } else {
                        startDate = today.minusMonths(3); // Default
                }

                List<StockInvestorDaily> dailyData = stockInvestorDailyRepository
                                .findByStockAndDateBetweenOrderByDateAsc(stock, startDate, today);

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

        @Transactional(readOnly = true)
        @Cacheable(value = "investorTrend", key = "#stockCode")
        public InvestorTrendDto getInvestorTrend(String stockCode) {
                Stock stock = stockRepository.findByStockCode(stockCode)
                                .orElseThrow(() -> new BusinessException(KisApiErrorCode.STOCK_NOT_FOUND));

                if (stock.getStockType() != Stock.StockType.DOMESTIC) {
                        throw new BusinessException(KisApiErrorCode.INVALID_STOCK_TYPE);
                }

                LocalDate today = LocalDate.now();
                LocalDate oneYearAgo = today.minusYears(1);
                LocalDate threeMonthsAgo = today.minusMonths(3);
                LocalDate oneMonthAgo = today.minusMonths(1);

                // 1. Ensure data exists (Lazy load)
                boolean hasData = stockInvestorDailyRepository.findLatestDateByStock(stock).isPresent();
                if (!hasData) {
                        log.info("No investor trend data for {}. Fetching from API...", stockCode);
                        fetchAndSaveInvestorTrend(stockCode);
                }

                // 2. Query Full 1 Year Data for Statistics
                List<StockInvestorDaily> allData = stockInvestorDailyRepository
                                .findByStockAndDateBetweenOrderByDateAsc(stock, oneYearAgo, today);

                if (allData.isEmpty()) {
                        log.warn("allData is empty for {}", stockCode);
                        return InvestorTrendDto.builder()
                                        .recent1MonthForeignerNetBuy("0")
                                        .recent1MonthInstitutionNetBuy("0")
                                        .recent3MonthForeignerNetBuy("0")
                                        .recent3MonthInstitutionNetBuy("0")
                                        .recent1YearForeignerNetBuy("0")
                                        .recent1YearInstitutionNetBuy("0")
                                        .supplyScore(0)
                                        .trendStatus("데이터 부족")
                                        .build();
                }

                // 3. Prepare Price Map for Quantity Estimation (Legacy Data Support)
                List<StockDailyData> prices = stockDailyDataRepository
                                .findByStock_StockCodeAndDateBetweenOrderByDateAsc(stockCode, oneYearAgo, today);
                log.info("StockDailyData size for {}: {}", stockCode, prices.size());

                Map<LocalDate, BigDecimal> priceMap = prices.stream()
                                .collect(Collectors.toMap(StockDailyData::getDate, StockDailyData::getClosingPrice,
                                                (existing, replacement) -> existing));
                log.info("priceMap size for {}: {}", stockCode, priceMap.size());

                // 4. Get Current Price for Overheat Calculation
                BigDecimal currentPrice = BigDecimal.ZERO;
                try {
                        StockPriceDto priceDto = getStockPrice(stockCode);
                        if (priceDto != null && priceDto.getStockPrice() != null) {
                                currentPrice = new BigDecimal(priceDto.getStockPrice().replace(",", "").trim());
                        }
                } catch (Exception e) {
                        log.warn("Price fetch failed for VWAP comparison: {}", e.getMessage());
                }

                // 5. Multi-Period Aggregation
                BigDecimal f1m = BigDecimal.ZERO, i1m = BigDecimal.ZERO;
                BigDecimal f3m = BigDecimal.ZERO, i3m = BigDecimal.ZERO;
                BigDecimal f1y = BigDecimal.ZERO, i1y = BigDecimal.ZERO;

                BigDecimal f1yQty = BigDecimal.ZERO, i1yQty = BigDecimal.ZERO;
                BigDecimal f1yAmtTotal = BigDecimal.ZERO, i1yAmtTotal = BigDecimal.ZERO;

                for (StockInvestorDaily d : allData) {
                        BigDecimal fAmt = d.getForeignerNetBuyAmount();
                        BigDecimal iAmt = d.getInstitutionNetBuyAmount();

                        // Get Effective Quantity (Actual or Estimated using priceMap or fallback
                        // currentPrice)
                        BigDecimal effFQty = getEffectiveQuantity(fAmt, d.getForeignerNetBuyQuantity(), d.getDate(),
                                        priceMap, currentPrice);
                        BigDecimal effIQty = getEffectiveQuantity(iAmt, d.getInstitutionNetBuyQuantity(), d.getDate(),
                                        priceMap, currentPrice);

                        // 1 Year (All)
                        f1y = f1y.add(fAmt);
                        i1y = i1y.add(iAmt);

                        // VWAP preparation (Only positive net buy days for accumulation cost)
                        if (fAmt.compareTo(BigDecimal.ZERO) > 0 && effFQty.compareTo(BigDecimal.ZERO) > 0) {
                                f1yQty = f1yQty.add(effFQty);
                                f1yAmtTotal = f1yAmtTotal.add(fAmt);
                        }
                        if (iAmt.compareTo(BigDecimal.ZERO) > 0 && effIQty.compareTo(BigDecimal.ZERO) > 0) {
                                i1yQty = i1yQty.add(effIQty);
                                i1yAmtTotal = i1yAmtTotal.add(iAmt);
                        }

                        // 3 Months
                        if (!d.getDate().isBefore(threeMonthsAgo)) {
                                f3m = f3m.add(fAmt);
                                i3m = i3m.add(iAmt);
                        }

                        // 1 Month
                        if (!d.getDate().isBefore(oneMonthAgo)) {
                                f1m = f1m.add(fAmt);
                                i1m = i1m.add(iAmt);
                        }
                }

                // 6. VWAP & Overheat Calculation

                // Calculate VWAP (Avg Price)
                BigDecimal fAvg = BigDecimal.ZERO;
                if (f1yQty.compareTo(BigDecimal.ZERO) > 0) {
                        // Compensation: Amount (Million KRW) -> actual KRW (* 1,000,000)
                        fAvg = f1yAmtTotal.multiply(new BigDecimal("1000000")).divide(f1yQty, 0, RoundingMode.HALF_UP);
                } else if (f1y.compareTo(BigDecimal.ZERO) > 0) {
                        // Amount exists but Qty is 0: This happens with legacy data missing quantity
                        // column
                        log.warn("Foreigner Avg Price calculation failed for {}: Amount exists but Qty is zero (legacy data?)",
                                        stockCode);
                }

                BigDecimal iAvg = BigDecimal.ZERO;
                if (i1yQty.compareTo(BigDecimal.ZERO) > 0) {
                        iAvg = i1yAmtTotal.multiply(new BigDecimal("1000000")).divide(i1yQty, 0, RoundingMode.HALF_UP);
                } else if (i1y.compareTo(BigDecimal.ZERO) > 0) {
                        log.warn("Institution Avg Price calculation failed for {}: Amount exists but Qty is zero (legacy data?)",
                                        stockCode);
                }

                BigDecimal fOverheat = calculateOverheat(currentPrice, fAvg);
                BigDecimal iOverheat = calculateOverheat(currentPrice, iAvg);

                // 5. Z-Score Calculation (1 Month intensity vs 1 Year baseline)
                double fZ = calculateZScore(allData, f1m, true);
                double iZ = calculateZScore(allData, i1m, false);

                // 6. Final Supply Score (0-100)
                double supplyScore = calculateOverallScore(f1m, i1m, f3m, i3m, f1y, i1y, fZ, iZ);

                // 7. Determine Trend Status
                String trend = determineAdvancedTrend(fZ, iZ, fOverheat, iOverheat, supplyScore);

                BigDecimal million = new BigDecimal("1000000");
                return InvestorTrendDto.builder()
                                .recent1MonthForeignerNetBuy(f1m.multiply(million).toPlainString())
                                .recent1MonthInstitutionNetBuy(i1m.multiply(million).toPlainString())
                                .recent3MonthForeignerNetBuy(f3m.multiply(million).toPlainString())
                                .recent3MonthInstitutionNetBuy(i3m.multiply(million).toPlainString())
                                .recent1YearForeignerNetBuy(f1y.multiply(million).toPlainString())
                                .recent1YearInstitutionNetBuy(i1y.multiply(million).toPlainString())
                                .foreignerAvgPrice(fAvg.compareTo(BigDecimal.ZERO) == 0 ? null : fAvg)
                                .institutionAvgPrice(iAvg.compareTo(BigDecimal.ZERO) == 0 ? null : iAvg)
                                .foreignerOverheat(fOverheat)
                                .institutionOverheat(iOverheat)
                                .foreignerZScore(fZ)
                                .institutionZScore(iZ)
                                .supplyScore(supplyScore)
                                .trendStatus(trend)
                                .build();
        }

        private BigDecimal calculateOverheat(BigDecimal current, BigDecimal avg) {
                if (avg == null || avg.compareTo(BigDecimal.ZERO) <= 0 || current == null
                                || current.compareTo(BigDecimal.ZERO) <= 0)
                        return null;
                return current.subtract(avg).divide(avg, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
        }

        private double calculateZScore(List<StockInvestorDaily> allData, BigDecimal recent1m, boolean isForeigner) {
                if (allData.size() < 20)
                        return 0;

                List<Double> monthlySamples = new ArrayList<>();
                for (int i = 0; i < allData.size() - 20; i += 5) {
                        double sum = 0;
                        for (int j = 0; j < 20; j++) {
                                StockInvestorDaily d = allData.get(i + j);
                                sum += (isForeigner ? d.getForeignerNetBuyAmount() : d.getInstitutionNetBuyAmount())
                                                .doubleValue();
                        }
                        monthlySamples.add(sum);
                }

                if (monthlySamples.isEmpty())
                        return 0;

                double avg = monthlySamples.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                double variance = monthlySamples.stream().mapToDouble(s -> Math.pow(s - avg, 2)).average().orElse(0);
                double stdDev = Math.sqrt(variance);

                if (stdDev == 0)
                        return 0;
                return (recent1m.doubleValue() - avg) / stdDev;
        }

        private double calculateOverallScore(BigDecimal f1m, BigDecimal i1m, BigDecimal f3m, BigDecimal i3m,
                        BigDecimal f1y, BigDecimal i1y, double fZ, double iZ) {
                double score = 50.0;
                score += (fZ * 5) + (iZ * 10);
                if (f1m.compareTo(BigDecimal.ZERO) > 0)
                        score += 5;
                if (i1m.compareTo(BigDecimal.ZERO) > 0)
                        score += 10;
                if (f3m.compareTo(BigDecimal.ZERO) > 0)
                        score += 5;
                if (i3m.compareTo(BigDecimal.ZERO) > 0)
                        score += 5;
                return Math.max(0, Math.min(100, score));
        }

        private String determineAdvancedTrend(double fZ, double iZ, BigDecimal fOverheat, BigDecimal iOverheat,
                        double score) {
                if (fZ > 3.0 || iZ > 3.0)
                        return "수급 다이버전스/폭발 발생";
                if (score > 80) {
                        if (iOverheat.compareTo(new BigDecimal("25")) > 0)
                                return "강력 매집형 과열 (주의)";
                        return "강력한 매집 추세";
                }
                if (score > 60)
                        return "매집 진행 중";
                if (score < 30)
                        return "지속적인 매도세";
                return "관망/수급 정체";
        }

        @Transactional
        public void fetchAndSaveInvestorTrend(String stockCode) {
                String url = kisApiProperties.getInvestorTrendDailyUrl();
                if (url == null || url.isEmpty()) {
                        log.error("KisApiProperties.investorTrendDailyUrl is NULL. Check application.yml");
                        return;
                }

                Stock stock = stockRepository.findByStockCode(stockCode)
                                .orElseThrow(() -> new BusinessException(KisApiErrorCode.STOCK_NOT_FOUND));

                if (stock.getStockType() != Stock.StockType.DOMESTIC) {
                        throw new BusinessException(KisApiErrorCode.INVALID_STOCK_TYPE);
                }

                LocalDate targetDate = LocalDate.now().minusYears(1);
                LocalDate currentDate = LocalDate.now();

                while (currentDate.isAfter(targetDate)) {
                        try {
                                final String queryDate = currentDate.format(DateTimeFormatter.BASIC_ISO_DATE);
                                KisInvestorDailyResponse response = kisApiClient.fetch(token -> webClient.get()
                                                .uri(uriBuilder -> uriBuilder
                                                                .path(url)
                                                                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                                                                .queryParam("FID_INPUT_ISCD", stockCode)
                                                                .queryParam("FID_INPUT_DATE_1", queryDate)
                                                                .queryParam("FID_ORG_ADJ_PRC", "0")
                                                                .queryParam("FID_ETC_CLS_CODE", "0")
                                                                .build())
                                                .header("content-type", "application/json; charset=utf-8")
                                                .header("authorization", token)
                                                .header("appkey", kisApiProperties.getAppkey())
                                                .header("appsecret", kisApiProperties.getAppsecret())
                                                .header("tr_id", "FHPTJ04160001")
                                                .header("custtype", "P"), KisInvestorDailyResponse.class);

                                if (response == null || response.getOutput() == null
                                                || response.getOutput().isEmpty()) {
                                        break;
                                }

                                List<KisInvestorDailyResponse.InvestorDailyOutput> list = response.getOutput();
                                for (KisInvestorDailyResponse.InvestorDailyOutput item : list) {
                                        LocalDate date = LocalDate.parse(item.getStckBsopDate(),
                                                        DateTimeFormatter.BASIC_ISO_DATE);

                                        if (date.isBefore(targetDate)) {
                                                currentDate = date; // Force exit outer loop
                                                break;
                                        }

                                        if (stockInvestorDailyRepository.existsByStockAndDate(stock, date)) {
                                                continue;
                                        }

                                        BigDecimal frgn = parseBigDec(item.getFrgnNtbyTrPbmn());
                                        BigDecimal prsn = parseBigDec(item.getPrsnNtbyTrPbmn());
                                        BigDecimal orgn = parseBigDec(item.getOrgnNtbyTrPbmn());
                                        BigDecimal etc = parseBigDec(item.getEtcCorpNtbyTrPbmn());

                                        Long frgnQty = parseLong(item.getFrgnNtbyQty());
                                        Long prsnQty = parseLong(item.getPrsnNtbyQty());
                                        Long orgnQty = parseLong(item.getOrgnNtbyQty());

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

                                // Update currentDate to the oldest date in the current response to fetch the
                                // next page
                                String oldestDateStr = list.get(list.size() - 1).getStckBsopDate();
                                LocalDate oldestDate = LocalDate.parse(oldestDateStr, DateTimeFormatter.BASIC_ISO_DATE);

                                if (!oldestDate.isBefore(currentDate)) {
                                        // If the oldest date is not before current date, we are stuck or reached end
                                        break;
                                }
                                currentDate = oldestDate;

                                // Respect rate limits
                                Thread.sleep(200);

                        } catch (Exception e) {
                                log.error("Error fetching investor trend for {}: {}", stockCode, e.getMessage());
                                break;
                        }
                }
                log.info("Finished fetching investor trend data for {}", stockCode);

                // Clear caches to reflect new data
                evictInvestorCaches(stockCode);
        }

        private void evictInvestorCaches(String stockCode) {
                try {
                        if (cacheManager.getCache("investorTrend") != null) {
                                cacheManager.getCache("investorTrend").evict(stockCode);
                        }
                        if (cacheManager.getCache("staticAnalysis") != null) {
                                cacheManager.getCache("staticAnalysis").evict(stockCode);
                        }
                        // Also clear AI valuation report cache if any
                        if (cacheManager.getCache("valuationReport") != null) {
                                cacheManager.getCache("valuationReport").evict(stockCode);
                        }

                        // DB-level Cache Clear (Force AI Re-generation)
                        stockAiSummaryRepository.deleteByStockCode(stockCode);
                        stockStaticAnalysisRepository.deleteByStockCode(stockCode);

                        log.info("Evicted Redis and DB caches for {}", stockCode);
                } catch (Exception e) {
                        log.warn("Failed to evict caches for {}: {}", stockCode, e.getMessage());
                }
        }

        private BigDecimal getEffectiveQuantity(BigDecimal amt, Long qty, LocalDate date,
                        Map<LocalDate, BigDecimal> priceMap, BigDecimal fallbackPrice) {
                // 1. If actual quantity exists, use it
                if (qty != null && qty > 0) {
                        return new BigDecimal(qty);
                }

                // 2. If quantity is 0 but amount exists, estimate using historical price
                if (amt != null && amt.compareTo(BigDecimal.ZERO) != 0) {
                        BigDecimal price = priceMap.get(date);
                        // Fallback to currentPrice if historical price is missing
                        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                                price = fallbackPrice;
                        }

                        if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                                // Estimated Qty = Amount (Million KRW) * 1,000,000 / Price (KRW)
                                return amt.abs().multiply(new BigDecimal("1000000"))
                                                .divide(price, 0, RoundingMode.HALF_UP);
                        }
                }

                return BigDecimal.ZERO;
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
                                if (stock.isSuspended()) {
                                        log.info("Skipping investor trend update for suspended stock: {} ({})",
                                                        stock.getStockName(), stock.getStockCode());
                                        continue;
                                }
                                fetchAndSaveInvestorTrend(stock.getStockCode());
                                Thread.sleep(100); // Rate limit (10 req/sec safe limit is ~100ms)
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

        @Transactional
        public void updateOtherMajorRatios(String stockCode) {
                // Validate StockType: Only DOMESTIC stocks are supported for this KIS API
                validateDomesticStock(stockCode);

                updateOtherMajorRatiosByDivCode(stockCode, "0"); // Yearly
                updateOtherMajorRatiosByDivCode(stockCode, "1"); // Quarterly
        }

        private void updateOtherMajorRatiosByDivCode(String stockCode, String divCode) {
                try {
                        com.AISA.AISA.kisStock.dto.KisOtherMajorRatiosResponse response = kisApiClient.fetch(
                                        token -> webClient.get()
                                                        .uri(uriBuilder -> uriBuilder
                                                                        .path(kisApiProperties.getOtherMajorRatiosUrl())
                                                                        .queryParam("fid_cond_mrkt_div_code", "J")
                                                                        .queryParam("fid_input_iscd", stockCode)
                                                                        .queryParam("fid_div_cls_code", divCode)
                                                                        .build())
                                                        .header("authorization", token)
                                                        .header("appkey", kisApiProperties.getAppkey())
                                                        .header("appsecret", kisApiProperties.getAppsecret())
                                                        .header("tr_id", "FHKST66430500")
                                                        .header("custtype", "P"),
                                        com.AISA.AISA.kisStock.dto.KisOtherMajorRatiosResponse.class);

                        if (response != null && response.getOutput() != null) {
                                for (com.AISA.AISA.kisStock.dto.KisOtherMajorRatiosResponse.Output out : response
                                                .getOutput()) {
                                        String yymm = out.getStacYymm();
                                        BigDecimal evEbitda = parseBigDecimalSafe(out.getEvEbitda());
                                        BigDecimal ebitda = parseBigDecimalSafe(out.getEbitda());

                                        if (yymm == null)
                                                continue;

                                        // Find matching entity
                                        com.AISA.AISA.kisStock.Entity.stock.StockFinancialRatio target = stockFinancialRatioRepository
                                                        .findByStockCodeAndStacYymmAndDivCode(stockCode, yymm, divCode)
                                                        .orElse(null);

                                        if (target != null) {
                                                // Use Builder to update (toBuilder=true assumed on Entity)
                                                com.AISA.AISA.kisStock.Entity.stock.StockFinancialRatio updated = target
                                                                .toBuilder()
                                                                .evEbitda(evEbitda) // Update new fields
                                                                .ebitda(ebitda)
                                                                .build();
                                                stockFinancialRatioRepository.save(updated);
                                        }
                                }
                        }
                } catch (Exception e) {
                        log.error("Failed to update Other Major Ratios for {}: {}", stockCode, e.getMessage());
                }
        }

        private BigDecimal parseBigDecimalSafe(String value) {
                if (value == null || value.trim().isEmpty() || "-".equals(value.trim())) {
                        return BigDecimal.ZERO;
                }
                try {
                        return new BigDecimal(value.replace(",", "").trim());
                } catch (Exception e) {
                        return BigDecimal.ZERO;
                }
        }

        public void validateDomesticStock(String stockCode) {
                Stock stock = stockRepository.findByStockCode(stockCode)
                                .orElseThrow(() -> new BusinessException(KisApiErrorCode.STOCK_NOT_FOUND));

                if (stock.getStockType() != Stock.StockType.DOMESTIC) {
                        log.warn("Invalid stock type for domestic service: {} ({})", stockCode, stock.getStockType());
                        throw new BusinessException(KisApiErrorCode.INVALID_STOCK_TYPE);
                }
        }
}
