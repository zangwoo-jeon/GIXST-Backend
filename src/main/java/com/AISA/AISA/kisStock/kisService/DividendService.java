package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.global.exception.BusinessException;
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
import com.AISA.AISA.kisStock.repository.StockRepository;
import com.AISA.AISA.portfolio.PortfolioStock.PortStock;
import com.AISA.AISA.portfolio.PortfolioStock.PortStockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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

        public List<StockDividendInfoDto> getDividendInfo(
                        String stockCode,
                        String startDate,
                        String endDate) {

                // 1. DB에서 먼저 조회
                List<StockDividend> savedDividends = stockDividendRepository
                                .findByStock_StockCodeAndRecordDateBetweenOrderByRecordDateDesc(stockCode, startDate,
                                                endDate);

                if (!savedDividends.isEmpty()) {
                        // DB에 데이터가 있으면 API 호출 없이 반환 (단, 전체 기간 커버 여부는 간단히 startDate/endDate로 판단하거나,
                        // 일단 데이터가 있으면 갱신하지 않는 정책으로 감. 더 정교하려면 비어있는 구간만 API 호출해야 함.)
                        // 여기서는 "DB에 하나라도 있으면 API 호출 안함"으로 단순화할 수 있으나,
                        // 사용자가 특정 연도 데이터를 원하는데 DB엔 작년것만 있으면 안됨.
                        // 따라서, DB 조회 결과가 요청 기간을 충분히 커버하는지 체크하는게 좋지만,
                        // KIS API 특성상 '결산배당', '중간배당' 등이 섞여서 기간 체크가 모호함.
                        // -> 간단한 전략: DB 조회 결과가 있으면 그걸 반환. (데이터 누락 가능성 존재하나 속도 우선)
                        // -> 더 나은 전략: DB 조회 결과가 비어있지 않으면 그대로 반환 (캐시처럼 사용).
                        // 만약 사용자가 "최신 데이터 갱신"을 원하면 별도 API가 필요할 수 있음.
                        // 우선은 "DB 조회 결과 존재 시 반환"으로 구현.

                        return savedDividends.stream()
                                        .filter(entity -> entity.getDividendAmount().compareTo(BigDecimal.ZERO) > 0)
                                        .map(entity -> StockDividendInfoDto.builder()
                                                        .stockCode(entity.getStock().getStockCode())
                                                        .stockName(entity.getStock().getStockName())
                                                        .recordDate(entity.getRecordDate())
                                                        .paymentDate(entity.getPaymentDate())
                                                        .dividendAmount(entity.getDividendAmount())
                                                        .dividendRate(entity.getDividendRate())
                                                        .build())
                                        .collect(Collectors.toList());
                }

                // 2. DB에 없으면 API 호출
                String accessToken = kisAuthService.getAccessToken();

                Stock stock = stockRepository.findByStockCode(stockCode)
                                .orElseThrow(() -> new BusinessException(KisApiErrorCode.STOCK_NOT_FOUND));

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
                                        return StockDividendInfoDto.builder()
                                                        .stockCode(apiDto.getStockCode())
                                                        .stockName(apiDto.getStockName())
                                                        .recordDate(apiDto.getRecordDate())
                                                        .paymentDate(apiDto.getPaymentDate())
                                                        .dividendAmount(new BigDecimal(apiDto.getDividendAmount()))
                                                        .dividendRate(rate)
                                                        .build();
                                })
                                .collect(Collectors.toList());

                // 3. 주가 정보 조회를 위한 기간 설정 (조회된 배당 내역의 기간을 커버하도록)
                // dtoList가 비어있지 않다고 가정
                if (!dtoList.isEmpty()) {
                        try {
                                String minDate = dtoList.get(dtoList.size() - 1).getRecordDate(); // 오래된 날짜
                                String maxDate = dtoList.get(0).getRecordDate(); // 최근 날짜

                                // 주말 등 고려하여 앞뒤로 여유 기간을 둠
                                LocalDate minLocalDate = LocalDate
                                                .parse(minDate, DateTimeFormatter.ofPattern("yyyyMMdd")).minusDays(7);
                                LocalDate maxLocalDate = LocalDate
                                                .parse(maxDate, DateTimeFormatter.ofPattern("yyyyMMdd")).plusDays(7);

                                String queryStart = minLocalDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                                String queryEnd = maxLocalDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

                                // 일별 주가 조회 (API 호출) - KisStockService에 getStockChart 메서드 활용
                                StockChartResponseDto chartResponse = kisStockService.getStockChart(stockCode,
                                                queryStart, queryEnd, "D");

                                // 날짜별 종가 매핑 (빠른 검색을 위해 Map 사용)
                                TreeMap<String, BigDecimal> priceMap = new java.util.TreeMap<>();
                                if (chartResponse != null && chartResponse.getPriceList() != null) {
                                        for (StockChartPriceDto p : chartResponse.getPriceList()) {
                                                priceMap.put(p.getDate(), new BigDecimal(p.getClosePrice()));
                                        }
                                }

                                // 4. 배당률 재계산 및 DB 저장
                                List<StockDividend> entitiesToSave = new ArrayList<>();

                                for (StockDividendInfoDto dto : dtoList) {
                                        String recordDate = dto.getRecordDate();

                                        // 해당 날짜의 종가 찾기 OR 가장 가까운 과거 날짜 찾기
                                        Entry<String, BigDecimal> entry = priceMap.floorEntry(recordDate);
                                        BigDecimal closePrice = (entry != null) ? entry.getValue() : BigDecimal.ZERO;

                                        if (closePrice.compareTo(BigDecimal.ZERO) > 0) {
                                                // 배당률 = (배당금 / 주가) * 100
                                                double calculatedRate = dto.getDividendAmount()
                                                                .divide(closePrice, 4, RoundingMode.HALF_UP)
                                                                .multiply(new BigDecimal(100))
                                                                .doubleValue();

                                                // DTO 업데이트
                                                dto.setDividendRate(Double
                                                                .parseDouble(String.format("%.2f", calculatedRate)));

                                                // Entity 생성 (중복 체크)
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

                                // 일괄 저장
                                if (!entitiesToSave.isEmpty()) {
                                        stockDividendRepository.saveAll(entitiesToSave);
                                }

                        } catch (Exception e) {
                                log.warn("Failed to update dividend rates with historical prices: {}", e.getMessage());
                                // 실패해도 원래 API 데이터는 반환
                        }
                }

                return dtoList;
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
                                double yield = totalDividend.divide(currentPrice, 4, RoundingMode.HALF_UP)
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

        public DividendDetailDto getDividendDetail(String stockCode) {
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

                // 4. 배당 주기 판단
                String frequency = "기타";
                int count = dividendList.size();
                if (count >= 4)
                        frequency = "분기배당";
                else if (count == 2)
                        frequency = "반기배당";
                else if (count == 1)
                        frequency = "결산배당";
                else if (count == 0)
                        frequency = "배당없음";

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
                                .dividendFrequency(frequency)
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

                // 3. DTO 변환
                return dividends.stream()
                                .map(entity -> StockDividendInfoDto.builder()
                                                .stockCode(entity.getStock().getStockCode())
                                                .stockName(entity.getStock().getStockName())
                                                .recordDate(entity.getRecordDate())
                                                .paymentDate(entity.getPaymentDate())
                                                .dividendAmount(entity.getDividendAmount())
                                                .dividendRate(entity.getDividendRate())
                                                .build())
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

                // 4. DTO 변환 & distinct
                // 4. DTO 변환 & distinct
                List<StockDividendInfoDto> dividendList = dividends.stream()
                                .map(entity -> {
                                        Integer quantity = stockQuantityMap
                                                        .getOrDefault(entity.getStock().getStockCode(), 0);
                                        BigDecimal totalAmount = entity.getDividendAmount()
                                                        .multiply(new BigDecimal(quantity));

                                        return StockDividendInfoDto.builder()
                                                        .stockCode(entity.getStock().getStockCode())
                                                        .stockName(entity.getStock().getStockName())
                                                        .recordDate(entity.getRecordDate())
                                                        .paymentDate(entity.getPaymentDate())
                                                        .dividendAmount(entity.getDividendAmount())
                                                        .dividendRate(entity.getDividendRate())
                                                        .totalExpectedDividend(totalAmount)
                                                        .quantity(quantity)
                                                        .build();
                                })
                                .distinct()
                                .collect(Collectors.toList());

                // 5. 월간 총 배당금 합산 (배당 지급일 기준)
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
}
