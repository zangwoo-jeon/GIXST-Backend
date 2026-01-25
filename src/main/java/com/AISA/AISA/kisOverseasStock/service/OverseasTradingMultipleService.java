package com.AISA.AISA.kisOverseasStock.service;

import com.AISA.AISA.kisOverseasStock.entity.OverseasStockTradingMultiple;
import com.AISA.AISA.kisOverseasStock.repository.OverseasStockTradingMultipleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OverseasTradingMultipleService {

    private final OverseasStockTradingMultipleRepository tradingMultipleRepository;

    public Optional<OverseasStockTradingMultiple> getTradingMultiples(String stockCode) {
        return tradingMultipleRepository.findByStockCode(stockCode);
    }
}
