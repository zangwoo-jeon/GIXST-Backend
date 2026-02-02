package com.AISA.AISA.kisOverseasStock.service;

import com.AISA.AISA.kisOverseasStock.dto.FinancialRatioRankDto;
import com.AISA.AISA.kisOverseasStock.dto.OverseasStockRankDto;
import com.AISA.AISA.kisOverseasStock.entity.OverseasStockDailyData;
import com.AISA.AISA.kisOverseasStock.entity.OverseasStockDividendRank;
import com.AISA.AISA.kisOverseasStock.entity.OverseasStockFinancialRatio;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockDailyDataRepository;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockFinancialRatioRepository;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockRepository;
import com.AISA.AISA.kisOverseasStock.repository.OverseasStockDividendRankRepository;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockDividend;
import com.AISA.AISA.kisStock.Entity.stock.StockMarketCap;
import com.AISA.AISA.kisStock.dto.DividendRank.DividendRankDto;
import com.AISA.AISA.kisStock.repository.StockDividendRepository;
import com.AISA.AISA.kisStock.repository.StockMarketCapRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class KisOverseasStockRankService {

    private final StockMarketCapRepository stockMarketCapRepository;
    private final OverseasStockDividendRankRepository overseasStockDividendRankRepository;
    private final StockDividendRepository stockDividendRepository;
    private final KisOverseasStockRepository overseasStockRepository;
    private final KisOverseasStockDailyDataRepository dailyDataRepository;
    private final KisOverseasStockFinancialRatioRepository financialRatioRepository;
    private final KisOverseasStockService kisOverseasStockService;

    /**
     * 해외 주식 배당 수익률 순위 조회
     */
    public DividendRankDto getOverseasDividendRank() {
        List<OverseasStockDividendRank> rankList = overseasStockDividendRankRepository
                .findAllByOrderByRankAsc();

        List<DividendRankDto.DividendRankEntry> entries = rankList.stream()
                .map(entity -> {
                    // Try to get current price from StockMarketCap
                    String currentPrice = "0";
                    Optional<StockMarketCap> smc = stockMarketCapRepository
                            .findByStock_StockCode(entity.getStockCode());
                    if (smc.isPresent() && smc.get().getCurrentPrice() != null) {
                        currentPrice = smc.get().getCurrentPrice();
                    }

                    return DividendRankDto.DividendRankEntry.builder()
                            .rank(entity.getRank())
                            .stockCode(entity.getStockCode())
                            .stockName(entity.getStockName())
                            .dividendAmount(entity.getDividendAmount())
                            .dividendRate(entity.getDividendRate())
                            .currentPrice(currentPrice)
                            .build();
                })
                .collect(Collectors.toList());

        return DividendRankDto.builder()
                .ranks(entries)
                .build();
    }

    /**
     * 해외 주식 배당 순위 데이터 갱신
     */
    @Transactional
    public void refreshOverseasDividendRank() {
        log.info("Starting overseas dividend rank refresh...");

        List<Stock> usStocks = overseasStockRepository.findAllByStockType(Stock.StockType.US_STOCK);
        List<OverseasStockDividendRank> newRankList = new ArrayList<>();

        DateTimeFormatter RECORD_DATE_FORMATTER = DateTimeFormatter
                .ofPattern("yyyyMMdd");
        String endDate = java.time.LocalDate.now().format(RECORD_DATE_FORMATTER);
        String startDate = java.time.LocalDate.now().minusYears(1).format(RECORD_DATE_FORMATTER);

        for (Stock stock : usStocks) {
            try {
                List<StockDividend> dividends = stockDividendRepository
                        .findByStock_StockCodeAndRecordDateBetweenOrderByRecordDateDesc(
                                stock.getStockCode(), startDate, endDate);

                if (dividends.isEmpty())
                    continue;

                BigDecimal annualDividend = dividends.stream()
                        .map(StockDividend::getDividendAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                if (annualDividend.compareTo(BigDecimal.ZERO) == 0)
                    continue;

                // Get current price with Fallback logic
                BigDecimal currentPrice = BigDecimal.ZERO;

                // 1. Try StockMarketCap (Real-time or recent sync)
                Optional<StockMarketCap> smcOpt = stockMarketCapRepository.findByStock(stock);
                if (smcOpt.isPresent() && smcOpt.get().getCurrentPrice() != null) {
                    currentPrice = new BigDecimal(smcOpt.get().getCurrentPrice());
                }

                // 2. Fallback to DailyData (Most recent closing price) if market cap price is
                // unavailable
                if (currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    List<OverseasStockDailyData> recentData = dailyDataRepository
                            .findTop5ByStockOrderByDateDesc(stock);
                    if (!recentData.isEmpty()) {
                        currentPrice = recentData.get(0).getClosingPrice();
                    }
                }

                if (currentPrice.compareTo(BigDecimal.ZERO) <= 0)
                    continue;

                double yield = annualDividend.divide(currentPrice, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal(100)).doubleValue();

                newRankList.add(OverseasStockDividendRank.builder()
                        .stockCode(stock.getStockCode())
                        .stockName(stock.getStockName())
                        .dividendAmount(annualDividend.setScale(2, RoundingMode.HALF_UP).toString())
                        .dividendRate(String.format("%.2f", yield))
                        .rank(0)
                        .build());

            } catch (Exception e) {
                log.warn("Failed to calculate dividend rank for {}: {}", stock.getStockCode(), e.getMessage());
            }
        }

        // Sort by yield desc
        newRankList.sort((r1, r2) -> {
            Double d1 = Double.parseDouble(r1.getDividendRate());
            Double d2 = Double.parseDouble(r2.getDividendRate());
            return d2.compareTo(d1);
        });

        // Assign ranks (top 50)
        List<OverseasStockDividendRank> topRanked = new ArrayList<>();
        for (int i = 0; i < Math.min(newRankList.size(), 50); i++) {
            OverseasStockDividendRank old = newRankList.get(i);
            topRanked.add(OverseasStockDividendRank.builder()
                    .stockCode(old.getStockCode())
                    .stockName(old.getStockName())
                    .dividendAmount(old.getDividendAmount())
                    .dividendRate(old.getDividendRate())
                    .rank(i + 1)
                    .build());
        }

        overseasStockDividendRankRepository.deleteAll();
        overseasStockDividendRankRepository.saveAll(topRanked);
        log.info("Refreshed overseas dividend rank with {} stocks.", topRanked.size());
    }

    /**
     * DB에 저장된 해외 주식 시가총액 순위를 조회합니다.
     * 
     * @param exchangeCode 거래소 코드 (NAS: 나스닥, NYS: 뉴욕, AMS: 아멕스, ALL: 전체)
     * @param start        시작 순위 (1부터 시작)
     * @param end          종료 순위
     */
    public OverseasStockRankDto getMarketCapRanking(String exchangeCode, int start, int end) {
        if (start < 1)
            start = 1;
        if (end < start)
            end = start;

        int limit = end - start + 1;
        long offset = start - 1;

        // Ranking by marketCap (KRW converted value stored in DB)
        Pageable pageable = new com.AISA.AISA.global.util.OffsetBasedPageRequest(offset, limit);
        List<StockMarketCap> marketCaps;

        // 거래소 필터링 (ALL인 경우 전체, 아니면 특정 거래소)
        if (exchangeCode != null && !exchangeCode.isEmpty() && !"ALL".equals(exchangeCode)) {
            com.AISA.AISA.kisStock.enums.MarketType targetMarket = null;
            try {
                targetMarket = com.AISA.AISA.kisStock.enums.MarketType.valueOf(exchangeCode.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid exchange code requested: {}", exchangeCode);
            }

            if (targetMarket != null) {
                marketCaps = stockMarketCapRepository
                        .findByStockStockTypeAndStockMarketNameOrderByMarketCapDesc(Stock.StockType.US_STOCK,
                                targetMarket, pageable);
            } else {
                marketCaps = new ArrayList<>();
            }
        } else {
            marketCaps = stockMarketCapRepository
                    .findByStockStockTypeOrderByMarketCapDesc(Stock.StockType.US_STOCK, pageable);
        }

        List<OverseasStockRankDto.RankItem> rankings = new ArrayList<>();
        int rankSequence = start;

        for (StockMarketCap smc : marketCaps) {
            Stock stock = smc.getStock();

            String currentPrice = smc.getCurrentPrice();
            String priceChange = smc.getPriceChange();
            String changeRate = smc.getChangeRate();

            // Always fetch real-time price data from KIS API
            try {
                com.AISA.AISA.kisStock.dto.StockPrice.StockPriceDto priceDto = kisOverseasStockService
                        .getOverseasStockPrice(stock.getStockCode());
                if (priceDto != null) {
                    currentPrice = priceDto.getStockPrice();
                    priceChange = priceDto.getPriceChange();
                    changeRate = priceDto.getChangeRate();
                }
            } catch (Exception e) {
                log.warn("Failed to fetch real-time price for {}: {}", stock.getStockCode(), e.getMessage());
            }

            rankings.add(OverseasStockRankDto.RankItem.builder()
                    .rank(rankSequence++)
                    .stockCode(stock.getStockCode())
                    .stockName(stock.getStockName())
                    .price(currentPrice)
                    .priceChange(priceChange)
                    .changeRate(changeRate)
                    .marketCap(smc.getMarketCapUsd() != null ? smc.getMarketCapUsd().toString() : "0")
                    .marketCapKrw(smc.getMarketCap() != null ? smc.getMarketCap().toString() : "0")
                    .listedShares(smc.getListedShares() != null ? smc.getListedShares().toString() : "0")
                    .build());
        }

        return OverseasStockRankDto.builder()
                .rankings(rankings)
                .build();
    }

    /**
     * 해외 주식 재무 비율 랭킹 조회
     *
     * @param sortType  정렬 기준 (PER, PBR, ROE)
     * @param direction 정렬 방향 (ASC, DESC)
     * @param page      페이지 번호 (0부터 시작)
     * @param size      페이지 크기
     */
    public FinancialRatioRankDto getFinancialRatioRanking(String sortType,
            String direction, int page, int size) {
        // 1. 모든 종목의 최신 재무 데이터 조회
        List<OverseasStockFinancialRatio> ratios = financialRatioRepository
                .findLatestAnnualFinancialRatios();

        // 2. 유효한 데이터만 필터링 (해당 지표가 null이거나 0인 경우 제외)
        List<OverseasStockFinancialRatio> filteredRatios = ratios.stream()
                .filter(r -> {
                    if (r.getIsSuspended() != null && r.getIsSuspended())
                        return false;
                    switch (sortType.toUpperCase()) {
                        case "PER":
                            return r.getPer() != null && r.getPer().compareTo(BigDecimal.ZERO) > 0;
                        case "PBR":
                            return r.getPbr() != null && r.getPbr().compareTo(BigDecimal.ZERO) > 0;
                        case "ROE":
                            return r.getRoe() != null; // ROE can be negative
                        case "EPS":
                            return r.getEpsUsd() != null;
                        default:
                            return true;
                    }
                })
                .collect(Collectors.toList());

        // 3. 정렬 logic
        filteredRatios.sort((r1, r2) -> {
            BigDecimal v1, v2;
            switch (sortType.toUpperCase()) {
                case "PER":
                    v1 = r1.getPer();
                    v2 = r2.getPer();
                    break;
                case "PBR":
                    v1 = r1.getPbr();
                    v2 = r2.getPbr();
                    break;
                case "ROE":
                    v1 = r1.getRoe();
                    v2 = r2.getRoe();
                    break;
                case "EPS":
                    v1 = r1.getEpsUsd();
                    v2 = r2.getEpsUsd();
                    break;
                default:
                    v1 = BigDecimal.ZERO;
                    v2 = BigDecimal.ZERO;
            }

            if ("DESC".equalsIgnoreCase(direction)) {
                return v2.compareTo(v1);
            } else {
                return v1.compareTo(v2);
            }
        });

        // 4. Pagination
        int totalCount = filteredRatios.size();
        int totalPages = (int) Math.ceil((double) totalCount / size);
        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, totalCount);

        List<OverseasStockFinancialRatio> pagedRatios;
        if (fromIndex >= totalCount) {
            pagedRatios = new ArrayList<>();
        } else {
            pagedRatios = filteredRatios.subList(fromIndex, toIndex);
        }

        // 5. DTO 변환
        List<FinancialRatioRankDto.RankItem> rankItems = new ArrayList<>();
        int currentRank = fromIndex + 1;

        for (OverseasStockFinancialRatio ratio : pagedRatios) {
            String stockName = overseasStockRepository
                    .findByStockCodeAndStockType(ratio.getStockCode(), Stock.StockType.US_STOCK)
                    .map(Stock::getStockName)
                    .orElse(ratio.getStockCode());

            rankItems.add(FinancialRatioRankDto.RankItem.builder()
                    .rank(currentRank++)
                    .stockCode(ratio.getStockCode())
                    .stockName(stockName)
                    .per(ratio.getPer() != null ? ratio.getPer().toString() : "-")
                    .pbr(ratio.getPbr() != null ? ratio.getPbr().toString() : "-")
                    .roe(ratio.getRoe() != null ? ratio.getRoe().toString() : "-")
                    .eps(ratio.getEpsUsd() != null ? ratio.getEpsUsd().toString() : "-")
                    .bps(ratio.getBpsUsd() != null ? ratio.getBpsUsd().toString() : "-")
                    .build());
        }

        return FinancialRatioRankDto.builder()
                .rankings(rankItems)
                .totalCount(totalCount)
                .totalPages(totalPages)
                .currentPage(page)
                .build();
    }
}
