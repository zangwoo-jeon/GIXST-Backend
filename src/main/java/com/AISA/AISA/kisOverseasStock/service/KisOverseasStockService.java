package com.AISA.AISA.kisOverseasStock.service;

import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockRepository;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.dto.StockSimpleSearchResponseDto; // Use Simple DTO
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class KisOverseasStockService {

    private final KisOverseasStockRepository overseasStockRepository;

    @Transactional(readOnly = true)
    public List<StockSimpleSearchResponseDto> searchOverseasStocks(String keyword) {
        // Search only US_STOCK type using specialized repository
        List<Stock> stocks = overseasStockRepository.findByKeyword(keyword);

        return stocks.stream()
                .map(stock -> StockSimpleSearchResponseDto.builder()
                        .stockCode(stock.getStockCode())
                        .stockName(stock.getStockName())
                        .marketName(stock.getMarketName())
                        .build())
                .collect(Collectors.toList());
    }
}
