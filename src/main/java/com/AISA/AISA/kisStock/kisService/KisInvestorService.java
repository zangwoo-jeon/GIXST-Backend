package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.dto.InvestorTrend.AccumulatedInvestorProjection;
import com.AISA.AISA.kisStock.dto.InvestorTrend.AccumulatedInvestorTrendDto;
import com.AISA.AISA.kisStock.repository.StockInvestorDailyRepository;
import com.AISA.AISA.kisStock.repository.StockRepository;
import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.kisStock.exception.KisApiErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

@Slf4j
@Service
@RequiredArgsConstructor
public class KisInvestorService {

        private final StockInvestorDailyRepository stockInvestorDailyRepository;
        private final StockRepository stockRepository;

        @Transactional(readOnly = true)
        public AccumulatedInvestorTrendDto getAccumulatedInvestorTrend(String stockCode, String period) {
                Stock stock = stockRepository.findByStockCode(stockCode)
                                .orElseThrow(() -> new BusinessException(KisApiErrorCode.STOCK_NOT_FOUND));

                if (stock.getStockType() != Stock.StockType.DOMESTIC) {
                        throw new BusinessException(KisApiErrorCode.INVALID_STOCK_TYPE);
                }

                LocalDate startDate;
                if ("1m".equalsIgnoreCase(period)) {
                        startDate = LocalDate.now().minusMonths(1);
                } else if ("1y".equalsIgnoreCase(period)) {
                        startDate = LocalDate.now().minusYears(1);
                } else {
                        // Default to 3 months if not specified or unrecognized
                        startDate = LocalDate.now().minusMonths(3);
                }

                AccumulatedInvestorProjection projection = stockInvestorDailyRepository
                                .findAggregatedInvestorTrendByStock(stock, startDate);

                if (projection == null) {
                        return AccumulatedInvestorTrendDto.builder()
                                        .personalNetBuyAmount(BigDecimal.ZERO)
                                        .foreignerNetBuyAmount(BigDecimal.ZERO)
                                        .institutionNetBuyAmount(BigDecimal.ZERO)
                                        .build();
                }

                return AccumulatedInvestorTrendDto.builder()
                                .personalNetBuyAmount(
                                                projection.getPersonalNetBuyAmount() != null
                                                                ? projection.getPersonalNetBuyAmount()
                                                                : BigDecimal.ZERO)
                                .foreignerNetBuyAmount(
                                                projection.getForeignerNetBuyAmount() != null
                                                                ? projection.getForeignerNetBuyAmount()
                                                                : BigDecimal.ZERO)
                                .institutionNetBuyAmount(
                                                projection.getInstitutionNetBuyAmount() != null
                                                                ? projection.getInstitutionNetBuyAmount()
                                                                : BigDecimal.ZERO)
                                .build();
        }
}
