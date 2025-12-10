package com.AISA.AISA.portfolio.macro.service;

import com.AISA.AISA.kisStock.dto.Index.IndexChartPriceDto;
import com.AISA.AISA.kisStock.dto.Index.IndexChartResponseDto;
import com.AISA.AISA.kisStock.kisService.KisIndexService;
import com.AISA.AISA.kisStock.kisService.KisMacroService;
import com.AISA.AISA.portfolio.macro.dto.MacroIndicatorDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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

    public List<MacroIndicatorDto> getKospiUsdRatio(String startDate, String endDate) {
        // 1. Fetch KOSPI Data
        IndexChartResponseDto kospiData = kisIndexService.getIndexChart("KOSPI", startDate, endDate, "D");
        Map<String, BigDecimal> kospiMap = kospiData.getPriceList().stream()
                .collect(Collectors.toMap(
                        IndexChartPriceDto::getDate,
                        dto -> new BigDecimal(dto.getPrice())));

        // 2. Fetch Exchange Rate Data (from KIS API)
        List<MacroIndicatorDto> exchangeRateData = kisMacroService.fetchExchangeRate("FX@KRW", startDate, endDate);
        Map<String, BigDecimal> exchangeRateMap = exchangeRateData.stream()
                .collect(Collectors.toMap(
                        MacroIndicatorDto::getDate,
                        dto -> new BigDecimal(dto.getValue())));

        // 3. Calculate Ratio
        List<MacroIndicatorDto> ratioList = new ArrayList<>();
        List<String> sortedDates = new ArrayList<>(kospiMap.keySet());
        Collections.sort(sortedDates);

        for (String date : sortedDates) {
            if (exchangeRateMap.containsKey(date)) {
                BigDecimal kospi = kospiMap.get(date);
                BigDecimal exchangeRate = exchangeRateMap.get(date);

                if (exchangeRate.compareTo(BigDecimal.ZERO) > 0) {
                    // Formula: KOSPI / (ExchangeRate / 1000)
                    BigDecimal ratio = kospi.divide(
                            exchangeRate.divide(new BigDecimal(1000), 4, RoundingMode.HALF_UP),
                            2, RoundingMode.HALF_UP);

                    ratioList.add(new MacroIndicatorDto(date, ratio.toString()));
                }
            }
        }
        return ratioList;
    }
}
