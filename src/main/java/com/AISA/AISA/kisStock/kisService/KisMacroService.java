package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.kisOverseasStock.config.KisOverseasApiProperties;
import com.AISA.AISA.kisStock.config.KisApiProperties;
import com.AISA.AISA.kisStock.dto.Macro.KisOverseasDailyPriceDto;
import com.AISA.AISA.kisStock.dto.Macro.KisOverseasDailyPriceResponseDto;
import com.AISA.AISA.portfolio.macro.dto.ExchangeRateStatusDto;
import com.AISA.AISA.kisStock.enums.BondYield;
import com.AISA.AISA.kisStock.enums.ExchangeRateCode;

import com.AISA.AISA.kisStock.exception.KisApiErrorCode;
import com.AISA.AISA.portfolio.macro.Entity.MacroDailyData;
import com.AISA.AISA.portfolio.macro.repository.MacroDailyDataRepository;
import com.AISA.AISA.portfolio.macro.dto.MacroIndicatorDto;
import com.AISA.AISA.kisStock.config.EcosApiProperties;
import com.AISA.AISA.kisStock.dto.EcosApiResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class KisMacroService {

    private final WebClient webClient;
    private final KisApiProperties kisApiProperties;
    private final KisOverseasApiProperties overseasApiProperties;
    private final EcosApiProperties ecosApiProperties;
    private final KisApiClient kisApiClient;

    private final MacroDailyDataRepository macroDailyDataRepository;

    // 한국은행 ECOS API - 주요국 통화의 대원화환율 (일별)
    private static final String STAT_CODE_EXCHANGE_RATE = "731Y001";

    // StatCode for Bond Yield (KIS API - US bonds only)
    private static final String STAT_CODE_BOND_YIELD = "KIS_BOND_YIELD";

    @Cacheable(value = "macroExchangeRate", key = "#currencyCode + '-' + #startDate + '-' + #endDate")
    public List<MacroIndicatorDto> fetchExchangeRate(String currencyCode, String startDate, String endDate) {
        ExchangeRateCode exchangeRateCode = ExchangeRateCode.findBySymbol(currencyCode);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        LocalDate start = LocalDate.parse(startDate, formatter);
        LocalDate end = LocalDate.parse(endDate, formatter);

        List<MacroDailyData> dbData = macroDailyDataRepository
                .findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(
                        STAT_CODE_EXCHANGE_RATE, exchangeRateCode.getItemCode(), start, end);

        // DB가 비어있거나, 첫 데이터가 요청 시작일보다 7일 이상 늦으면 ECOS에서 빠진 구간 보충
        boolean hasGap = dbData.isEmpty() || dbData.get(0).getDate().isAfter(start.plusDays(7));
        if (hasGap) {
            fetchAndSaveExchangeRate(currencyCode, startDate, endDate);
            dbData = macroDailyDataRepository
                    .findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(
                            STAT_CODE_EXCHANGE_RATE, exchangeRateCode.getItemCode(), start, end);
        }

        return dbData.stream()
                .map(entity -> new MacroIndicatorDto(entity.getDate().format(formatter), entity.getValue().toString()))
                .collect(Collectors.toList());
    }

    @Cacheable(value = "macroBond", key = "#bond.name() + '-' + #startDate + '-' + #endDate")
    public List<MacroIndicatorDto> fetchBondYield(BondYield bond, String startDate, String endDate) {
        if (bond.isEcosBased()) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
            LocalDate start = LocalDate.parse(startDate, formatter);
            LocalDate end = LocalDate.parse(endDate, formatter);

            List<MacroDailyData> dbData = macroDailyDataRepository
                    .findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(
                            STAT_CODE_ECOS_BOND_YIELD, bond.getEcosItemCode(), start, end);

            // DB가 비어있거나, 첫 데이터가 요청 시작일보다 7일 이상 늦으면 ECOS에서 빠진 구간 보충
            boolean hasGap = dbData.isEmpty() || dbData.get(0).getDate().isAfter(start.plusDays(7));
            if (hasGap) {
                fetchAndSaveBondYield(bond, startDate, endDate);
                dbData = macroDailyDataRepository
                        .findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(
                                STAT_CODE_ECOS_BOND_YIELD, bond.getEcosItemCode(), start, end);
            }

            return dbData.stream()
                    .map(e -> new MacroIndicatorDto(e.getDate().format(formatter), e.getValue().toString()))
                    .collect(Collectors.toList());
        }

        return fetchMacroData(STAT_CODE_BOND_YIELD, bond.getSymbol(), "I", bond.getSymbol(), startDate, endDate);
    }

    public List<MacroIndicatorDto> fetchMacroData(String statCode, String itemCode, String marketDivCode,
            String symbol, String startDate, String endDate) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        LocalDate start = LocalDate.parse(startDate, formatter);
        LocalDate end = LocalDate.parse(endDate, formatter);

        List<MacroDailyData> dbData = macroDailyDataRepository.findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(
                statCode, itemCode, start, end);

        if (!dbData.isEmpty()) {
            return dbData.stream()
                    .map(entity -> new MacroIndicatorDto(
                            entity.getDate().format(formatter),
                            entity.getValue().toString()))
                    .collect(Collectors.toList());
        }

        fetchAndSaveMacroData(statCode, itemCode, marketDivCode, symbol, startDate, endDate);

        dbData = macroDailyDataRepository.findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(
                statCode, itemCode, start, end);

        return dbData.stream()
                .map(entity -> new MacroIndicatorDto(
                        entity.getDate().format(formatter),
                        entity.getValue().toString()))
                .collect(Collectors.toList());
    }

    @Transactional
    @CacheEvict(value = "macroExchangeRate", allEntries = true)
    public void fetchAndSaveExchangeRate(String currencyCode, String startDateStr, String endDateStr) {
        ExchangeRateCode exchangeRateCode = ExchangeRateCode.findBySymbol(currencyCode);
        fetchAndSaveEcosDataForRange(STAT_CODE_EXCHANGE_RATE, exchangeRateCode.getItemCode(), startDateStr, endDateStr);
    }

    @Transactional
    @CacheEvict(value = "macroBond", allEntries = true)
    public void fetchAndSaveBondYield(BondYield bond, String startDateStr, String endDateStr) {
        if (bond.isEcosBased()) {
            fetchAndSaveEcosDataForRange(STAT_CODE_ECOS_BOND_YIELD, bond.getEcosItemCode(), startDateStr, endDateStr);
        } else {
            fetchAndSaveMacroData(STAT_CODE_BOND_YIELD, bond.getSymbol(), "I", bond.getSymbol(), startDateStr, endDateStr);
        }
    }

    @Transactional
    public void fetchAndSaveEcosDataForRange(String statCode, String itemCode, String startDateStr, String endDateStr) {
        try {
            String url = String.format("/StatisticSearch/%s/json/kr/1/100000/%s/D/%s/%s/%s",
                    ecosApiProperties.getApiKey(), statCode, startDateStr, endDateStr, itemCode);

            log.info("Fetching ECOS data: stat={}, item={}, range=[{}~{}]", statCode, itemCode, startDateStr, endDateStr);

            EcosApiResponseDto response = webClient.get()
                    .uri(ecosApiProperties.getBaseUrl() + url)
                    .retrieve()
                    .bodyToMono(EcosApiResponseDto.class)
                    .block();

            if (response == null || response.getStatisticSearch() == null
                    || response.getStatisticSearch().getRow() == null) return;

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
            for (EcosApiResponseDto.Row row : response.getStatisticSearch().getRow()) {
                if (row.getDataValue() == null || row.getDataValue().isBlank()) continue;
                LocalDate date = LocalDate.parse(row.getTime(), formatter);
                BigDecimal value = new BigDecimal(row.getDataValue());

                boolean exists = macroDailyDataRepository
                        .findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(statCode, itemCode, date, date)
                        .stream().findAny().isPresent();

                if (!exists) {
                    macroDailyDataRepository.save(MacroDailyData.builder()
                            .statCode(statCode)
                            .itemCode(itemCode)
                            .date(date)
                            .value(value)
                            .build());
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch/save ECOS data (stat={}, item={}): {}", statCode, itemCode, e.getMessage());
        }
    }

    public Double getLatestExchangeRate() {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(7);

        List<MacroDailyData> data = macroDailyDataRepository.findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(
                STAT_CODE_EXCHANGE_RATE, ExchangeRateCode.USD.getItemCode(), startDate, endDate);

        if (data.isEmpty()) {
            return null;
        }

        return data.get(data.size() - 1).getValue().doubleValue();
    }

    public ExchangeRateStatusDto getExchangeRateStatus(ExchangeRateCode code) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(7);

        List<MacroDailyData> data = macroDailyDataRepository
                .findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(
                        STAT_CODE_EXCHANGE_RATE, code.getItemCode(), startDate, endDate);

        if (data.isEmpty()) return null;

        MacroDailyData latest = data.get(data.size() - 1);
        BigDecimal price = latest.getValue();
        BigDecimal priceChange = BigDecimal.ZERO;
        BigDecimal changeRate = BigDecimal.ZERO;

        if (data.size() >= 2) {
            BigDecimal prev = data.get(data.size() - 2).getValue();
            priceChange = price.subtract(prev).setScale(2, RoundingMode.HALF_UP);
            if (prev.compareTo(BigDecimal.ZERO) != 0) {
                changeRate = priceChange.divide(prev, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
            }
        }

        return ExchangeRateStatusDto.builder()
                .date(latest.getDate().format(DateTimeFormatter.ofPattern("yyyyMMdd")))
                .price(price.toPlainString())
                .priceChange(priceChange.toPlainString())
                .changeRate(changeRate.toPlainString())
                .build();
    }

    @Cacheable(value = "exchangeRateStatus", key = "'USD'")
    public ExchangeRateStatusDto getExchangeRateStatus() {
        return getExchangeRateStatus(ExchangeRateCode.USD);
    }

    @Cacheable(value = "exchangeRateMap", key = "#startDate + '-' + #endDate")
    public Map<String, Double> getExchangeRateMap(String startDate, String endDate) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        LocalDate start = LocalDate.parse(startDate, formatter);
        LocalDate end = LocalDate.parse(endDate, formatter);

        // Fetch enough range to handle holidays (look back 7 more days)
        LocalDate fetchStart = start.minusDays(7);

        List<MacroDailyData> data = macroDailyDataRepository.findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(
                STAT_CODE_EXCHANGE_RATE, ExchangeRateCode.USD.getItemCode(), fetchStart, end);

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
                                    .value(new BigDecimal(dto.getClosePrice()))
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

    // ECOS Bond Yield (3Y BBB-)
    // STAT_CODE: 817Y002
    // ITEM_CODE: 010320000
    public static final String STAT_CODE_ECOS_BOND_YIELD = "817Y002";
    public static final String ITEM_CODE_ECOS_BOND_YIELD = "010320000";

    @Transactional
    public void fetchAndSaveEcosBondYield() {
        fetchAndSaveEcosData(STAT_CODE_ECOS_BOND_YIELD, ITEM_CODE_ECOS_BOND_YIELD, 7);
    }

    @Transactional
    public void fetchAndSaveEcosData(String statCode, String itemCode, int lookbackDays) {
        try {
            LocalDate today = LocalDate.now();
            LocalDate startDate = today.minusDays(lookbackDays);
            String startStr = startDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String endStr = today.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            String key = ecosApiProperties.getApiKey();
            String url = String.format("/StatisticSearch/%s/json/kr/1/100/%s/D/%s/%s/%s",
                    key, statCode, startStr, endStr, itemCode);

            log.info("Fetching ECOS data: stat={}, item={}, range=[{}~{}]", statCode, itemCode, startStr, endStr);

            EcosApiResponseDto response = webClient.get()
                    .uri("https://ecos.bok.or.kr/api" + url)
                    .retrieve()
                    .bodyToMono(EcosApiResponseDto.class)
                    .block();

            if (response != null && response.getStatisticSearch() != null
                    && response.getStatisticSearch().getRow() != null) {
                for (EcosApiResponseDto.Row row : response.getStatisticSearch().getRow()) {
                    LocalDate date = LocalDate.parse(row.getTime(), DateTimeFormatter.ofPattern("yyyyMMdd"));
                    BigDecimal value = new BigDecimal(row.getDataValue());

                    boolean exists = macroDailyDataRepository
                            .findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(
                                    statCode, itemCode, date, date)
                            .stream().findAny().isPresent();

                    if (!exists) {
                        MacroDailyData entity = MacroDailyData.builder()
                                .statCode(statCode)
                                .itemCode(itemCode)
                                .date(date)
                                .value(value)
                                .build();
                        macroDailyDataRepository.save(entity);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch/save ECOS data (stat={}): {}", statCode, e.getMessage());
        }
    }

    @Cacheable(value = "ecosBondYield", key = "#statCode + '-' + #itemCode", unless = "#result == null")
    public BigDecimal getLatestEcosData(String statCode, String itemCode) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(14); // Look back 2 weeks just in case

        List<MacroDailyData> data = macroDailyDataRepository
                .findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(
                        statCode, itemCode, startDate, endDate);

        if (!data.isEmpty()) {
            return data.get(data.size() - 1).getValue();
        }

        // Try fetch
        fetchAndSaveEcosData(statCode, itemCode, 14);

        data = macroDailyDataRepository
                .findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(
                        statCode, itemCode, startDate, endDate);

        if (!data.isEmpty()) {
            return data.get(data.size() - 1).getValue();
        }

        return null;
    }

    @Cacheable(value = "macroBond", key = "'latest-' + #bond.name()", unless = "#result == null")
    public BigDecimal getLatestBondYield(BondYield bond) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(7);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");

        String statCode = bond.isEcosBased() ? STAT_CODE_ECOS_BOND_YIELD : STAT_CODE_BOND_YIELD;
        String itemCode = bond.isEcosBased() ? bond.getEcosItemCode() : bond.getSymbol();

        List<MacroDailyData> data = macroDailyDataRepository
                .findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(statCode, itemCode, startDate, endDate);

        if (!data.isEmpty()) return data.get(data.size() - 1).getValue();

        fetchAndSaveBondYield(bond, startDate.format(formatter), endDate.format(formatter));

        data = macroDailyDataRepository
                .findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(statCode, itemCode, startDate, endDate);

        return data.isEmpty() ? null : data.get(data.size() - 1).getValue();
    }

}
