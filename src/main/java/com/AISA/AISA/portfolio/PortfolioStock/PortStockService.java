package com.AISA.AISA.portfolio.PortfolioStock;

import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.kisOverseasStock.service.KisOverseasStockService;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.dto.StockPrice.StockPriceDto;
import com.AISA.AISA.kisStock.kisService.KisMacroService;
import com.AISA.AISA.kisStock.kisService.KisStockService;
import com.AISA.AISA.kisStock.repository.StockRepository;
import com.AISA.AISA.portfolio.PortfolioGroup.Portfolio;
import com.AISA.AISA.portfolio.PortfolioGroup.PortfolioRepository;
import com.AISA.AISA.portfolio.PortfolioGroup.exception.PortfolioErrorCode;
import com.AISA.AISA.portfolio.PortfolioStock.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import static java.util.stream.Collectors.toList;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class PortStockService {
    private final PortStockRepository portStockRepository;
    private final PortfolioRepository portfolioRepository;
    private final StockRepository stockRepository;
    private final KisStockService kisStockService;
    private final KisOverseasStockService kisOverseasStockService;
    private final KisMacroService kisMacroService;

    @Transactional
    public PortStock addStock(UUID portId, PortStockAddRequest request) {
        Portfolio portfolio = portfolioRepository.findById(portId)
                .orElseThrow(() -> new BusinessException(PortfolioErrorCode.PORTFOLIO_NOT_FOUND));

        Stock stock = stockRepository.findByStockCode(request.getStockCode())
                .orElseThrow(() -> new BusinessException(PortfolioErrorCode.STOCK_NOT_FOUND));

        // 이미 존재하는 종목인지 확인
        return portStockRepository.findByPortfolio_PortIdAndStock_StockCode(portId, request.getStockCode())
                .map(existingStock -> {
                    // 존재하면 수량 및 평단가 업데이트 (가중 평균)
                    int newQuantity = existingStock.getQuantity() + request.getQuantity();
                    BigDecimal totalCost = existingStock.getAveragePrice()
                            .multiply(BigDecimal.valueOf(existingStock.getQuantity()))
                            .add(request.getAveragePrice()
                                    .multiply(BigDecimal.valueOf(request.getQuantity())));
                    BigDecimal newAveragePrice = totalCost.divide(BigDecimal.valueOf(newQuantity),
                            RoundingMode.HALF_UP);

                    existingStock.updateQuantity(newQuantity);
                    existingStock.updateAveragePrice(newAveragePrice);
                    return existingStock;
                })
                .orElseGet(() -> {
                    // 존재하지 않으면 새로 생성
                    Integer maxSequence = portStockRepository.findMaxSequenceByPortfolioId(portId);
                    int nextSequence = (maxSequence == null) ? 1 : maxSequence + 1;

                    PortStock newStock = new PortStock(
                            portfolio,
                            stock,
                            request.getQuantity(),
                            request.getAveragePrice(),
                            nextSequence);
                    return portStockRepository.save(newStock);
                });
    }

    @Transactional
    public void removeStock(UUID portStockId) {
        PortStock portStock = portStockRepository.findById(portStockId)
                .orElseThrow(() -> new BusinessException(PortfolioErrorCode.PORTFOLIO_STOCK_NOT_FOUND));

        int deletedSequence = portStock.getSequence();
        UUID portId = portStock.getPortfolio().getPortId();

        portStockRepository.delete(portStock);

        // 삭제된 종목보다 뒤에 있는 종목들의 sequence를 1씩 당김
        List<PortStock> stocksToShift = portStockRepository.findByPortfolio_PortIdAndSequenceGreaterThan(portId,
                deletedSequence);
        for (PortStock stock : stocksToShift) {
            stock.updateSequence(stock.getSequence() - 1);
        }
    }

    @Transactional
    public void updateStock(UUID portStockId, PortStockUpdateRequest request) {
        PortStock portStock = portStockRepository.findById(portStockId)
                .orElseThrow(() -> new BusinessException(PortfolioErrorCode.PORTFOLIO_STOCK_NOT_FOUND));

        if (request.getQuantity() != null) {
            portStock.updateQuantity(request.getQuantity());
        }
        if (request.getAveragePrice() != null) {
            portStock.updateAveragePrice(request.getAveragePrice());
        }
        if (request.getSequence() != null) {
            int newSequence = request.getSequence();
            int oldSequence = portStock.getSequence();
            UUID portId = portStock.getPortfolio().getPortId();

            if (newSequence != oldSequence) {
                long totalCount = portStockRepository.countByPortfolio_PortId(portId);
                if (newSequence < 1 || newSequence > totalCount) {
                    throw new BusinessException(PortfolioErrorCode.INVALID_SEQUENCE);
                }

                if (newSequence < oldSequence) {
                    // 위로 이동: newSequence ~ oldSequence - 1 까지의 종목들을 +1
                    List<PortStock> stocksToShift = portStockRepository.findByPortfolio_PortIdAndSequenceBetween(portId,
                            newSequence, oldSequence - 1);
                    for (PortStock stock : stocksToShift) {
                        stock.updateSequence(stock.getSequence() + 1);
                    }
                } else {
                    // 아래로 이동: oldSequence + 1 ~ newSequence 까지의 종목들을 -1
                    List<PortStock> stocksToShift = portStockRepository.findByPortfolio_PortIdAndSequenceBetween(portId,
                            oldSequence + 1, newSequence);
                    for (PortStock stock : stocksToShift) {
                        stock.updateSequence(stock.getSequence() - 1);
                    }
                }
                portStock.updateSequence(newSequence);
            }
        }
    }

    public PortfolioReturnResponse getPortStocks(UUID portId) {
        Portfolio portfolio = portfolioRepository.findById(portId)
                .orElseThrow(() -> new BusinessException(PortfolioErrorCode.PORTFOLIO_NOT_FOUND));

        List<PortStock> portStocks = portStockRepository.findByPortfolio_PortIdWithStockOrderBySequenceAsc(portId);

        // 최신 환율 정보 가져오기
        Double latestRate = kisMacroService.getLatestExchangeRate();
        BigDecimal exchangeRate = (latestRate != null) ? BigDecimal.valueOf(latestRate) : BigDecimal.valueOf(1350.0); // 기본값

        List<PortStockResponse> enrichedStocks = portStocks.stream()
                .map(portStock -> {
                    BigDecimal currentPrice = BigDecimal.ZERO;
                    BigDecimal dailyProfit = BigDecimal.ZERO;
                    BigDecimal dailyChangeRate = BigDecimal.ZERO;
                    BigDecimal applicableExchangeRate = BigDecimal.ONE;

                    Stock stock = portStock.getStock();
                    boolean isOverseas = stock.getStockType() == Stock.StockType.US_STOCK
                            || stock.getStockType() == Stock.StockType.US_ETF;

                    try {
                        StockPriceDto stockPriceDto;
                        if (isOverseas) {
                            stockPriceDto = kisOverseasStockService.getOverseasStockPrice(stock.getStockCode());
                            applicableExchangeRate = exchangeRate;
                        } else {
                            stockPriceDto = kisStockService.getStockPrice(stock.getStockCode());
                            applicableExchangeRate = BigDecimal.ONE;
                        }

                        if (stockPriceDto != null && stockPriceDto.getStockPrice() != null) {
                            currentPrice = new BigDecimal(stockPriceDto.getStockPrice());

                            if (stockPriceDto.getPriceChange() != null) {
                                BigDecimal priceChange = new BigDecimal(stockPriceDto.getPriceChange());
                                dailyProfit = priceChange.multiply(BigDecimal.valueOf(portStock.getQuantity()));
                            }

                            if (stockPriceDto.getChangeRate() != null) {
                                dailyChangeRate = new BigDecimal(stockPriceDto.getChangeRate());
                            }
                        }
                    } catch (Exception e) {
                        // API 호출 실패 시 0으로 처리, 로그 필요 시 추가
                        log.error("Error fetching price for stock {}: ", portStock.getStock().getStockCode(), e);
                    }
                    return new PortStockResponse(portStock, currentPrice, dailyProfit, dailyChangeRate,
                            applicableExchangeRate);
                })
                .sorted((a, b) -> a.getSequence().compareTo(b.getSequence()))
                .collect(toList());

        return new PortfolioReturnResponse(portfolio, enrichedStocks);
    }

    public PortfolioReturnResponse getPortfolioReturn(UUID portId) {
        return getPortStocks(portId);
    }
}
