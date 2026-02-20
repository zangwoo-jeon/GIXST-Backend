package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.kisStock.dto.InvestorTrend.InvestorRankDto;
import com.AISA.AISA.kisStock.dto.InvestorTrend.InvestorRankResponseDto;
import com.AISA.AISA.kisStock.dto.InvestorTrend.InvestorTrendAggregationProjection;
import com.AISA.AISA.kisStock.repository.StockInvestorDailyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KisRankService {

    private final StockInvestorDailyRepository stockInvestorDailyRepository;

    @Transactional(readOnly = true)
    public InvestorRankResponseDto getInvestorRanking(String period, String type, int limit) {
        LocalDate startDate;
        if ("1w".equalsIgnoreCase(period)) {
            startDate = LocalDate.now().minusWeeks(1);
        } else if ("1m".equalsIgnoreCase(period)) {
            startDate = LocalDate.now().minusMonths(1);
        } else if ("6m".equalsIgnoreCase(period)) {
            startDate = LocalDate.now().minusMonths(6);
        } else {
            startDate = LocalDate.now().minusMonths(3);
        }

        List<InvestorTrendAggregationProjection> aggregatedData = stockInvestorDailyRepository
                .findAggregatedInvestorTrend(startDate);

        Comparator<InvestorTrendAggregationProjection> comparator;
        if ("foreigner_buy".equalsIgnoreCase(type)) {
            comparator = Comparator.comparing(InvestorTrendAggregationProjection::getForeignerNetBuyAmount);
        } else if ("foreigner_sell".equalsIgnoreCase(type)) {
            comparator = Comparator.comparing(InvestorTrendAggregationProjection::getForeignerNetBuyAmount);
        } else if ("institution_buy".equalsIgnoreCase(type)) {
            comparator = Comparator.comparing(InvestorTrendAggregationProjection::getInstitutionNetBuyAmount);
        } else if ("institution_sell".equalsIgnoreCase(type)) {
            comparator = Comparator.comparing(InvestorTrendAggregationProjection::getInstitutionNetBuyAmount);
        } else if ("personal_buy".equalsIgnoreCase(type)) {
            comparator = Comparator.comparing(InvestorTrendAggregationProjection::getPersonalNetBuyAmount);
        } else if ("personal_sell".equalsIgnoreCase(type)) {
            comparator = Comparator.comparing(InvestorTrendAggregationProjection::getPersonalNetBuyAmount);
        } else {
            comparator = Comparator.comparing(InvestorTrendAggregationProjection::getForeignerNetBuyAmount);
        }

        boolean isSell = type.endsWith("_sell");
        if (!isSell) {
            comparator = comparator.reversed(); // Descending (positive high first)
        } else {
            // For sell, we want the most negative values first (Natural ASC)
            // No change needed
        }

        List<InvestorRankDto> ranks = aggregatedData.stream()
                .filter(d -> {
                    if ("foreigner_buy".equalsIgnoreCase(type))
                        return d.getForeignerNetBuyAmount() != null
                                && d.getForeignerNetBuyAmount().compareTo(BigDecimal.ZERO) > 0;
                    if ("institution_buy".equalsIgnoreCase(type))
                        return d.getInstitutionNetBuyAmount() != null
                                && d.getInstitutionNetBuyAmount().compareTo(BigDecimal.ZERO) > 0;
                    if ("personal_buy".equalsIgnoreCase(type))
                        return d.getPersonalNetBuyAmount() != null
                                && d.getPersonalNetBuyAmount().compareTo(BigDecimal.ZERO) > 0;
                    if ("foreigner_sell".equalsIgnoreCase(type))
                        return d.getForeignerNetBuyAmount() != null
                                && d.getForeignerNetBuyAmount().compareTo(BigDecimal.ZERO) < 0;
                    if ("institution_sell".equalsIgnoreCase(type))
                        return d.getInstitutionNetBuyAmount() != null
                                && d.getInstitutionNetBuyAmount().compareTo(BigDecimal.ZERO) < 0;
                    if ("personal_sell".equalsIgnoreCase(type))
                        return d.getPersonalNetBuyAmount() != null
                                && d.getPersonalNetBuyAmount().compareTo(BigDecimal.ZERO) < 0;
                    return true;
                })
                .sorted(comparator)
                .limit(limit)
                .map(d -> InvestorRankDto.builder()
                        .stockCode(d.getStockCode())
                        .stockName(d.getStockName())
                        .stockType(d.getStockType())
                        .personalNetBuyAmount(d.getPersonalNetBuyAmount() != null
                                ? d.getPersonalNetBuyAmount().toString()
                                : "0")
                        .foreignerNetBuyAmount(d.getForeignerNetBuyAmount() != null
                                ? d.getForeignerNetBuyAmount().toString()
                                : "0")
                        .institutionNetBuyAmount(d.getInstitutionNetBuyAmount() != null
                                ? d.getInstitutionNetBuyAmount().toString()
                                : "0")
                        .build())
                .collect(Collectors.toList());

        for (int i = 0; i < ranks.size(); i++) {
            ranks.get(i).setRank(String.valueOf(i + 1));
        }

        return InvestorRankResponseDto.builder().ranks(ranks).build();
    }
}
