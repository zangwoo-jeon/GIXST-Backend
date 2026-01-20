package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.kisStock.Entity.Index.IndexDailyData;
import com.AISA.AISA.kisStock.Entity.Index.OverseasIndexDailyData;
import com.AISA.AISA.kisStock.config.KisApiProperties;
import com.AISA.AISA.kisOverseasStock.config.KisOverseasApiProperties;
import com.AISA.AISA.kisStock.dto.Index.IndexChartInfoDto;
import com.AISA.AISA.kisStock.dto.Index.IndexChartPriceDto;
import com.AISA.AISA.kisStock.dto.Index.IndexChartResponseDto;
import com.AISA.AISA.kisStock.dto.Index.KisIndexChartApiResponse;
import com.AISA.AISA.kisStock.enums.ExchangeRateCode;
import com.AISA.AISA.kisStock.exception.KisApiErrorCode;
import com.AISA.AISA.kisStock.repository.IndexDailyDataRepository;
import com.AISA.AISA.kisStock.enums.OverseasIndex;
import com.AISA.AISA.kisStock.repository.OverseasIndexDailyDataRepository;
import com.AISA.AISA.kisStock.dto.Macro.KisOverseasDailyPriceDto;
import com.AISA.AISA.kisStock.dto.Macro.KisOverseasDailyPriceResponseDto;
import com.AISA.AISA.kisStock.dto.Macro.KisOverseasIndexBasicInfoDto;
import com.AISA.AISA.kisStock.dto.Index.OverseasIndexStatusDto;
import com.AISA.AISA.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.AISA.AISA.portfolio.macro.dto.MacroIndicatorDto;
import java.util.Collections;
import java.util.Map;
import java.math.RoundingMode;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class KisIndexService {

    private final WebClient webClient;
    private final KisApiProperties kisApiProperties;
    private final KisOverseasApiProperties overseasApiProperties; // Inject
    private final IndexDailyDataRepository indexDailyDataRepository;
    private final OverseasIndexDailyDataRepository overseasIndexDailyDataRepository;
    private final KisMacroService kisMacroService;
    private final KisApiClient kisApiClient;

    @Transactional
    public void fetchAndSaveHistoricalData(String marketCode, String startDateStr) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        LocalDate targetStartDate = LocalDate.parse(startDateStr, formatter);
        LocalDate currentDate = LocalDate.now();

        log.info("Starting historical data fetch for {} from {} to {}", marketCode, currentDate,
                targetStartDate);

        int consecutiveFailures = 0;
        final int MAX_CONSECUTIVE_FAILURES = 5;

        while (currentDate.isAfter(targetStartDate)) {
            String currentDateStr = currentDate.format(formatter);
            log.info("Fetching data for date: {}", currentDateStr);

            try {
                // 1. API 호출 및 데이터 가져오기
                IndexChartResponseDto response = fetchIndexChartFromApi(marketCode, currentDateStr,
                        "D");

                if (response.getPriceList() == null || response.getPriceList().isEmpty()) {
                    log.warn("No data returned for date: {}. Retrying with previous day.", currentDateStr);
                    currentDate = currentDate.minusDays(1);
                    consecutiveFailures++;
                    if (consecutiveFailures > MAX_CONSECUTIVE_FAILURES) {
                        log.error("Too many consecutive failures ({}). Stopping.", consecutiveFailures);
                        break;
                    }
                    continue;
                }

                // Reset failure counter on success
                consecutiveFailures = 0;

                // 2. 데이터 저장
                for (IndexChartPriceDto priceDto : response.getPriceList()) {
                    LocalDate parsedDate = LocalDate.parse(priceDto.getDate(), formatter);

                    // 이미 저장된 데이터가 있으면 건너뛰기 (옵션)
                    if (indexDailyDataRepository.findByMarketNameAndDate(marketCode, parsedDate)
                            .isPresent()) {
                        continue;
                    }

                    IndexDailyData entity = IndexDailyData.builder()
                            .marketName(marketCode)
                            .date(parsedDate)
                            .closingPrice(new BigDecimal(priceDto.getPrice()))
                            .openingPrice(new BigDecimal(priceDto.getOpenPrice()))
                            .highPrice(new BigDecimal(priceDto.getHighPrice()))
                            .lowPrice(new BigDecimal(priceDto.getLowPrice()))
                            .priceChange(BigDecimal.ZERO)
                            .changeRate(0.0)
                            .volume(BigDecimal.ZERO)
                            .build();

                    indexDailyDataRepository.save(entity);
                }

                // 3. 다음 호출을 위해 날짜 갱신 (가장 과거 날짜 - 1일)
                String oldestDateStr = response.getPriceList().get(response.getPriceList().size() - 1)
                        .getDate();
                LocalDate oldestDate = LocalDate.parse(oldestDateStr, formatter);

                if (!oldestDate.isBefore(currentDate)) {
                    // 무한 루프 방지: API가 계속 같은 날짜를 반환하거나 이상할 경우
                    log.warn("Oldest date {} is not before current date {}. Stopping to prevent infinite loop.",
                            oldestDate, currentDate);
                    break;
                }

                currentDate = oldestDate.minusDays(1);

                // API 호출 제한 고려 (0.1초 대기)
                Thread.sleep(100);

            } catch (Exception e) {
                log.error("Error fetching historical data for {}: {}", currentDateStr, e.getMessage());
                // Instead of breaking, try previous day
                currentDate = currentDate.minusDays(1);
                consecutiveFailures++;
                if (consecutiveFailures > MAX_CONSECUTIVE_FAILURES) {
                    log.error("Too many consecutive failures ({}). Stopping.", consecutiveFailures);
                    break;
                }
            }
        }
        log.info("Finished historical data fetch for {}", marketCode);
    }

    @Transactional
    public void saveIndexDailyData(String marketCode, String date, String dateType) {
        IndexChartResponseDto response = fetchIndexChartFromApi(marketCode, date, dateType);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");

        for (IndexChartPriceDto priceDto : response.getPriceList()) {
            LocalDate parsedDate = LocalDate.parse(priceDto.getDate(), formatter);

            if (indexDailyDataRepository.findByMarketNameAndDate(marketCode, parsedDate).isPresent()) {
                continue;
            }

            IndexDailyData entity = IndexDailyData.builder()
                    .marketName(marketCode)
                    .date(parsedDate)
                    .closingPrice(new BigDecimal(priceDto.getPrice()))
                    .openingPrice(new BigDecimal(priceDto.getOpenPrice()))
                    .highPrice(new BigDecimal(priceDto.getHighPrice()))
                    .lowPrice(new BigDecimal(priceDto.getLowPrice()))
                    .priceChange(BigDecimal.ZERO) // DTO에 없으면 0 또는 계산
                    .changeRate(0.0) // DTO에 없으면 0 또는 계산
                    .volume(BigDecimal.ZERO) // DTO에 volume이 없다면 0 처리, 있다면 new
                    // BigDecimal(priceDto.getVolume())
                    .build();

            indexDailyDataRepository.save(entity);
        }
    }

    // DB에서 조회하는 메서드 (Controller에서 사용)
    @Cacheable(value = "indexChart", key = "#marketCode + '-' + #startDate + '-' + #endDate + '-' + #dateType")
    public IndexChartResponseDto getIndexChart(String marketCode, String startDate, String endDate,
            String dateType) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        LocalDate targetEndDate = LocalDate.parse(endDate, formatter);
        LocalDate targetStartDate = LocalDate.parse(startDate, formatter);

        try {
            // 1. 실시간 데이터(API) 시도 (endDate 기준 100건)
            IndexChartResponseDto apiResponse = fetchIndexChartFromApi(marketCode, endDate, dateType);

            // API 데이터가 없으면 DB만 조회
            if (apiResponse.getPriceList() == null || apiResponse.getPriceList().isEmpty()) {
                throw new BusinessException(KisApiErrorCode.INDEX_FETCH_FAILED);
            }

            // 2. API 데이터 필터링 (startDate보다 이후인 데이터만 사용)
            List<IndexChartPriceDto> filteredApiList = apiResponse.getPriceList().stream()
                    .filter(dto -> {
                        LocalDate dtoDate = LocalDate.parse(dto.getDate(), formatter);
                        return !dtoDate.isBefore(targetStartDate)
                                && !dtoDate.isAfter(targetEndDate);
                    })
                    .toList();

            // 3. 부족한 과거 데이터 DB 조회
            List<IndexChartPriceDto> dbPriceList = new ArrayList<>();

            // API 데이터가 있고, 가장 오래된 날짜가 startDate보다 아직 미래라면 -> 그 사이 공백을 DB로 채움
            if (!filteredApiList.isEmpty()) {
                String oldestApiDateStr = filteredApiList.get(filteredApiList.size() - 1).getDate();
                LocalDate oldestApiDate = LocalDate.parse(oldestApiDateStr, formatter);

                // API 데이터에 이미 포함된 기간(월/주) 수집
                Set<String> coveredPeriods = getCoveredPeriods(filteredApiList, dateType);

                if (oldestApiDate.isAfter(targetStartDate)) {
                    List<IndexDailyData> pastDataList = indexDailyDataRepository
                            .findAllByMarketNameAndDateBetweenOrderByDateDesc(
                                    marketCode, targetStartDate,
                                    oldestApiDate.minusDays(1));

                    // dateType에 따른 필터링 적용 (M: 월별, W: 주별) + 중복 제외
                    List<IndexDailyData> filteredPastDataList = filterDataByDateType(pastDataList, dateType,
                            coveredPeriods);

                    dbPriceList = filteredPastDataList.stream()
                            .map(this::convertToDto)
                            .collect(Collectors.toList());
                }
            } else {
                // API 데이터가 startDate 범위에 하나도 없는 경우
                String apiOldestRaw = apiResponse.getPriceList()
                        .get(apiResponse.getPriceList().size() - 1).getDate();
                LocalDate apiOldestDate = LocalDate.parse(apiOldestRaw, formatter);

                // API 원본 데이터 기준으로 커버된 기간 확인 (filteredApiList는 비어있을 수 있으므로)
                Set<String> coveredPeriods = getCoveredPeriods(apiResponse.getPriceList(), dateType);

                if (apiOldestDate.isAfter(targetStartDate)) {
                    List<IndexDailyData> pastDataList = indexDailyDataRepository
                            .findAllByMarketNameAndDateBetweenOrderByDateDesc(
                                    marketCode, targetStartDate,
                                    apiOldestDate.minusDays(1));

                    // dateType에 따른 필터링 적용
                    List<IndexDailyData> filteredPastDataList = filterDataByDateType(pastDataList, dateType,
                            coveredPeriods);

                    dbPriceList = filteredPastDataList.stream()
                            .map(this::convertToDto)
                            .collect(Collectors.toList());
                }
            }

            // 4. 데이터 병합
            List<IndexChartPriceDto> mergedList = new ArrayList<>(filteredApiList);
            mergedList.addAll(dbPriceList);

            return IndexChartResponseDto.builder()
                    .info(apiResponse.getInfo())
                    .priceList(mergedList)
                    .build();

        } catch (Exception e) {
            log.warn("Failed to fetch from API, falling back to DB: {}", e.getMessage());

            // API 실패 시 DB에서 전체 범위 조회
            List<IndexDailyData> entityList = indexDailyDataRepository
                    .findAllByMarketNameAndDateBetweenOrderByDateDesc(marketCode, targetStartDate,
                            targetEndDate);

            if (entityList.isEmpty()) {
                throw new BusinessException(KisApiErrorCode.INDEX_FETCH_FAILED);
            }

            IndexDailyData todayData = entityList.get(0);

            IndexChartInfoDto chartInfoDto = IndexChartInfoDto.builder()
                    .marketName(marketCode.toUpperCase())
                    .currentIndices(todayData.getClosingPrice().toString())
                    .priceChange(todayData.getPriceChange().toString())
                    .changeRate(todayData.getChangeRate().toString())
                    .build();

            List<IndexChartPriceDto> chartPriceList = entityList.stream()
                    .map(this::convertToDto)
                    .collect(Collectors.toList());

            return IndexChartResponseDto.builder()
                    .info(chartInfoDto)
                    .priceList(chartPriceList)
                    .build();
        }
    }

    public IndexChartInfoDto getIndexStatus(String marketCode) {
        // 오늘 날짜 기준으로 API 호출하여 최신 상태 정보만 가져옴
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        // API 호출 시 dateType은 'D'로 고정
        IndexChartResponseDto response = fetchIndexChartFromApi(marketCode, today, "D");
        return response.getInfo();
    }

    private IndexChartPriceDto convertToDto(IndexDailyData entity) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        return IndexChartPriceDto.builder()
                .date(entity.getDate().format(formatter))
                .price(entity.getClosingPrice().toString())
                .openPrice(entity.getOpeningPrice().toString())
                .highPrice(entity.getHighPrice().toString())
                .lowPrice(entity.getLowPrice().toString())
                .volume(entity.getVolume() != null ? entity.getVolume().toString() : "0")
                .build();
    }

    // 실제 API 호출 메서드 (Private or Internal use)
    private IndexChartResponseDto fetchIndexChartFromApi(String marketCode, String date, String dateType) {
        String fidInputIscd = switch (marketCode.toUpperCase()) {
            case "KOSPI" -> "0001";
            case "KOSDAQ" -> "1001";
            default -> throw new BusinessException(KisApiErrorCode.INVALID_MARKET_CODE);
        };

        KisIndexChartApiResponse apiResponse = kisApiClient.fetch(token -> {
            String authorizationHeader = token.startsWith("Bearer ") ? token : "Bearer " + token;
            return webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(kisApiProperties.getIndexChartUrl())
                            .queryParam("FID_PERIOD_DIV_CODE", dateType)
                            .queryParam("FID_COND_MRKT_DIV_CODE", "U")
                            .queryParam("FID_INPUT_ISCD", fidInputIscd)
                            .queryParam("FID_INPUT_DATE_1", date)
                            .build())
                    .header("authorization", authorizationHeader)
                    .header("appkey", kisApiProperties.getAppkey())
                    .header("appsecret", kisApiProperties.getAppsecret())
                    .header("tr_id", "FHPUP02120000")
                    .header("custtype", "P");
        }, KisIndexChartApiResponse.class);

        // 응답 검증 강화 (output1이 null이거나 비어있으면 실패로 간주)
        if (apiResponse == null || !"0".equals(apiResponse.getRtCd())) { // Check rtCd for success
            log.error("API Response is valid but list is empty. Msg: {}",
                    apiResponse != null ? apiResponse.getMsg1() : "null response");
            throw new BusinessException(KisApiErrorCode.INDEX_FETCH_FAILED);
        }

        IndexChartInfoDto chartInfoDto = IndexChartInfoDto.builder()
                .marketName(marketCode.toUpperCase())
                .currentIndices(apiResponse.getTodayInfo().getCurrentIndices())
                .priceChange(apiResponse.getTodayInfo().getPriceChange())
                .changeRate(apiResponse.getTodayInfo().getChangeRate())
                .build();

        List<IndexChartPriceDto> chartPriceList = apiResponse.getDateInfoList().stream()
                .map(apiPrice -> IndexChartPriceDto.builder()
                        .date(apiPrice.getDate())
                        .price(apiPrice.getPrice())
                        .openPrice(apiPrice.getOpenPrice())
                        .highPrice(apiPrice.getHighPrice())
                        .lowPrice(apiPrice.getLowPrice())
                        .build())
                .collect(Collectors.toList());

        return IndexChartResponseDto.builder()
                .info(chartInfoDto)
                .priceList(chartPriceList)
                .build();
    }

    // 변경: 반환 타입을 List<DTO>로 변경하지 않고, 아래에서 IndexChartPriceDto 등을 반환하도록 수정 예정이나
    // 메서드 시그니처 변경 필요. 일단 내부 로직부터 변경.
    // 하지만 Controller에서 List<MacroIndicatorDto>를 받고 있으므로, Controller 수정과 맞물려야 함.
    // 일단 여기서는 IndexChartPriceDto (OHLC) 리스트를 반환하도록 변경하고 Controller도 수정하는게 맞음.
    // 기존 DTO 재활용: IndexChartPriceDto
    @Cacheable(value = "overseasIndex", key = "#index.name() + '-' + #startDate + '-' + #endDate")
    public List<IndexChartPriceDto> fetchOverseasIndex(OverseasIndex index, String startDate, String endDate) {
        // 1. Fetch Index Data
        List<IndexChartPriceDto> indexList = fetchOverseasIndexData(index.getSymbol(), "N", index.getSymbol(),
                startDate, endDate);

        // 2. Determine Currency Code
        String currencyCode = switch (index) {
            case NASDAQ, SP500 -> ExchangeRateCode.USD.getSymbol();
            case NIKKEI -> ExchangeRateCode.JPY.getSymbol();
            case HANGSENG -> ExchangeRateCode.HKD_KRW.getSymbol();
            case EUROSTOXX50 -> ExchangeRateCode.EUR_KRW.getSymbol();
            default -> ExchangeRateCode.USD.getSymbol();
        };

        try {
            // 3. Fetch Exchange Rate Data
            List<MacroIndicatorDto> exchangeRateList = kisMacroService.fetchExchangeRate(currencyCode, startDate,
                    endDate);
            Map<String, String> exchangeRateMap = exchangeRateList.stream()
                    .collect(Collectors.toMap(MacroIndicatorDto::getDate, MacroIndicatorDto::getValue, (v1, v2) -> v1));

            // 4. Map Exchange Rate to Index Data
            for (IndexChartPriceDto dto : indexList) {
                if (exchangeRateMap.containsKey(dto.getDate())) {
                    dto.setExchangeRate(exchangeRateMap.get(dto.getDate()));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch exchange rate for overseas index {}: {}", index, e.getMessage());
            // Fail silently for exchange rate, main data is still returned
        }

        return indexList;
    }

    public OverseasIndexStatusDto getOverseasIndexStatus(OverseasIndex index) {
        KisOverseasIndexBasicInfoDto latest = fetchOverseasIndexBasicInfo(index.getSymbol());

        return OverseasIndexStatusDto.builder()
                .date(latest.getDate())
                .price(latest.getPrice())
                .priceChange(latest.getPriceChange())
                .changeRate(latest.getChangeRate())
                .build();
    }

    @Transactional
    public void fetchAndSaveOverseasIndex(OverseasIndex index, String startDateStr, String endDateStr) {
        fetchAndSaveOverseasIndexData(index.getSymbol(), "N", index.getSymbol(), startDateStr, endDateStr);
    }

    private List<IndexChartPriceDto> fetchOverseasIndexData(String itemCode, String marketDivCode,
            String symbol, String startDate, String endDate) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        LocalDate start = LocalDate.parse(startDate, formatter);
        LocalDate end = LocalDate.parse(endDate, formatter);

        // 1. Try to fetch from DB
        List<OverseasIndexDailyData> dbData = overseasIndexDailyDataRepository
                .findAllByMarketNameAndDateBetweenOrderByDateAsc(itemCode, start, end); // itemCode stores symbol/market
                                                                                        // name

        if (!dbData.isEmpty()) {
            return dbData.stream()
                    .map(this::convertOverseasEntityToDto)
                    .collect(Collectors.toList());
        }

        // 2. If DB is empty, fetch from API and save
        fetchAndSaveOverseasIndexData(itemCode, marketDivCode, symbol, startDate, endDate);

        // 3. Re-fetch from DB
        dbData = overseasIndexDailyDataRepository.findAllByMarketNameAndDateBetweenOrderByDateAsc(
                itemCode, start, end);

        return dbData.stream()
                .map(this::convertOverseasEntityToDto)
                .collect(Collectors.toList());
    }

    private IndexChartPriceDto convertOverseasEntityToDto(
            OverseasIndexDailyData entity) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        return IndexChartPriceDto.builder()
                .date(entity.getDate().format(formatter))
                .price(entity.getClosingPrice().toString())
                .openPrice(entity.getOpeningPrice().toString())
                .highPrice(entity.getHighPrice().toString())
                .lowPrice(entity.getLowPrice().toString())
                .volume(entity.getVolume() != null ? entity.getVolume().toString() : "0")
                .build();
    }

    private void fetchAndSaveOverseasIndexData(String itemCode, String marketDivCode, String symbol,
            String startDateStr, String endDateStr) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        LocalDate targetStartDate = LocalDate.parse(startDateStr, formatter);
        LocalDate currentDate = LocalDate.parse(endDateStr, formatter);

        log.info("Starting bulk fetch for Oversea Index {} from {} to {}", symbol, startDateStr, endDateStr);

        while (!currentDate.isBefore(targetStartDate)) {
            String currentDateStr = currentDate.format(formatter);
            LocalDate queryStartDate = currentDate.minusDays(99); // 100 days limit
            if (queryStartDate.isBefore(targetStartDate)) {
                queryStartDate = targetStartDate;
            }
            String queryStartDateStr = queryStartDate.format(formatter);

            log.info("Fetching data from {} to {}", queryStartDateStr, currentDateStr);

            try {
                List<KisOverseasDailyPriceDto> apiList = fetchOverseasFromApi(marketDivCode, symbol, queryStartDateStr,
                        currentDateStr);

                if (apiList != null && !apiList.isEmpty()) {
                    for (KisOverseasDailyPriceDto dto : apiList) {
                        LocalDate date = LocalDate.parse(dto.getDate(), formatter);

                        boolean exists = overseasIndexDailyDataRepository
                                .findByMarketNameAndDate(itemCode, date)
                                .isPresent();

                        if (!exists) {
                            OverseasIndexDailyData entity = OverseasIndexDailyData
                                    .builder()
                                    .marketName(itemCode)
                                    .date(date)
                                    .closingPrice(new BigDecimal(dto.getClosePrice()))
                                    .openingPrice(new BigDecimal(dto.getOpenPrice()))
                                    .highPrice(new BigDecimal(dto.getHighPrice()))
                                    .lowPrice(new BigDecimal(dto.getLowPrice()))
                                    .volume(new BigDecimal(dto.getVolume()))
                                    .priceChange(BigDecimal.ZERO) // API doesn't provide this in list
                                    .changeRate(0.0) // API doesn't provide this in list
                                    .build();
                            overseasIndexDailyDataRepository.save(entity);
                        }
                    }
                }

                // Move back
                currentDate = queryStartDate.minusDays(1);
                Thread.sleep(100); // Rate limit

            } catch (Exception e) {
                log.error("Error fetching overseas index data: {}", e.getMessage());
                break; // Stop on error
            }
        }
        log.info("Finished bulk fetch.");
    }

    private List<KisOverseasDailyPriceDto> fetchOverseasFromApi(String marketDivCode, String symbol, String startDate,
            String endDate) {
        KisOverseasDailyPriceResponseDto response = kisApiClient.fetch(token -> webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(overseasApiProperties.getOverseaUrl())
                        .queryParam("FID_COND_MRKT_DIV_CODE", marketDivCode)
                        .queryParam("FID_INPUT_ISCD", symbol)
                        .queryParam("FID_INPUT_DATE_1", startDate)
                        .queryParam("FID_INPUT_DATE_2", endDate)
                        .queryParam("FID_PERIOD_DIV_CODE", "D")
                        .build())
                .header("authorization", token)
                .header("appkey", kisApiProperties.getAppkey())
                .header("appsecret", kisApiProperties.getAppsecret())
                .header("tr_id", "FHKST03030100")
                .header("custtype", "P"), KisOverseasDailyPriceResponseDto.class);

        if (response == null || !"0".equals(response.getReturnCode())) {
            log.error("KIS API Error: RtCd={}, Msg={}",
                    response != null ? response.getReturnCode() : "null",
                    response != null ? response.getMessage() : "null response");
            throw new BusinessException(KisApiErrorCode.STOCK_PRICE_FETCH_FAILED);
        }

        return response.getDailyPriceList();
    }

    private KisOverseasIndexBasicInfoDto fetchOverseasIndexBasicInfo(String symbol) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        String today = LocalDate.now().format(formatter);

        KisOverseasDailyPriceResponseDto response = kisApiClient.fetch(token -> webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(overseasApiProperties.getOverseaUrl()) // Use the same URL as history fetch
                                                                     // (FHKST03030100)
                        .queryParam("FID_COND_MRKT_DIV_CODE", "N")
                        .queryParam("FID_INPUT_ISCD", symbol)
                        .queryParam("FID_INPUT_DATE_1", today)
                        .queryParam("FID_INPUT_DATE_2", today)
                        .queryParam("FID_PERIOD_DIV_CODE", "D")
                        .build())
                .header("authorization", token)
                .header("appkey", kisApiProperties.getAppkey())
                .header("appsecret", kisApiProperties.getAppsecret())
                .header("tr_id", "FHKST03030100") // Use the correct TR ID
                .header("custtype", "P"), KisOverseasDailyPriceResponseDto.class);

        if (response == null || !"0".equals(response.getReturnCode())) {
            log.error("KIS API Error: RtCd={}, Msg={}",
                    response != null ? response.getReturnCode() : "null",
                    response != null ? response.getMessage() : "null response");
            throw new BusinessException(KisApiErrorCode.STOCK_PRICE_FETCH_FAILED);
        }

        if (response.getOutput1() == null) {
            throw new BusinessException(KisApiErrorCode.STOCK_PRICE_FETCH_FAILED);
        }

        // Output1 might not have date, so we can inject it or use what's available.
        if (response.getOutput1().getDate() == null && response.getDailyPriceList() != null
                && !response.getDailyPriceList().isEmpty()) {
            // If output1 doesn't have date, try to get from output2(daily list) first item
            response.getOutput1().setDate(response.getDailyPriceList().get(0).getDate());
        } else if (response.getOutput1().getDate() == null) {
            response.getOutput1().setDate(today);
        }

        return response.getOutput1();
    }

    private Set<String> getCoveredPeriods(List<IndexChartPriceDto> dtoList, String dateType) {
        Set<String> periods = new java.util.HashSet<>();
        if (dtoList == null || dtoList.isEmpty()) {
            return periods;
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        java.time.temporal.WeekFields weekFields = java.time.temporal.WeekFields.ISO;

        for (IndexChartPriceDto dto : dtoList) {
            LocalDate date = LocalDate.parse(dto.getDate(), formatter);
            if ("M".equalsIgnoreCase(dateType)) {
                periods.add(date.format(DateTimeFormatter.ofPattern("yyyyMM")));
            } else if ("W".equalsIgnoreCase(dateType)) {
                int year = date.get(weekFields.weekBasedYear());
                int week = date.get(weekFields.weekOfWeekBasedYear());
                periods.add(year + "-" + week);
            }
        }
        return periods;
    }

    // dateType에 따라 데이터를 필터링하는 헬퍼 메서드 (중복 제외 포함)
    private List<IndexDailyData> filterDataByDateType(List<IndexDailyData> dataList, String dateType,
            Set<String> excludedPeriods) {
        if (dataList == null || dataList.isEmpty()) {
            return new ArrayList<>();
        }

        // 일별(D)이면 필터링 없이 그대로 반환
        if ("D".equalsIgnoreCase(dateType)) {
            return dataList;
        }

        List<IndexDailyData> result = new ArrayList<>();

        // 월별(M) 처리
        if ("M".equalsIgnoreCase(dateType)) {
            String lastMonth = null;
            for (IndexDailyData data : dataList) {
                String currentMonth = data.getDate().format(DateTimeFormatter.ofPattern("yyyyMM"));

                // 제외해야 할 월이면 건너뜀
                if (excludedPeriods != null && excludedPeriods.contains(currentMonth)) {
                    continue;
                }

                if (!currentMonth.equals(lastMonth)) {
                    result.add(data);
                    lastMonth = currentMonth;
                }
            }
            return result;
        }

        // 주별(W) 처리
        if ("W".equalsIgnoreCase(dateType)) {
            java.time.temporal.WeekFields weekFields = java.time.temporal.WeekFields.ISO;
            String lastWeek = null;

            for (IndexDailyData data : dataList) {
                int year = data.getDate().get(weekFields.weekBasedYear());
                int week = data.getDate().get(weekFields.weekOfWeekBasedYear());
                String currentWeek = year + "-" + week;

                // 제외해야 할 주차면 건너뜀
                if (excludedPeriods != null && excludedPeriods.contains(currentWeek)) {
                    continue;
                }

                if (!currentWeek.equals(lastWeek)) {
                    result.add(data);
                    lastWeek = currentWeek;
                }
            }
            return result;
        }

        return dataList;
    }

    @Cacheable(value = "kospiUsdRatio", key = "#startDate + '-' + #endDate")
    public List<IndexChartPriceDto> getKospiUsdRatio(String startDate, String endDate) {
        // 1. Fetch KOSPI Data
        IndexChartResponseDto kospiData = getIndexChart("KOSPI", startDate, endDate, "D");
        Map<String, IndexChartPriceDto> kospiMap = kospiData.getPriceList().stream()
                .collect(Collectors.toMap(
                        IndexChartPriceDto::getDate,
                        dto -> dto));

        // 2. Fetch Exchange Rate Data (from KIS API)
        List<MacroIndicatorDto> exchangeRateData = kisMacroService.fetchExchangeRate("FX@KRW", startDate, endDate);
        Map<String, BigDecimal> exchangeRateMap = exchangeRateData.stream()
                .collect(Collectors.toMap(
                        MacroIndicatorDto::getDate,
                        dto -> new BigDecimal(dto.getValue())));

        // 3. Calculate Ratio
        List<IndexChartPriceDto> ratioList = new ArrayList<>();
        List<String> sortedDates = new ArrayList<>(kospiMap.keySet());
        Collections.sort(sortedDates);

        for (String date : sortedDates) {
            if (exchangeRateMap.containsKey(date)) {
                IndexChartPriceDto kospiDto = kospiMap.get(date);
                BigDecimal exchangeRate = exchangeRateMap.get(date);

                if (exchangeRate.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal divisor = exchangeRate.divide(new BigDecimal(1000), 4, RoundingMode.HALF_UP);

                    // Formula: KOSPI / (ExchangeRate / 1000)
                    BigDecimal open = new BigDecimal(kospiDto.getOpenPrice()).divide(divisor, 2, RoundingMode.HALF_UP);
                    BigDecimal high = new BigDecimal(kospiDto.getHighPrice()).divide(divisor, 2, RoundingMode.HALF_UP);
                    BigDecimal low = new BigDecimal(kospiDto.getLowPrice()).divide(divisor, 2, RoundingMode.HALF_UP);
                    BigDecimal close = new BigDecimal(kospiDto.getPrice()).divide(divisor, 2, RoundingMode.HALF_UP);

                    ratioList.add(IndexChartPriceDto.builder()
                            .date(date)
                            .price(close.toString())
                            .openPrice(open.toString())
                            .highPrice(high.toString())
                            .lowPrice(low.toString())
                            .volume(kospiDto.getVolume())
                            .exchangeRate(exchangeRate.toString())
                            .build());
                }
            }
        }
        return ratioList;
    }

    @Cacheable(value = "kosdaqUsdRatio", key = "#startDate + '-' + #endDate")
    public List<IndexChartPriceDto> getKosdaqUsdRatio(String startDate, String endDate) {
        // 1. Fetch KOSDAQ Data
        IndexChartResponseDto kosdaqData = getIndexChart("KOSDAQ", startDate, endDate, "D");
        Map<String, IndexChartPriceDto> kosdaqMap = kosdaqData.getPriceList().stream()
                .collect(Collectors.toMap(
                        IndexChartPriceDto::getDate,
                        dto -> dto));

        // 2. Fetch Exchange Rate Data (from KIS API)
        List<MacroIndicatorDto> exchangeRateData = kisMacroService.fetchExchangeRate("FX@KRW", startDate, endDate);
        Map<String, BigDecimal> exchangeRateMap = exchangeRateData.stream()
                .collect(Collectors.toMap(
                        MacroIndicatorDto::getDate,
                        dto -> new BigDecimal(dto.getValue())));

        // 3. Calculate Ratio
        List<IndexChartPriceDto> ratioList = new ArrayList<>();
        List<String> sortedDates = new ArrayList<>(kosdaqMap.keySet());
        Collections.sort(sortedDates);

        for (String date : sortedDates) {
            if (exchangeRateMap.containsKey(date)) {
                IndexChartPriceDto kosdaqDto = kosdaqMap.get(date);
                BigDecimal exchangeRate = exchangeRateMap.get(date);

                if (exchangeRate.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal divisor = exchangeRate.divide(new BigDecimal(1000), 4, RoundingMode.HALF_UP);

                    // Formula: KOSDAQ / (ExchangeRate / 1000)
                    BigDecimal open = new BigDecimal(kosdaqDto.getOpenPrice()).divide(divisor, 2, RoundingMode.HALF_UP);
                    BigDecimal high = new BigDecimal(kosdaqDto.getHighPrice()).divide(divisor, 2, RoundingMode.HALF_UP);
                    BigDecimal low = new BigDecimal(kosdaqDto.getLowPrice()).divide(divisor, 2, RoundingMode.HALF_UP);
                    BigDecimal close = new BigDecimal(kosdaqDto.getPrice()).divide(divisor, 2, RoundingMode.HALF_UP);

                    ratioList.add(IndexChartPriceDto.builder()
                            .date(date)
                            .price(close.toString())
                            .openPrice(open.toString())
                            .highPrice(high.toString())
                            .lowPrice(low.toString())
                            .volume(kosdaqDto.getVolume())
                            .exchangeRate(exchangeRate.toString())
                            .build());
                }
            }
        }
        return ratioList;
    }
}
