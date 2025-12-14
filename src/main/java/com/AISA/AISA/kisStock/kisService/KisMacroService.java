package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.kisStock.config.KisApiProperties;
import com.AISA.AISA.kisStock.dto.Macro.KisOverseasDailyPriceDto;
import com.AISA.AISA.kisStock.dto.Macro.KisOverseasDailyPriceResponseDto;
import com.AISA.AISA.kisStock.enums.BondYield;

import com.AISA.AISA.kisStock.exception.KisApiErrorCode;
import com.AISA.AISA.kisStock.kisService.Auth.KisAuthService;
import com.AISA.AISA.portfolio.macro.Entity.MacroDailyData;
import com.AISA.AISA.portfolio.macro.repository.MacroDailyDataRepository;
import com.AISA.AISA.portfolio.macro.dto.MacroIndicatorDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class KisMacroService {

    private final WebClient webClient;
    private final KisAuthService kisAuthService;
    private final KisApiProperties kisApiProperties;

    private final MacroDailyDataRepository macroDailyDataRepository;

    // StatCode and ItemCode for Exchange Rate (Compatible with ECOS codes)
    private static final String STAT_CODE_EXCHANGE_RATE = "731Y001";
    private static final String ITEM_CODE_USD = "0000001";

    // StatCode for Overseas Index (New constant)

    // StatCode for Bond Yield (New constant)
    private static final String STAT_CODE_BOND_YIELD = "KIS_BOND_YIELD";

    public List<MacroIndicatorDto> fetchExchangeRate(String currencyCode, String startDate, String endDate) {
        return fetchMacroData(STAT_CODE_EXCHANGE_RATE, ITEM_CODE_USD, "X", currencyCode, startDate, endDate);
    }

    public List<MacroIndicatorDto> fetchBondYield(BondYield bond, String startDate, String endDate) {
        return fetchMacroData(STAT_CODE_BOND_YIELD, bond.getSymbol(), "I", bond.getSymbol(), startDate, endDate);
    }

    private List<MacroIndicatorDto> fetchMacroData(String statCode, String itemCode, String marketDivCode,
            String symbol, String startDate, String endDate) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        LocalDate start = LocalDate.parse(startDate, formatter);
        LocalDate end = LocalDate.parse(endDate, formatter);

        // 1. Try to fetch from DB
        List<MacroDailyData> dbData = macroDailyDataRepository.findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(
                statCode, itemCode, start, end);

        if (!dbData.isEmpty()) {
            return dbData.stream()
                    .map(entity -> new MacroIndicatorDto(
                            entity.getDate().format(formatter),
                            entity.getValue().toString()))
                    .collect(Collectors.toList());
        }

        // 2. If DB is empty, fetch from API and save
        fetchAndSaveMacroData(statCode, itemCode, marketDivCode, symbol, startDate, endDate);

        // 3. Re-fetch from DB
        dbData = macroDailyDataRepository.findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(
                statCode, itemCode, start, end);

        return dbData.stream()
                .map(entity -> new MacroIndicatorDto(
                        entity.getDate().format(formatter),
                        entity.getValue().toString()))
                .collect(Collectors.toList());
    }

    @Transactional
    public void fetchAndSaveExchangeRate(String currencyCode, String startDateStr, String endDateStr) {
        fetchAndSaveMacroData(STAT_CODE_EXCHANGE_RATE, ITEM_CODE_USD, "X", currencyCode, startDateStr, endDateStr);
    }

    @Transactional
    public void fetchAndSaveBondYield(BondYield bond, String startDateStr, String endDateStr) {
        fetchAndSaveMacroData(STAT_CODE_BOND_YIELD, bond.getSymbol(), "I", bond.getSymbol(), startDateStr,
                endDateStr);
    }

    public Double getLatestExchangeRate() {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(7); // Check last 7 days for holidays/weekends

        List<MacroDailyData> data = macroDailyDataRepository.findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(
                STAT_CODE_EXCHANGE_RATE, ITEM_CODE_USD, startDate, endDate);

        if (data.isEmpty()) {
            return null;
        }

        // Return the latest value
        return data.get(data.size() - 1).getValue().doubleValue();
    }

    public Map<String, Double> getExchangeRateMap(String startDate, String endDate) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        LocalDate start = LocalDate.parse(startDate, formatter);
        LocalDate end = LocalDate.parse(endDate, formatter);

        // Fetch enough range to handle holidays (look back 7 more days)
        LocalDate fetchStart = start.minusDays(7);

        List<MacroDailyData> data = macroDailyDataRepository.findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(
                STAT_CODE_EXCHANGE_RATE, ITEM_CODE_USD, fetchStart, end);

        return data.stream()
                .collect(Collectors.toMap(
                        entity -> entity.getDate().format(formatter),
                        entity -> entity.getValue().doubleValue(),
                        (existing, replacement) -> existing)); // In case of duplicates
    }

    private void fetchAndSaveMacroData(String statCode, String itemCode, String marketDivCode, String symbol,
            String startDateStr, String endDateStr) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        LocalDate targetStartDate = LocalDate.parse(startDateStr, formatter);
        LocalDate currentDate = LocalDate.parse(endDateStr, formatter);

        log.info("Starting bulk fetch for {} ({}) from {} to {}", statCode, symbol, startDateStr, endDateStr);

        while (!currentDate.isBefore(targetStartDate)) {
            String currentDateStr = currentDate.format(formatter);
            LocalDate queryStartDate = currentDate.minusDays(99); // 100 days limit (inclusive)
            if (queryStartDate.isBefore(targetStartDate)) {
                queryStartDate = targetStartDate;
            }
            String queryStartDateStr = queryStartDate.format(formatter);

            log.info("Fetching data from {} to {}", queryStartDateStr, currentDateStr);

            try {
                List<KisOverseasDailyPriceDto> apiList = fetchFromApi(marketDivCode, symbol, queryStartDateStr,
                        currentDateStr);

                if (apiList != null && !apiList.isEmpty()) {
                    for (KisOverseasDailyPriceDto dto : apiList) {
                        LocalDate date = LocalDate.parse(dto.getDate(), formatter);

                        boolean exists = macroDailyDataRepository
                                .findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(
                                        statCode, itemCode, date, date)
                                .stream().findAny().isPresent();

                        if (!exists) {
                            MacroDailyData entity = MacroDailyData.builder()
                                    .statCode(statCode)
                                    .itemCode(itemCode)
                                    .date(date)
                                    .value(new java.math.BigDecimal(dto.getClosePrice()))
                                    .build();
                            macroDailyDataRepository.save(entity);
                        }
                    }
                }

                // Move back
                currentDate = queryStartDate.minusDays(1);
                Thread.sleep(100); // Rate limit

            } catch (Exception e) {
                log.error("Error fetching macro data: {}", e.getMessage());
                break; // Stop on error
            }
        }
        log.info("Finished bulk fetch.");
    }

    private List<KisOverseasDailyPriceDto> fetchFromApi(String marketDivCode, String symbol, String startDate,
            String endDate) {
        String accessToken = kisAuthService.getAccessToken();

        KisOverseasDailyPriceResponseDto response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(kisApiProperties.getOverSeaUrl())
                        .queryParam("FID_COND_MRKT_DIV_CODE", marketDivCode)
                        .queryParam("FID_INPUT_ISCD", symbol)
                        .queryParam("FID_INPUT_DATE_1", startDate)
                        .queryParam("FID_INPUT_DATE_2", endDate)
                        .queryParam("FID_PERIOD_DIV_CODE", "D")
                        .build())
                .header("authorization", accessToken)
                .header("appkey", kisApiProperties.getAppkey())
                .header("appsecret", kisApiProperties.getAppsecret())
                .header("tr_id", "FHKST03030100")
                .header("custtype", "P")
                .retrieve()
                .bodyToMono(KisOverseasDailyPriceResponseDto.class)
                .block();

        if (response == null || !"0".equals(response.getReturnCode())) {
            log.error("KIS API Error: RtCd={}, Msg={}",
                    response != null ? response.getReturnCode() : "null",
                    response != null ? response.getMessage() : "null response");
            throw new BusinessException(KisApiErrorCode.STOCK_PRICE_FETCH_FAILED);
        }

        return response.getDailyPriceList();
    }

}
