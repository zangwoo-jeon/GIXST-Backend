package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.kisOverseasStock.config.KisOverseasApiProperties;
import com.AISA.AISA.kisStock.config.KisApiProperties;
import com.AISA.AISA.kisStock.dto.Macro.KisOverseasDailyPriceDto;
import com.AISA.AISA.kisStock.dto.Macro.KisOverseasDailyPriceResponseDto;
import com.AISA.AISA.kisStock.dto.Macro.KisOverseasIndexBasicInfoDto;
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
    private final KisOverseasApiProperties overseasApiProperties; // Inject
    private final EcosApiProperties ecosApiProperties; // Added
    private final KisApiClient kisApiClient;

    private final MacroDailyDataRepository macroDailyDataRepository;

    // StatCode and ItemCode for Exchange Rate (Compatible with ECOS codes)
    private static final String STAT_CODE_EXCHANGE_RATE = "731Y001";

    // StatCode for Overseas Index (New constant)

    // StatCode for Bond Yield (New constant)
    private static final String STAT_CODE_BOND_YIELD = "KIS_BOND_YIELD";

    @Cacheable(value = "macroExchangeRate", key = "#currencyCode + '-' + #startDate + '-' + #endDate")
    public List<MacroIndicatorDto> fetchExchangeRate(String currencyCode, String startDate, String endDate) {
        ExchangeRateCode exchangeRateCode = ExchangeRateCode.findBySymbol(currencyCode);

        // For calculated rates like HKD_KRW, EUR_KRW, we only fetch from DB
        if (exchangeRateCode == ExchangeRateCode.HKD_KRW || exchangeRateCode == ExchangeRateCode.EUR_KRW) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
            LocalDate start = LocalDate.parse(startDate, formatter);
            LocalDate end = LocalDate.parse(endDate, formatter);

            List<MacroDailyData> dbData = macroDailyDataRepository
                    .findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(
                            STAT_CODE_EXCHANGE_RATE, exchangeRateCode.getItemCode(), start, end);

            return dbData.stream()
                    .map(entity -> new MacroIndicatorDto(
                            entity.getDate().format(formatter),
                            entity.getValue().toString()))
                    .collect(Collectors.toList());
        }

        return fetchMacroData(STAT_CODE_EXCHANGE_RATE, exchangeRateCode.getItemCode(), "X", currencyCode, startDate,
                endDate);
    }

    @Transactional
    public void calcAndSaveHkdKrwRate(String startDate, String endDate) {
        calcAndSaveCrossRate(ExchangeRateCode.HKD, ExchangeRateCode.HKD_KRW, startDate, endDate);
    }

    @Transactional
    public void calcAndSaveEurKrwRate(String startDate, String endDate) {
        calcAndSaveCrossRate(ExchangeRateCode.EUR, ExchangeRateCode.EUR_KRW, startDate, endDate);
    }

    private void calcAndSaveCrossRate(ExchangeRateCode sourceCode, ExchangeRateCode targetCode, String startDate,
            String endDate) {
        // 1. Ensure we have sufficient data for USD/KRW and USD/Source
        fetchAndSaveExchangeRate(ExchangeRateCode.USD.getSymbol(), startDate, endDate);
        fetchAndSaveExchangeRate(sourceCode.getSymbol(), startDate, endDate);

        // 2. Fetch data from Service (read from DB)
        List<MacroIndicatorDto> usdKrwList = fetchExchangeRate(ExchangeRateCode.USD.getSymbol(), startDate, endDate);
        List<MacroIndicatorDto> usdSourceList = fetchExchangeRate(sourceCode.getSymbol(), startDate, endDate);

        Map<String, BigDecimal> usdKrwMap = usdKrwList.stream()
                .collect(Collectors.toMap(MacroIndicatorDto::getDate, dto -> new BigDecimal(dto.getValue())));
        Map<String, BigDecimal> usdSourceMap = usdSourceList.stream()
                .collect(Collectors.toMap(MacroIndicatorDto::getDate, dto -> new BigDecimal(dto.getValue())));

        // 3. Calculate and Save Cross Rate
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");

        for (String dateStr : usdKrwMap.keySet()) {
            if (usdSourceMap.containsKey(dateStr)) {
                LocalDate date = LocalDate.parse(dateStr, formatter);

                // Check if already exists
                boolean exists = macroDailyDataRepository.findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(
                        STAT_CODE_EXCHANGE_RATE, targetCode.getItemCode(), date, date).stream().findAny().isPresent();

                if (!exists) {
                    BigDecimal usdKrw = usdKrwMap.get(dateStr);
                    BigDecimal usdSource = usdSourceMap.get(dateStr);

                    if (usdSource.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal crossRate;
                        // Determine calculation method based on Source Code
                        if (sourceCode == ExchangeRateCode.EUR) {
                            // EUR is quoted as EUR/USD (1 EUR = x USD)
                            // Formula: EUR/KRW = (USD/KRW) * (EUR/USD)
                            crossRate = usdKrw.multiply(usdSource).setScale(4, RoundingMode.HALF_UP);
                        } else {
                            // Others (like HKD) are quoted as USD/HKD (1 USD = x HKD)
                            // Formula: HKD/KRW = (USD/KRW) / (USD/HKD)
                            crossRate = usdKrw.divide(usdSource, 4, RoundingMode.HALF_UP);
                        }

                        MacroDailyData entity = MacroDailyData.builder()
                                .statCode(STAT_CODE_EXCHANGE_RATE)
                                .itemCode(targetCode.getItemCode())
                                .date(date)
                                .value(crossRate)
                                .build();
                        macroDailyDataRepository.save(entity);
                    }
                }
            }
        }
    }

    @Cacheable(value = "macroBond", key = "#bond.name() + '-' + #startDate + '-' + #endDate")
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
        ExchangeRateCode exchangeRateCode = ExchangeRateCode.findBySymbol(currencyCode);
        fetchAndSaveMacroData(STAT_CODE_EXCHANGE_RATE, exchangeRateCode.getItemCode(), "X", currencyCode, startDateStr,
                endDateStr);
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
                STAT_CODE_EXCHANGE_RATE, ExchangeRateCode.USD.getItemCode(), startDate, endDate);

        if (data.isEmpty()) {
            return null;
        }

        // Return the latest value
        return data.get(data.size() - 1).getValue().doubleValue();
    }

    public ExchangeRateStatusDto getExchangeRateStatus(ExchangeRateCode code) {
        if (code == ExchangeRateCode.HKD_KRW) {
            return calculateCrossRateStatus(ExchangeRateCode.USD, ExchangeRateCode.HKD, false);
        } else if (code == ExchangeRateCode.EUR_KRW) {
            return calculateCrossRateStatus(ExchangeRateCode.USD, ExchangeRateCode.EUR, true);
        }

        KisOverseasIndexBasicInfoDto latest = fetchExchangeRateBasicInfo(code.getSymbol());
        BigDecimal price = new BigDecimal(latest.getPrice());
        BigDecimal change = new BigDecimal(latest.getPriceChange());
        BigDecimal rate = new BigDecimal(latest.getChangeRate());

        // JPY Adjustment (1 unit -> 100 units)
        if (code == ExchangeRateCode.JPY) {
            price = price.multiply(new BigDecimal("100"));
            change = change.multiply(new BigDecimal("100"));
        }

        return ExchangeRateStatusDto.builder()
                .date(latest.getDate())
                .price(price.toPlainString())
                .priceChange(change.toPlainString())
                .changeRate(rate.toPlainString())
                .build();
    }

    public ExchangeRateStatusDto getExchangeRateStatus() {
        return getExchangeRateStatus(ExchangeRateCode.USD);
    }

    private ExchangeRateStatusDto calculateCrossRateStatus(ExchangeRateCode usdkrwCode, ExchangeRateCode sourceCode,
            boolean isMultiply) {
        // 1. Fetch Basic Info
        KisOverseasIndexBasicInfoDto usdKrwInfo = fetchExchangeRateBasicInfo(usdkrwCode.getSymbol());
        KisOverseasIndexBasicInfoDto sourceInfo = fetchExchangeRateBasicInfo(sourceCode.getSymbol());

        BigDecimal usdKrwPrice = new BigDecimal(usdKrwInfo.getPrice());
        BigDecimal usdKrwChangeRate = new BigDecimal(usdKrwInfo.getChangeRate());

        BigDecimal sourcePrice = new BigDecimal(sourceInfo.getPrice());
        BigDecimal sourceChangeRate = new BigDecimal(sourceInfo.getChangeRate());

        // 2. Calculate Current Price
        BigDecimal currentPrice;
        if (isMultiply) {
            // EUR/KRW = USD/KRW * EUR/USD
            currentPrice = usdKrwPrice.multiply(sourcePrice);
        } else {
            // HKD/KRW = USD/KRW / (USD/HKD)
            currentPrice = usdKrwPrice.divide(sourcePrice, 2, RoundingMode.HALF_UP);
        }

        // 3. Calculate Previous Close Price to derive Change
        BigDecimal usdKrwPrev = usdKrwPrice.divide(
                BigDecimal.ONE.add(usdKrwChangeRate.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP)),
                4, RoundingMode.HALF_UP);

        BigDecimal sourcePrev = sourcePrice.divide(
                BigDecimal.ONE.add(sourceChangeRate.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP)),
                4, RoundingMode.HALF_UP);

        BigDecimal prevPrice;
        if (isMultiply) {
            prevPrice = usdKrwPrev.multiply(sourcePrev);
        } else {
            prevPrice = usdKrwPrev.divide(sourcePrev, 4, RoundingMode.HALF_UP);
        }

        // 4. Calculate Change and Rate
        BigDecimal change = currentPrice.subtract(prevPrice).setScale(2, RoundingMode.HALF_UP);
        BigDecimal changeRate = BigDecimal.ZERO;

        if (prevPrice.compareTo(BigDecimal.ZERO) != 0) {
            changeRate = change.divide(prevPrice, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        return ExchangeRateStatusDto.builder()
                .date(usdKrwInfo.getDate()) // Use USD/KRW date
                .price(currentPrice.toPlainString())
                .priceChange(change.toPlainString())
                .changeRate(changeRate.toPlainString())
                .build();
    }

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
                            BigDecimal value = new java.math.BigDecimal(dto.getClosePrice());
                            // 엔화일 경우 100을 곱해서 저장 (100엔 단위)
                            if ("FX@KRWJS".equals(symbol)) {
                                value = value.multiply(new BigDecimal(100));
                            }

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

    private KisOverseasIndexBasicInfoDto fetchExchangeRateBasicInfo(String symbol) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        String today = LocalDate.now().format(formatter);

        KisOverseasDailyPriceResponseDto response = kisApiClient.fetch(token -> webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(overseasApiProperties.getOverseaUrl())
                        .queryParam("FID_COND_MRKT_DIV_CODE", "X")
                        .queryParam("FID_INPUT_ISCD", symbol)
                        .queryParam("FID_INPUT_DATE_1", today)
                        .queryParam("FID_INPUT_DATE_2", today)
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

    // ECOS Bond Yield (3Y BBB-)
    // STAT_CODE: 817Y002
    // ITEM_CODE: 010320000
    private static final String STAT_CODE_ECOS_BOND_YIELD = "817Y002";
    private static final String ITEM_CODE_ECOS_BOND_YIELD = "010320000";

    @Transactional
    public void fetchAndSaveEcosBondYield() {
        try {
            LocalDate today = LocalDate.now();
            LocalDate startDate = today.minusDays(7); // Check last 7 days
            String startStr = startDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String endStr = today.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            String key = ecosApiProperties.getApiKey();
            String url = String.format("/StatisticSearch/%s/json/kr/1/10/%s/D/%s/%s/%s",
                    key, STAT_CODE_ECOS_BOND_YIELD, startStr, endStr, ITEM_CODE_ECOS_BOND_YIELD);

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
                                    STAT_CODE_ECOS_BOND_YIELD, ITEM_CODE_ECOS_BOND_YIELD, date, date)
                            .stream().findAny().isPresent();

                    if (!exists) {
                        MacroDailyData entity = MacroDailyData.builder()
                                .statCode(STAT_CODE_ECOS_BOND_YIELD)
                                .itemCode(ITEM_CODE_ECOS_BOND_YIELD)
                                .date(date)
                                .value(value)
                                .build();
                        macroDailyDataRepository.save(entity);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch/save ECOS bond yield: {}", e.getMessage());
        }
    }

    @Cacheable(value = "ecosBondYield", key = "'latest'", unless = "#result == null")
    public String getLatestEcosBondYield() {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(14); // Look back 2 weeks just in case

        List<MacroDailyData> data = macroDailyDataRepository
                .findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(
                        STAT_CODE_ECOS_BOND_YIELD, ITEM_CODE_ECOS_BOND_YIELD, startDate, endDate);

        if (!data.isEmpty()) {
            return data.get(data.size() - 1).getValue().toString();
        }
        return null;
    }

}
