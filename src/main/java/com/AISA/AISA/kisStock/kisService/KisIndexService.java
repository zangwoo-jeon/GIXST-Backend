package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.kisStock.Entity.Index.IndexDailyData;
import com.AISA.AISA.kisStock.config.KisApiProperties;
import com.AISA.AISA.kisStock.dto.Index.IndexChartInfoDto;
import com.AISA.AISA.kisStock.dto.Index.IndexChartPriceDto;
import com.AISA.AISA.kisStock.dto.Index.IndexChartResponseDto;
import com.AISA.AISA.kisStock.dto.Index.KisIndexChartApiResponse;
import com.AISA.AISA.kisStock.exception.KisApiErrorCode;
import com.AISA.AISA.kisStock.kisService.Auth.KisAuthService;
import com.AISA.AISA.kisStock.repository.IndexDailyDataRepository;
import com.AISA.AISA.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class KisIndexService {

    private final WebClient webClient;
    private final KisAuthService kisAuthService;
    private final KisApiProperties kisApiProperties;
    private final IndexDailyDataRepository indexDailyDataRepository;

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

                if (oldestApiDate.isAfter(targetStartDate)) {
                    List<IndexDailyData> pastDataList = indexDailyDataRepository
                            .findAllByMarketNameAndDateBetweenOrderByDateDesc(
                                    marketCode, targetStartDate,
                                    oldestApiDate.minusDays(1));

                    dbPriceList = pastDataList.stream()
                            .map(this::convertToDto)
                            .collect(Collectors.toList());
                }
            } else {
                // API 데이터가 startDate 범위에 하나도 없으면 (API가 너무 미래 데이터만 줬거나 등)
                // DB에서 전체 범위 조회 시도 (API가 준 데이터의 가장 오래된 날짜보다 더 과거를 요청했을 수 있음)
                // 하지만 fetchIndexChartFromApi는 endDate 기준 100개를 주므로,
                // endDate가 startDate보다 훨씬 미래라면 API 데이터가 startDate를 커버하지 못할 수 있음.
                // 이 경우 API 데이터 전체(100개) + 그 이전 DB 데이터를 합쳐야 하는데,
                // 여기서는 단순하게 "API가 커버하지 못하는 영역"을 DB에서 가져오는 로직으로 처리

                // API가 반환한 가장 오래된 날짜 확인
                String apiOldestRaw = apiResponse.getPriceList()
                        .get(apiResponse.getPriceList().size() - 1).getDate();
                LocalDate apiOldestDate = LocalDate.parse(apiOldestRaw, formatter);

                if (apiOldestDate.isAfter(targetStartDate)) {
                    List<IndexDailyData> pastDataList = indexDailyDataRepository
                            .findAllByMarketNameAndDateBetweenOrderByDateDesc(
                                    marketCode, targetStartDate,
                                    apiOldestDate.minusDays(1));
                    dbPriceList = pastDataList.stream()
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
                .build();
    }

    // 실제 API 호출 메서드 (Private or Internal use)
    private IndexChartResponseDto fetchIndexChartFromApi(String marketCode, String date, String dateType) {
        // 1. 토큰 처리 (Bearer가 이미 붙어있는지 확인 필요)
        String accessToken = kisAuthService.getAccessToken();
        String authorizationHeader = accessToken.startsWith("Bearer ") ? accessToken : "Bearer " + accessToken;

        String fidInputIscd = switch (marketCode.toUpperCase()) {
            case "KOSPI" -> "0001";
            case "KOSDAQ" -> "1001";
            default -> throw new BusinessException(KisApiErrorCode.INVALID_MARKET_CODE);
        };

        KisIndexChartApiResponse apiResponse = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(kisApiProperties.getIndexChartUrl()) // url 확인:
                        // /uapi/domestic-stock/v1/quotations/inquire-index-daily-price
                        .queryParam("FID_PERIOD_DIV_CODE", dateType) // D : 일별, W : 주별, M : 월별
                        .queryParam("FID_COND_MRKT_DIV_CODE", "U")
                        .queryParam("FID_INPUT_ISCD", fidInputIscd) // KOSPI
                        .queryParam("FID_INPUT_DATE_1", date)
                        .build())
                .header("authorization", authorizationHeader) // [수정] 명확한 변수명 사용 및 Bearer 접두사 한번만 붙도록 보장
                .header("appkey", kisApiProperties.getAppkey())
                .header("appsecret", kisApiProperties.getAppsecret())
                .header("tr_id", "FHPUP02120000")
                .header("custtype", "P") // [중요] 고객 타입 추가 (P: 개인)
                .retrieve()
                .bodyToMono(KisIndexChartApiResponse.class)
                .onErrorMap(error -> {
                    // 로깅을 통해 실제 API가 뱉는 에러 메시지를 확인하는 것이 좋습니다.
                    log.error("KIS API Error for {}: {}", marketCode, error.getMessage());
                    return new BusinessException(KisApiErrorCode.INDEX_FETCH_FAILED);
                })
                .block();

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
}
