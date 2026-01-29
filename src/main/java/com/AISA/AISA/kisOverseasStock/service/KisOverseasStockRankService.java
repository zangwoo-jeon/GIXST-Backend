package com.AISA.AISA.kisOverseasStock.service;

import com.AISA.AISA.kisOverseasStock.dto.OverseasStockRankDto;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockMarketCap;
import com.AISA.AISA.kisStock.repository.StockMarketCapRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class KisOverseasStockRankService {

    private final StockMarketCapRepository stockMarketCapRepository;

    /**
     * DB에 저장된 해외 주식 시가총액 순위를 조회합니다.
     * 
     * @param exchangeCode 거래소 코드 (NAS: 나스닥, NYS: 뉴욕, AMS: 아멕스, ALL: 전체)
     */
    public OverseasStockRankDto getMarketCapRanking(String exchangeCode) {
        // Ranking by marketCap (KRW converted value stored in DB)
        // Top 50 results
        Pageable pageable = PageRequest.of(0, 50);
        List<StockMarketCap> marketCaps = stockMarketCapRepository
                .findByStockStockTypeOrderByMarketCapDesc(Stock.StockType.US_STOCK, pageable);

        List<OverseasStockRankDto.RankItem> rankings = new ArrayList<>();
        int rank = 1;

        for (StockMarketCap smc : marketCaps) {
            Stock stock = smc.getStock();

            // 거래소 필터링 (ALL인 경우 전체)
            if (exchangeCode != null && !exchangeCode.isEmpty() && !"ALL".equals(exchangeCode)) {
                if (!stock.getMarketName().getExchangeCode().equals(exchangeCode)) {
                    continue;
                }
            }

            rankings.add(OverseasStockRankDto.RankItem.builder()
                    .rank(rank++)
                    .stockCode(stock.getStockCode())
                    .stockName(stock.getStockName())
                    .price(smc.getCurrentPrice())
                    .priceChange(smc.getPriceChange())
                    .changeRate(smc.getChangeRate())
                    .changeSign(smc.getChangeSign())
                    .marketCap(smc.getMarketCapUsd() != null ? smc.getMarketCapUsd().toString() : "0")
                    .marketCapKrw(smc.getMarketCap() != null ? smc.getMarketCap().toString() : "0")
                    .listedShares(smc.getListedShares() != null ? smc.getListedShares().toString() : "0")
                    .build());
        }

        return OverseasStockRankDto.builder()
                .rankings(rankings)
                .build();
    }
}
