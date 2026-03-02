package com.AISA.AISA.fred.service;

import com.AISA.AISA.fred.config.FredApiProperties;
import com.AISA.AISA.fred.dto.FredIndexDataDto;
import com.AISA.AISA.fred.dto.FredObservationDto;
import com.AISA.AISA.fred.dto.FredSeriesResponseDto;
import com.AISA.AISA.fred.enums.FredIndex;
import com.AISA.AISA.kisStock.Entity.Index.OverseasIndexDailyData;
import com.AISA.AISA.kisStock.kisService.KisMacroService;
import com.AISA.AISA.kisStock.repository.OverseasIndexDailyDataRepository;
import com.AISA.AISA.portfolio.macro.dto.MacroIndicatorDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class FredIndexService {

    private static final DateTimeFormatter APP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter FRED_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final WebClient fredWebClient;
    private final FredApiProperties fredApiProperties;
    private final OverseasIndexDailyDataRepository repository;
    private final KisMacroService kisMacroService;

    public FredIndexService(WebClient.Builder webClientBuilder,
                            FredApiProperties fredApiProperties,
                            OverseasIndexDailyDataRepository repository,
                            KisMacroService kisMacroService) {
        this.fredWebClient = webClientBuilder
                .baseUrl(fredApiProperties.getBaseUrl())
                .build();
        this.fredApiProperties = fredApiProperties;
        this.repository = repository;
        this.kisMacroService = kisMacroService;
    }

    // DB 우선 조회, 없으면 FRED에서 가져와 저장 후 반환
    @Cacheable(value = "fredIndex", key = "#index.name() + '-' + #startDate + '-' + #endDate")
    public List<FredIndexDataDto> getFredIndexChart(FredIndex index, String startDate, String endDate) {
        LocalDate start = LocalDate.parse(startDate, APP_FMT);
        LocalDate end = LocalDate.parse(endDate, APP_FMT);

        List<OverseasIndexDailyData> dbData = repository
                .findAllByMarketNameAndDateBetweenOrderByDateAsc(index.getMarketName(), start, end);

        if (!dbData.isEmpty()) {
            return dbData.stream().map(this::toDto).collect(Collectors.toList());
        }

        fetchAndSave(index, startDate, endDate);

        dbData = repository.findAllByMarketNameAndDateBetweenOrderByDateAsc(index.getMarketName(), start, end);
        return dbData.stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional
    @CacheEvict(value = "fredIndex", allEntries = true)
    public void fetchAndSave(FredIndex index, String startDateStr, String endDateStr) {
        String fredStart = LocalDate.parse(startDateStr, APP_FMT).format(FRED_FMT);
        String fredEnd = LocalDate.parse(endDateStr, APP_FMT).format(FRED_FMT);

        log.info("Fetching FRED data: series={}, start={}, end={}", index.getSeriesId(), fredStart, fredEnd);

        FredSeriesResponseDto response = fredWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/series/observations")
                        .queryParam("series_id", index.getSeriesId())
                        .queryParam("api_key", fredApiProperties.getApiKey())
                        .queryParam("file_type", "json")
                        .queryParam("observation_start", fredStart)
                        .queryParam("observation_end", fredEnd)
                        .build())
                .retrieve()
                .bodyToMono(FredSeriesResponseDto.class)
                .block();

        if (response == null || response.getObservations() == null) {
            throw new RuntimeException("FRED API returned empty response for series: " + index.getSeriesId());
        }

        int saved = 0;
        for (FredObservationDto obs : response.getObservations()) {
            // "." = 주말·공휴일 (데이터 없음), 건너뜀
            if (".".equals(obs.getValue())) continue;

            LocalDate date = LocalDate.parse(obs.getDate(), FRED_FMT);
            BigDecimal price = new BigDecimal(obs.getValue());

            if (repository.findByMarketNameAndDate(index.getMarketName(), date).isPresent()) continue;

            // FRED는 종가만 제공 → OHLC 모두 종가로 설정
            repository.save(OverseasIndexDailyData.builder()
                    .marketName(index.getMarketName())
                    .date(date)
                    .closingPrice(price)
                    .openingPrice(price)
                    .highPrice(price)
                    .lowPrice(price)
                    .priceChange(BigDecimal.ZERO)
                    .changeRate(0.0)
                    .volume(BigDecimal.ZERO)
                    .build());
            saved++;
        }

        log.info("FRED data saved: series={}, count={}", index.getSeriesId(), saved);
    }

    @Cacheable(value = "fredIndexKrw", key = "#index.name() + '-' + #startDate + '-' + #endDate")
    public List<FredIndexDataDto> getFredIndexChartKrw(FredIndex index, String startDate, String endDate) {
        List<FredIndexDataDto> usdData = getFredIndexChart(index, startDate, endDate);

        List<MacroIndicatorDto> exchangeRateList = kisMacroService.fetchExchangeRate("USD", startDate, endDate);
        Map<String, BigDecimal> exchangeRateMap = exchangeRateList.stream()
                .collect(Collectors.toMap(MacroIndicatorDto::getDate, dto -> new BigDecimal(dto.getValue())));

        return usdData.stream()
                .filter(dto -> exchangeRateMap.containsKey(dto.getDate()))
                .map(dto -> {
                    BigDecimal usdPrice = new BigDecimal(dto.getPrice());
                    BigDecimal exchangeRate = exchangeRateMap.get(dto.getDate());
                    BigDecimal krwPrice = usdPrice.multiply(exchangeRate)
                            .divide(new BigDecimal(1000), 2, RoundingMode.HALF_UP);
                    return FredIndexDataDto.builder()
                            .date(dto.getDate())
                            .price(krwPrice.toPlainString())
                            .exchangeRate(exchangeRate.toPlainString())
                            .build();
                })
                .collect(Collectors.toList());
    }

    private FredIndexDataDto toDto(OverseasIndexDailyData entity) {
        return FredIndexDataDto.builder()
                .date(entity.getDate().format(APP_FMT))
                .price(entity.getClosingPrice().toPlainString())
                .build();
    }
}
