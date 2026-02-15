package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.kisOverseasStock.entity.OverseasStockDailyData;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockDailyDataRepository;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockDividend;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialRatio;
import com.AISA.AISA.kisStock.config.KisApiProperties;
import com.AISA.AISA.kisStock.dto.Dividend.DividendCalendarRequestDto;
import com.AISA.AISA.kisStock.dto.Dividend.DividendCalendarResponseDto;
import com.AISA.AISA.kisStock.dto.Dividend.KisDividendApiResponse;
import com.AISA.AISA.kisStock.dto.Dividend.StockDividendInfoDto;
import com.AISA.AISA.kisStock.dto.Dividend.DividendDetailDto;
import com.AISA.AISA.kisStock.exception.KisApiErrorCode;
import com.AISA.AISA.kisStock.kisService.Auth.KisAuthService;
import com.AISA.AISA.kisStock.kisService.KisInformationService;
import com.AISA.AISA.kisStock.repository.StockDividendRepository;
import com.AISA.AISA.kisStock.repository.StockDailyDataRepository;
import com.AISA.AISA.kisStock.repository.StockRepository;
import com.AISA.AISA.portfolio.PortfolioStock.PortStock;
import com.AISA.AISA.portfolio.PortfolioStock.PortStockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import com.AISA.AISA.kisStock.Entity.stock.StockDividendRank;
import com.AISA.AISA.kisStock.repository.StockDividendRankRepository;
import com.AISA.AISA.kisStock.dto.DividendRank.DividendRankDto;
import com.AISA.AISA.kisStock.kisService.KisStockService;
import com.AISA.AISA.kisStock.dto.StockPrice.StockPriceDto;
import com.AISA.AISA.kisStock.dto.StockPrice.StockChartResponseDto;
import com.AISA.AISA.kisStock.dto.StockPrice.StockChartPriceDto;
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
        private final KisInformationService kisInformationService;
        private final StockDividendRepository stockDividendRepository;
        private final PortStockRepository portStockRepository;
        private final KisMacroService kisMacroService;
        private final StockDailyDataRepository stockDailyDataRepository;
        private final KisOverseasStockDailyDataRepository overseasDailyDataRepository;

        @Cacheable(value = "stockDividend", key = "#stockCode + '-' + #startDate + '-' + #endDate", sync = true)
        public List<StockDividendInfoDto> getDividendInfo(
                        String stockCode,
                        String startDate,
                        String endDate) {
                validateDomesticStock(stockCode);

                // 1. DB에서 먼저 조회
                List<StockDividend> savedDividends = stockDividendRepository
                                .findByStock_StockCodeAndRecordDateBetweenOrderByRecordDateDesc(stockCode, startDate,
                                                endDate);

                if (!savedDividends.isEmpty()) {
                        // 환율 정보 준비 (US 자산용 - getDividendInfo는 국내주식 전용일 수 있으나 DTO 호환성 유지)
                        Double latestRate = kisMacroService.getLatestExchangeRate();
                        BigDecimal fallbackRate = BigDecimal.valueOf(latestRate != null ? latestRate : 1350.0);

                        Set<String> paymentDates = savedDividends.stream()
                                        .map(StockDividend::getPaymentDate)
                                        .filter(d -> d != null && d.length() == 10)
                                        .map(d -> d.replace("/", ""))
                                        .collect(Collectors.toSet());

                        Map<String, Double> finalExchangeRateMap;
                        if (!paymentDates.isEmpty()) {
                                String minDate = paymentDates.stream().min(String::compareTo).get();
                                String maxDate = paymentDates.stream().max(String::compareTo).get();
                                finalExchangeRateMap = kisMacroService.getExchangeRateMap(minDate, maxDate);
                        } else {
                                finalExchangeRateMap = Collections.emptyMap();
                        }

                        return savedDividends.stream()
                                        .filter(entity -> entity.getDividendAmount().compareTo(BigDecimal.ZERO) > 0)
                                        .map(entity -> {
                                                Stock stock = entity.getStock();
                                                boolean isUsAsset = stock.getStockType() == Stock.StockType.US_STOCK
                                                                || stock.getStockType() == Stock.StockType.US_ETF;

                                                BigDecimal rate = BigDecimal.ONE;
                                                if (isUsAsset) {
                                                        String dateStr = entity.getPaymentDate().replace("/", "");
                                                        rate = finalExchangeRateMap.containsKey(dateStr)
                                                                        ? BigDecimal.valueOf(finalExchangeRateMap
                                                                                        .get(dateStr))
                                                                        : fallbackRate;
                                                }

                                                double rateToUse = entity.getDividendRate();
                                                if (rateToUse == 0 && entity.getPaymentDate() != null) {
                                                        rateToUse = calculateDividendRate(stock,
                                                                        entity.getDividendAmount(),
                                                                        entity.getPaymentDate());
                                                }

                                                BigDecimal dividendAmountKrw = entity.getDividendAmount()
                                                                .multiply(rate);

                                                return StockDividendInfoDto.builder()
                                                                .id(entity.getId())
                                                                .stockCode(stock.getStockCode())
                                                                .stockName(stock.getStockName())
                                                                .recordDate(entity.getRecordDate())
                                                                .paymentDate(entity.getPaymentDate())
                                                                .dividendAmount(entity.getDividendAmount())
                                                                .dividendRate(rateToUse)
                                                                .stockType(stock.getStockType())
                                                                .exchangeRate(rate)
                                                                .dividendAmountKrw(dividendAmountKrw)
                                                                .build();
                                        })
                                        .collect(Collectors.toList());
                }

                // 2. DB에 없으면 API 호출
                return fetchAndSaveDividendsFromApi(stockCode, startDate, endDate);
        }

        public void validateDomesticStock(String stockCode) {
                Stock stock = stockRepository.findByStockCode(stockCode)
                                .orElseThrow(() -> new BusinessException(KisApiErrorCode.STOCK_NOT_FOUND));

                if (stock.getStockType() != Stock.StockType.DOMESTIC
                                && stock.getStockType() != Stock.StockType.DOMESTIC_ETF
                                && stock.getStockType() != Stock.StockType.FOREIGN_ETF) {
                        throw new BusinessException(KisApiErrorCode.INVALID_STOCK_TYPE);
                }
        }

        private List<StockDividendInfoDto> fetchAndSaveDividendsFromApi(String stockCode, String startDate,
                        String endDate) {
                String accessToken = kisAuthService.getAccessToken();

                Stock stock = stockRepository.findByStockCode(stockCode)
                                .orElseThrow(() -> new BusinessException(KisApiErrorCode.STOCK_NOT_FOUND));

                // The validation is now handled by validateDomesticStock, which is called in
                // getDividendInfo.
                // This check is redundant if validateDomesticStock is called before this
                // method.
                // However, if fetchAndSaveDividendsFromApi can be called independently, this
                // check might still be useful.
                // For now, keeping it as per the original structure, but the new
                // validateDomesticStock method is added.
                // If the intent was to replace this specific check with the new method, this
                // line would be removed.
                // Based on the provided diff, the new method is added, and this line is not
                // explicitly removed.
                // The instruction was to "Update validateDomesticStock to include ETFs",
                // implying the logic of the validation.
                // The provided diff shows a new method definition, not a modification of an
                // existing inline check.
                // Given the instruction and diff, the new method is added, and the existing
                // call in getDividendInfo will use it.
                // The inline check here is left as is, as the diff didn't explicitly remove it.
                if (stock.getStockType() != Stock.StockType.DOMESTIC
                                && stock.getStockType() != Stock.StockType.DOMESTIC_ETF
                                && stock.getStockType() != Stock.StockType.FOREIGN_ETF) {
                        throw new BusinessException(KisApiErrorCode.INVALID_STOCK_TYPE);
                }

                KisDividendApiResponse apiResponse = webClient.get()
                                .uri(uriBuilder -> uriBuilder
                                                .path(kisApiProperties.getDividendUrl())
                                                .queryParam("CTS", "")
                                                .queryParam("GB1", "0") // 0:배당전체
                                                .queryParam("F_DT", startDate)
                                                .queryParam("T_DT", endDate)
                                                .queryParam("SHT_CD", stockCode)
                                                .queryParam("HIGH_GB", "")
                                                .build())
                                .header("authorization", accessToken)
                                .header("appKey", kisApiProperties.getAppkey())
                                .header("appSecret", kisApiProperties.getAppsecret())
                                .header("tr_id", "HHKDB669102C0")
                                .retrieve()
                                .bodyToMono(KisDividendApiResponse.class)
                                .onErrorMap(error -> {
                                        log.error("{}의 배당 정보을 불러오는 데 실패했습니다. 에러: {}", stockCode, error.getMessage());
                                        return new BusinessException(KisApiErrorCode.DIVIDEND_FETCH_FAILED);
                                })
                                .block();

                if (apiResponse == null || !"0".equals(apiResponse.getRtCd()) || apiResponse.getOutput1() == null) {
                        log.warn("{}의 배당 정보가 없습니다.", stockCode);
                        return Collections.emptyList();
                }

                List<StockDividendInfoDto> dtoList = apiResponse.getOutput1().stream()
                                .filter(apiDto -> {
                                        try {
                                                return apiDto.getDividendAmount() != null
                                                                && !apiDto.getDividendAmount().isEmpty()
                                                                && new BigDecimal(apiDto.getDividendAmount())
                                                                                .compareTo(BigDecimal.ZERO) > 0;
                                        } catch (NumberFormatException e) {
                                                return false;
                                        }
                                })
                                .map(apiDto -> {
                                        double rate = 0.0;
                                        try {
                                                if (apiDto.getDividendRate() != null
                                                                && !apiDto.getDividendRate().isEmpty()) {
                                                        rate = Double.parseDouble(apiDto.getDividendRate());
                                                }
                                        } catch (NumberFormatException e) {
                                                // Ignore invalid rate
                                        }
                                        BigDecimal amount = new BigDecimal(apiDto.getDividendAmount());
                                        return StockDividendInfoDto.builder()
                                                        .stockCode(apiDto.getStockCode())
                                                        .stockName(apiDto.getStockName())
                                                        .recordDate(apiDto.getRecordDate())
                                                        .paymentDate(apiDto.getPaymentDate())
                                                        .dividendAmount(amount)
                                                        .dividendRate(rate)
                                                        .stockType(stock.getStockType())
                                                        .exchangeRate(BigDecimal.ONE)
                                                        .dividendAmountKrw(amount)
                                                        .build();
                                })
                                .collect(Collectors.toList());

                // 3. 주가 정보 조회를 위한 기간 설정
                if (!dtoList.isEmpty()) {
                        try {
                                String minDate = dtoList.get(dtoList.size() - 1).getRecordDate(); // 오래된 날짜
                                String maxDate = dtoList.get(0).getRecordDate(); // 최근 날짜

                                LocalDate minLocalDate = LocalDate
                                                .parse(minDate, DateTimeFormatter.ofPattern("yyyyMMdd")).minusDays(7);
                                LocalDate maxLocalDate = LocalDate
                                                .parse(maxDate, DateTimeFormatter.ofPattern("yyyyMMdd")).plusDays(7);

                                String queryStart = minLocalDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                                String queryEnd = maxLocalDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

                                StockChartResponseDto chartResponse = kisStockService.getStockChart(stockCode,
                                                queryStart, queryEnd, "D");

                                TreeMap<String, BigDecimal> priceMap = new java.util.TreeMap<>();
                                if (chartResponse != null && chartResponse.getPriceList() != null) {
                                        for (StockChartPriceDto p : chartResponse.getPriceList()) {
                                                priceMap.put(p.getDate(), new BigDecimal(p.getClosePrice()));
                                        }
                                }

                                List<StockDividend> entitiesToSave = new ArrayList<>();

                                for (StockDividendInfoDto dto : dtoList) {
                                        String recordDate = dto.getRecordDate();
                                        Entry<String, BigDecimal> entry = priceMap.floorEntry(recordDate);
                                        BigDecimal closePrice = (entry != null) ? entry.getValue() : BigDecimal.ZERO;

                                        if (closePrice.compareTo(BigDecimal.ZERO) > 0) {
                                                double calculatedRate = dto.getDividendAmount()
                                                                .divide(closePrice, 4, RoundingMode.HALF_UP)
                                                                .multiply(new BigDecimal(100))
                                                                .doubleValue();

                                                dto.setDividendRate(Double
                                                                .parseDouble(String.format("%.2f", calculatedRate)));

                                                if (!stockDividendRepository.existsByStock_StockCodeAndRecordDate(
                                                                stockCode, recordDate)) {
                                                        entitiesToSave.add(
                                                                        StockDividend
                                                                                        .builder()
                                                                                        .stock(stock)
                                                                                        .recordDate(recordDate)
                                                                                        .paymentDate(dto.getPaymentDate())
                                                                                        .dividendAmount(dto
                                                                                                        .getDividendAmount())
                                                                                        .dividendRate(dto
                                                                                                        .getDividendRate())
                                                                                        .stockPrice(closePrice)
                                                                                        .build());
                                                }
                                        }
                                }

                                if (!entitiesToSave.isEmpty()) {
                                        stockDividendRepository.saveAll(entitiesToSave);
                                }

                        } catch (Exception e) {
                                log.warn("Failed to update dividend rates with historical prices: {}", e.getMessage());
                        }
                }

                return dtoList;
        }

        @Caching(evict = {
                        @CacheEvict(value = "stockDividendDetail", key = "#stockCode"),
                        @CacheEvict(value = "stockDividend", allEntries = true),
                        @CacheEvict(value = "dividendRank", key = "'all'")
        })
        public List<StockDividendInfoDto> refreshStockDividend(String stockCode, String startDate, String endDate) {
                log.info("Refreshing dividend data for stock: {} ({}-{})", stockCode, startDate, endDate);
                return fetchAndSaveDividendsFromApi(stockCode, startDate, endDate);
        }

        @CacheEvict(value = { "stockDividend", "stockDividendDetail", "dividendRank" }, allEntries = true)
        public void refreshAllDividends(String startDate, String endDate) {
                log.info("Starting batch dividend refresh for period: {} ~ {}", startDate, endDate);
                List<Stock> allStocks = stockRepository.findAll();
                int count = 0;

                for (Stock stock : allStocks) {
                        try {
                                // Rate limit
                                TimeUnit.MILLISECONDS.sleep(100);

                                fetchAndSaveDividendsFromApi(stock.getStockCode(), startDate, endDate);
                                count++;

                                if (count % 10 == 0) {
                                        log.info("Refreshed dividend data for {} stocks...", count);
                                }
                        } catch (Exception e) {
                                log.warn("Failed to refresh dividend for {}: {}", stock.getStockCode(), e.getMessage());
                        }
                }
                log.info("Completed batch dividend refresh. Total: {}", count);
        }

        @Cacheable(value = "dividendRank", key = "'all'", sync = true)
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
                List<Stock> allStocksForType = stockRepository.findAll();
                Map<String, Stock.StockType> stockTypeMap = allStocksForType.stream()
                                .collect(Collectors.toMap(Stock::getStockCode, Stock::getStockType, (a, b) -> a));

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
                                                        .rank(entity.getRank()) // The rank
                                                                                // field is
                                                                                // now
                                                                                // Integer
                                                        .stockCode(entity.getStockCode())
                                                        .stockName(entity.getStockName())
                                                        .dividendAmount(entity.getDividendAmount())
                                                        .dividendRate(entity.getDividendRate())
                                                        .currentPrice(currentPrice)
                                                        .stockType(stockTypeMap.getOrDefault(entity.getStockCode(),
                                                                        null))
                                                        .build();
                                })
                                .collect(Collectors.toList());

                return DividendRankDto.builder()
                                .ranks(entries)
                                .build();
        }

        @Transactional
        @CacheEvict(value = "dividendRank", key = "'all'")
        public void refreshDividendRank() {
                log.info("Starting batch dividend rank refresh (DB Based)...");

                // 1. Get all stocks
                List<Stock> allStocks = stockRepository.findAll();
                List<StockDividendRank> newRankList = new ArrayList<>();

                // Date range for 'Last Year' dividend
                String endDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                String startDate = LocalDate.now().minusYears(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));

                int count = 0;

                for (Stock stock : allStocks) {
                        try {
                                // 2. Get Dividend Info FROM DB (No API Call)
                                // We assume DB is populated via refreshAllDividends or individual queries
                                List<StockDividend> dividends = stockDividendRepository
                                                .findByStock_StockCodeAndRecordDateBetweenOrderByRecordDateDesc(
                                                                stock.getStockCode(), startDate, endDate);

                                if (dividends.isEmpty())
                                        continue;

                                BigDecimal totalDividend = BigDecimal.ZERO;
                                for (StockDividend info : dividends) {
                                        totalDividend = totalDividend.add(info.getDividendAmount());
                                }

                                if (totalDividend.compareTo(BigDecimal.ZERO) == 0)
                                        continue;

                                // 3. Get Current Price (Still needs API or Cache)
                                // Optimization: If we have many stocks, this loop is still slow due to Price
                                // API.
                                // However, we can't rank without current price.
                                // Rate limit handled in getStockPrice internally or we add sleep here.
                                TimeUnit.MILLISECONDS.sleep(50); // Reduced sleep as we skipped dividend API

                                StockPriceDto priceDto = kisStockService.getStockPrice(stock.getStockCode());
                                if (priceDto == null || priceDto.getStockPrice() == null)
                                        continue;

                                BigDecimal currentPrice = new BigDecimal(priceDto.getStockPrice());

                                if (currentPrice.compareTo(BigDecimal.ZERO) == 0)
                                        continue;

                                // 4. Calculate Yield
                                // Yield = (Dividend / Price) * 100
                                double yield = totalDividend.divide(currentPrice, 4, RoundingMode.HALF_UP)
                                                .multiply(new BigDecimal(100)).doubleValue();

                                // Add to list
                                StockDividendRank rankEntity = StockDividendRank.builder()
                                                .stockCode(stock.getStockCode())
                                                .stockName(stock.getStockName())
                                                .dividendAmount(totalDividend.toString())
                                                .dividendRate(String.format("%.2f", yield))
                                                .rank(0) // Will assign later
                                                .build();

                                newRankList.add(rankEntity);
                                count++;

                                if (count % 50 == 0) {
                                        log.info("Calculated rank for {} stocks...", count);
                                }

                        } catch (Exception e) {
                                log.warn("Failed to process dividend rank for {}: {}", stock.getStockCode(),
                                                e.getMessage());
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
                                        .rank(i + 1)
                                        .build());
                }

                // 6. Save to DB
                stockDividendRankRepository.deleteAll();
                stockDividendRankRepository.saveAll(newRankList);
                log.info("Refreshed Dividend Rank with {} stocks.", newRankList.size());
        }

        @Cacheable(value = "stockDividendDetail", key = "#stockCode", sync = true)
        public DividendDetailDto getDividendDetail(String stockCode) {
                validateDomesticStock(stockCode);
                // 1. 기간 설정 (최근 1년)
                String endDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                String startDate = LocalDate.now().minusYears(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));

                // 2. 배당 내역 조회 (getDividendInfo 활용 - 없으면 API 호출 및 저장됨)
                List<StockDividendInfoDto> dividendList = getDividendInfo(stockCode, startDate, endDate);

                // 3. DPS (주당배당금) 합계 계산
                BigDecimal totalDividend = BigDecimal.ZERO;
                String recentExDate = "-";

                if (!dividendList.isEmpty()) {
                        for (StockDividendInfoDto info : dividendList) {
                                totalDividend = totalDividend.add(info.getDividendAmount());
                        }
                        recentExDate = dividendList.get(0).getRecordDate();
                }

                // 5. 현재가 조회 (Yield 계산용)
                String currentPriceStr = "0";
                BigDecimal currentPrice = BigDecimal.ZERO;
                try {
                        StockPriceDto priceDto = kisStockService.getStockPrice(stockCode);
                        if (priceDto != null) {
                                currentPriceStr = priceDto.getStockPrice();
                                currentPrice = new BigDecimal(currentPriceStr);
                        }
                } catch (Exception e) {
                        log.warn("Failed to get stock price for {}: {}", stockCode, e.getMessage());
                }

                // 6. Yield 계산
                String yield = "0";
                if (currentPrice.compareTo(BigDecimal.ZERO) > 0) {
                        yield = totalDividend.divide(currentPrice, 4, RoundingMode.HALF_UP)
                                        .multiply(new BigDecimal(100))
                                        .setScale(2, RoundingMode.HALF_UP)
                                        .toString();
                }

                // 7. Payout Ratio (배당성향) 계산 (EPS 필요)
                String payoutRatio = "0";
                try {
                        // "0": 연간
                        List<StockFinancialRatio> ratios = kisInformationService
                                        .fetchAndSaveFinancialRatio(stockCode, "0");

                        // 최신 연간 EPS 가져오기
                        // fetchAndSave... returns the list of saved/fetched entities.
                        // But need to ensure we pick the latest one if multiple returned or sorted.
                        // Assuming the API returns relevant latest data.
                        if (!ratios.isEmpty()) {
                                // Sort by stacYymm desc just in case
                                ratios.sort((r1, r2) -> r2.getStacYymm().compareTo(r1.getStacYymm()));
                                BigDecimal eps = ratios.get(0).getEps();

                                if (eps != null && eps.compareTo(BigDecimal.ZERO) > 0) {
                                        payoutRatio = totalDividend.divide(eps, 4, RoundingMode.HALF_UP)
                                                        .multiply(new BigDecimal(100))
                                                        .setScale(2, RoundingMode.HALF_UP)
                                                        .toString();
                                }
                        }
                } catch (Exception e) {
                        log.warn("Failed to calc Payout Ratio for {}: {}", stockCode, e.getMessage());
                }

                return DividendDetailDto.builder()
                                .stockCode(stockCode)
                                .dividendYield(yield)
                                .dividendPerShare(totalDividend.toString())
                                .payoutRatio(payoutRatio)

                                .recentExDividendDate(recentExDate)
                                .build();
        }

        public List<StockDividendInfoDto> getDividendCalendar(int year, int month) {
                // 1. 해당 월의 시작일과 종료일 계산
                LocalDate firstDay = LocalDate.of(year, month, 1);
                LocalDate lastDay = firstDay.withDayOfMonth(firstDay.lengthOfMonth());

                String startDate = firstDay.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                String endDate = lastDay.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

                // 2. DB에서 해당 기간의 배당 정보 전체 조회
                List<StockDividend> dividends = stockDividendRepository
                                .findByRecordDateBetweenOrderByRecordDateAsc(startDate, endDate);

                // 3. 환율 정보 준비 (US 자산 포함 가능)
                Double latestRate = kisMacroService.getLatestExchangeRate();
                BigDecimal fallbackRate = BigDecimal.valueOf(latestRate != null ? latestRate : 1350.0);

                Set<String> paymentDates = dividends.stream()
                                .map(StockDividend::getPaymentDate)
                                .filter(d -> d != null && d.length() == 10)
                                .map(d -> d.replace("/", ""))
                                .collect(Collectors.toSet());

                Map<String, Double> finalExchangeRateMap;
                if (!paymentDates.isEmpty()) {
                        String minDate = paymentDates.stream().min(String::compareTo).get();
                        String maxDate = paymentDates.stream().max(String::compareTo).get();
                        finalExchangeRateMap = kisMacroService.getExchangeRateMap(minDate, maxDate);
                } else {
                        finalExchangeRateMap = Collections.emptyMap();
                }

                // 4. DTO 변환
                return dividends.stream()
                                .map(entity -> {
                                        Stock stock = entity.getStock();
                                        boolean isUsAsset = stock.getStockType() == Stock.StockType.US_STOCK
                                                        || stock.getStockType() == Stock.StockType.US_ETF;

                                        BigDecimal rate = BigDecimal.ONE;
                                        if (isUsAsset) {
                                                String dateStr = entity.getPaymentDate().replace("/", "");
                                                rate = finalExchangeRateMap.containsKey(dateStr)
                                                                ? BigDecimal.valueOf(finalExchangeRateMap.get(dateStr))
                                                                : fallbackRate;
                                        }

                                        double rateToUse = entity.getDividendRate();
                                        if (rateToUse == 0 && entity.getPaymentDate() != null) {
                                                rateToUse = calculateDividendRate(stock, entity.getDividendAmount(),
                                                                entity.getPaymentDate());
                                        }

                                        BigDecimal dividendAmountKrw = entity.getDividendAmount().multiply(rate);

                                        return StockDividendInfoDto.builder()
                                                        .id(entity.getId())
                                                        .stockCode(stock.getStockCode())
                                                        .stockName(stock.getStockName())
                                                        .recordDate(entity.getRecordDate())
                                                        .paymentDate(entity.getPaymentDate())
                                                        .dividendAmount(entity.getDividendAmount())
                                                        .dividendRate(rateToUse)
                                                        .stockType(stock.getStockType())
                                                        .exchangeRate(rate)
                                                        .dividendAmountKrw(dividendAmountKrw)
                                                        .build();
                                })
                                .distinct()
                                .collect(Collectors.toList());
        }

        public DividendCalendarResponseDto getPortfolioDividendCalendar(
                        DividendCalendarRequestDto request) {
                // 1. 포트폴리오 내 종목 조회 및 수량매핑
                List<PortStock> portStocks = portStockRepository
                                .findByPortfolio_PortId(request.getPortId());

                if (portStocks.isEmpty()) {
                        return DividendCalendarResponseDto.builder()
                                        .dividends(Collections.emptyList())
                                        .totalMonthlyDividend(BigDecimal.ZERO)
                                        .build();
                }

                // 종목코드 -> 수량 Map 생성
                Map<String, Integer> stockQuantityMap = portStocks.stream()
                                .collect(Collectors.toMap(
                                                ps -> ps.getStock().getStockCode(),
                                                PortStock::getQuantity,
                                                (existing, replacement) -> existing // 중복 시 기존 값 유지 (or sum?)
                                ));

                List<String> stockCodes = new ArrayList<>(stockQuantityMap.keySet());

                // 2. 해당 월의 시작일과 종료일 계산
                LocalDate firstDay = LocalDate.of(request.getYear(), request.getMonth(), 1);
                LocalDate lastDay = firstDay.withDayOfMonth(firstDay.lengthOfMonth());

                String recordStart = firstDay.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                String recordEnd = lastDay.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

                String paymentStart = firstDay.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
                String paymentEnd = lastDay.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));

                // 3. DB에서 해당 기간 및 종목들에 해당하는 배당 정보 조회 (배당락일 OR 지급일 기준)
                List<StockDividend> dividends = stockDividendRepository
                                .findDividendsByStockCodesAndDateRange(stockCodes, recordStart, recordEnd, paymentStart,
                                                paymentEnd);

                // 4. 환율 정보 준비 (US 자산용)
                Double latestRate = kisMacroService.getLatestExchangeRate();
                BigDecimal fallbackRate = BigDecimal.valueOf(latestRate != null ? latestRate : 1350.0);

                // 지급일들 수집 (yyyy/MM/dd -> yyyyMMdd 변환 필요)
                Set<String> paymentDates = dividends.stream()
                                .map(StockDividend::getPaymentDate)
                                .filter(d -> d != null && d.length() == 10)
                                .map(d -> d.replace("/", ""))
                                .collect(Collectors.toSet());

                Map<String, Double> finalExchangeRateMap;
                if (!paymentDates.isEmpty()) {
                        String minDate = paymentDates.stream().min(String::compareTo).get();
                        String maxDate = paymentDates.stream().max(String::compareTo).get();
                        finalExchangeRateMap = kisMacroService.getExchangeRateMap(minDate, maxDate);
                } else {
                        finalExchangeRateMap = Collections.emptyMap();
                }

                // 5. DTO 변환 & distinct
                List<StockDividendInfoDto> dividendList = dividends.stream()
                                .map(entity -> {
                                        Stock stock = entity.getStock();
                                        Integer quantity = stockQuantityMap.getOrDefault(stock.getStockCode(), 0);

                                        boolean isUsAsset = stock.getStockType() == Stock.StockType.US_STOCK
                                                        || stock.getStockType() == Stock.StockType.US_ETF;

                                        BigDecimal rate = BigDecimal.ONE;
                                        if (isUsAsset) {
                                                String dateStr = entity.getPaymentDate().replace("/", "");
                                                rate = finalExchangeRateMap.containsKey(dateStr)
                                                                ? BigDecimal.valueOf(finalExchangeRateMap.get(dateStr))
                                                                : fallbackRate;
                                        }

                                        BigDecimal dividendAmountKrw = entity.getDividendAmount().multiply(rate);
                                        BigDecimal totalAmountKrw = dividendAmountKrw
                                                        .multiply(new BigDecimal(quantity));

                                        double rateToUse = entity.getDividendRate();
                                        if (rateToUse == 0 && entity.getPaymentDate() != null) {
                                                rateToUse = calculateDividendRate(stock, entity.getDividendAmount(),
                                                                entity.getPaymentDate());
                                        }

                                        return StockDividendInfoDto.builder()
                                                        .id(entity.getId())
                                                        .stockCode(stock.getStockCode())
                                                        .stockName(stock.getStockName())
                                                        .recordDate(entity.getRecordDate())
                                                        .paymentDate(entity.getPaymentDate())
                                                        .dividendAmount(entity.getDividendAmount())
                                                        .dividendRate(rateToUse)
                                                        .totalExpectedDividend(totalAmountKrw) // 원화로 통일
                                                        .quantity(quantity)
                                                        .stockType(stock.getStockType())
                                                        .exchangeRate(rate)
                                                        .dividendAmountKrw(dividendAmountKrw)
                                                        .build();
                                })
                                .distinct()
                                .collect(Collectors.toList());

                // 6. 월간 총 배당금 합산 (배당 지급일 기준)
                BigDecimal totalMonthlyDividend = dividendList.stream()
                                .filter(dto -> dto.getPaymentDate() != null &&
                                                dto.getPaymentDate().compareTo(paymentStart) >= 0 &&
                                                dto.getPaymentDate().compareTo(paymentEnd) <= 0)
                                .map(StockDividendInfoDto::getTotalExpectedDividend)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                return DividendCalendarResponseDto.builder()
                                .dividends(dividendList)
                                .totalMonthlyDividend(totalMonthlyDividend)
                                .build();
        }

        private double calculateDividendRate(Stock stock, BigDecimal dividendAmount, String paymentDate) {
                try {
                        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd");
                        LocalDate date = LocalDate.parse(paymentDate, formatter);

                        BigDecimal price = BigDecimal.ZERO;
                        boolean isUs = stock.getStockType() == Stock.StockType.US_STOCK
                                        || stock.getStockType() == Stock.StockType.US_ETF;

                        if (isUs) {
                                price = overseasDailyDataRepository.findByStockAndDate(stock, date)
                                                .map(OverseasStockDailyData::getClosingPrice)
                                                .orElse(BigDecimal.ZERO);
                                if (price.compareTo(BigDecimal.ZERO) <= 0) {
                                        price = overseasDailyDataRepository.findFirstByStockOrderByDateDesc(stock)
                                                        .map(OverseasStockDailyData::getClosingPrice)
                                                        .orElse(BigDecimal.ZERO);
                                }
                        } else {
                                price = stockDailyDataRepository.findByStockAndDate(stock, date)
                                                .map(entity -> entity.getClosingPrice())
                                                .orElse(BigDecimal.ZERO);
                                if (price.compareTo(BigDecimal.ZERO) <= 0) {
                                        price = stockDailyDataRepository.findFirstByStockOrderByDateDesc(stock)
                                                        .map(entity -> entity.getClosingPrice())
                                                        .orElse(BigDecimal.ZERO);
                                }
                        }

                        if (price.compareTo(BigDecimal.ZERO) > 0) {
                                return dividendAmount.divide(price, 4, RoundingMode.HALF_UP)
                                                .multiply(new BigDecimal(100))
                                                .doubleValue();
                        }
                } catch (Exception e) {
                        log.warn("Failed to calculate dividend rate for {}: {}", stock.getStockCode(), e.getMessage());
                }
                return 0.0;
        }
}
