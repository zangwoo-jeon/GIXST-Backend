package com.AISA.AISA.portfolio.macro.service;

import com.AISA.AISA.kisStock.dto.Index.IndexChartPriceDto;
import com.AISA.AISA.kisStock.dto.Index.IndexChartResponseDto;
import com.AISA.AISA.kisStock.enums.OverseasIndex;
import com.AISA.AISA.kisStock.kisService.KisIndexService;
import com.AISA.AISA.kisStock.kisService.KisMacroService;
import com.AISA.AISA.kisStock.enums.ExchangeRateCode;
import com.AISA.AISA.portfolio.macro.dto.MacroIndicatorDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.cache.annotation.Cacheable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MacroService {

    private final KisMacroService kisMacroService;
    private final KisIndexService kisIndexService;

    // Methods moved to KisIndexService

    @Cacheable(value = "overseasIndexKrw", key = "#indexName + '-' + #startDate + '-' + #endDate")
    public List<IndexChartPriceDto> getWonConvertedOverseasIndex(String indexName, String startDate, String endDate) {
        OverseasIndex overseasIndex = OverseasIndex.valueOf(indexName.toUpperCase());
        String currencyCode;

        if (overseasIndex == OverseasIndex.NASDAQ || overseasIndex == OverseasIndex.SP500) {
            currencyCode = ExchangeRateCode.USD.getSymbol();
        } else if (overseasIndex == OverseasIndex.NIKKEI) {
            currencyCode = ExchangeRateCode.JPY.getSymbol();
        } else if (overseasIndex == OverseasIndex.HANGSENG) {
            currencyCode = ExchangeRateCode.HKD_KRW.getSymbol();
        } else if (overseasIndex == OverseasIndex.EUROSTOXX50) {
            currencyCode = ExchangeRateCode.EUR_KRW.getSymbol();
        } else {
            throw new IllegalArgumentException("원화 환산은 NASDAQ, S&P500, NIKKEI, HANGSENG, EUROSTOXX50 지수만 지원합니다.");
        }

        // 1. Fetch Overseas Index Data
        List<IndexChartPriceDto> indexData = kisIndexService.fetchOverseasIndex(overseasIndex, startDate, endDate);
        Map<String, IndexChartPriceDto> indexMap = indexData.stream()
                .collect(Collectors.toMap(
                        IndexChartPriceDto::getDate,
                        dto -> dto));

        // 2. Fetch Exchange Rate Data (from KIS API)
        List<MacroIndicatorDto> exchangeRateData = kisMacroService.fetchExchangeRate(currencyCode, startDate, endDate);
        Map<String, BigDecimal> exchangeRateMap = exchangeRateData.stream()
                .collect(Collectors.toMap(
                        MacroIndicatorDto::getDate,
                        dto -> new BigDecimal(dto.getValue())));

        // 3. Calculate Won Converted Value
        List<IndexChartPriceDto> resultList = new ArrayList<>();
        List<String> sortedDates = new ArrayList<>(indexMap.keySet());
        Collections.sort(sortedDates);

        for (String date : sortedDates) {
            if (exchangeRateMap.containsKey(date)) {
                IndexChartPriceDto indexDto = indexMap.get(date);
                BigDecimal indexValue = new BigDecimal(indexDto.getPrice());
                BigDecimal exchangeRate = exchangeRateMap.get(date);

                if (exchangeRate.compareTo(BigDecimal.ZERO) > 0) {
                    // Formula: (IndexValue * ExchangeRate) / 1000
                    BigDecimal wonConvertedValue = indexValue.multiply(exchangeRate)
                            .divide(new BigDecimal(1000), 2, RoundingMode.HALF_UP);

                    // Converted OHLC (assuming simple multiplication for all)
                    BigDecimal open = new BigDecimal(indexDto.getOpenPrice()).multiply(exchangeRate)
                            .divide(new BigDecimal(1000), 2, RoundingMode.HALF_UP);
                    BigDecimal high = new BigDecimal(indexDto.getHighPrice()).multiply(exchangeRate)
                            .divide(new BigDecimal(1000), 2, RoundingMode.HALF_UP);
                    BigDecimal low = new BigDecimal(indexDto.getLowPrice()).multiply(exchangeRate)
                            .divide(new BigDecimal(1000), 2, RoundingMode.HALF_UP);

                    resultList.add(IndexChartPriceDto.builder()
                            .date(date)
                            .price(wonConvertedValue.toString())
                            .openPrice(open.toString())
                            .highPrice(high.toString())
                            .lowPrice(low.toString())
                            .volume(indexDto.getVolume())
                            .exchangeRate(exchangeRate.toString())
                            .build());
                }
            }
        }
        return resultList;
    }
}
