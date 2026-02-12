package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.kisStock.Entity.stock.EtfConstituent;
import com.AISA.AISA.kisStock.Entity.stock.EtfDetail;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.dto.StockPrice.NaverEtfComponentDto;
import com.AISA.AISA.kisStock.repository.EtfConstituentRepository;
import com.AISA.AISA.kisStock.repository.EtfDetailRepository;
import com.AISA.AISA.kisStock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EtfService {

    private final EtfDetailRepository etfDetailRepository;
    private final StockRepository stockRepository;
    private final EtfConstituentRepository etfConstituentRepository;
    private final WebClient webClient;
    private final TransactionTemplate transactionTemplate;

    @Transactional(readOnly = true)
    public Optional<EtfDetail> getEtfDetail(String stockCode) {
        return stockRepository.findByStockCode(stockCode)
                .flatMap(stock -> etfDetailRepository.findById(stock.getStockId()));
    }

    @Transactional(readOnly = true)
    public List<EtfDetail> getTopEtfsByLowExpense(int limit) {
        return etfDetailRepository.findAll(Sort.by(Sort.Direction.ASC, "totalExpense"))
                .stream()
                .limit(limit)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EtfDetail> getEtfsByUnderlyingIndex(String indexName) {
        return etfDetailRepository.findAll().stream()
                .filter(detail -> detail.getUnderlyingIndex().contains(indexName))
                .toList();
    }

    public void syncAllConstituents() {
        List<Stock> etfs = stockRepository.findAll().stream()
                .filter(s -> s.getStockType() == Stock.StockType.DOMESTIC_ETF ||
                        s.getStockType() == Stock.StockType.FOREIGN_ETF)
                .toList();

        log.info("Starting bulk sync for {} ETFs", etfs.size());

        for (Stock etf : etfs) {
            try {
                // Sequential saving: each ETF sync is a separate transaction
                transactionTemplate.execute(status -> {
                    syncConstituents(etf.getStockCode());
                    return null;
                });

                // Small delay to prevent rate limit
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Failed to sync constituents for ETF {}: {}", etf.getStockCode(), e.getMessage());
            }
        }
        log.info("Bulk sync completed.");
    }

    @Transactional
    public void syncConstituents(String stockCode) {
        Stock etf = stockRepository.findByStockCode(stockCode)
                .orElseThrow(() -> new IllegalArgumentException("ETF not found: " + stockCode));

        if (etf.getStockType() != Stock.StockType.DOMESTIC_ETF && etf.getStockType() != Stock.StockType.FOREIGN_ETF) {
            throw new IllegalArgumentException("Constituent sync is only supported for domestic-listed ETFs.");
        }

        String url = "https://stock.naver.com/api/domestic/detail/" + stockCode + "/ETFComponent";

        List<NaverEtfComponentDto> components = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToFlux(NaverEtfComponentDto.class)
                .collectList()
                .block();

        if (components == null || components.isEmpty()) {
            log.warn("No constituents found for ETF: {}", stockCode);
            return;
        }

        // Clear existing constituents
        etfConstituentRepository.deleteByEtf(etf);

        for (NaverEtfComponentDto dto : components) {
            Stock constituent = findMatchingStock(dto);

            BigDecimal weight = BigDecimal.ZERO;
            try {
                weight = new BigDecimal(dto.getWeight());
            } catch (Exception e) {
                log.warn("Failed to parse weight for {}: {}", dto.getComponentName(), dto.getWeight());
            }

            // Refinement: use our internal stock code if matched, otherwise use raw symbol
            // from Naver
            String symbol = (constituent != null) ? constituent.getStockCode()
                    : (dto.getComponentReutersCode() != null ? dto.getComponentReutersCode()
                            : dto.getComponentItemCode());

            EtfConstituent ec = EtfConstituent.builder()
                    .etf(etf)
                    .constituent(constituent)
                    .componentName(dto.getComponentName())
                    .componentSymbol(symbol)
                    .weight(weight)
                    .lastUpdated(LocalDateTime.now())
                    .build();

            etfConstituentRepository.save(ec);
        }

        log.info("Successfully synced {} constituents for ETF: {}", components.size(), stockCode);
    }

    @Transactional(readOnly = true)
    public List<EtfConstituent> getConstituents(String stockCode) {
        Stock etf = stockRepository.findByStockCode(stockCode)
                .orElseThrow(() -> new IllegalArgumentException("ETF not found: " + stockCode));
        return etfConstituentRepository.findByEtf(etf);
    }

    private Stock findMatchingStock(NaverEtfComponentDto dto) {
        // 1. Domestic Match (by componentItemCode)
        if (dto.getComponentItemCode() != null && !dto.getComponentItemCode().isBlank()) {
            return stockRepository.findByStockCode(dto.getComponentItemCode()).orElse(null);
        }

        // 2. Foreign Match (by componentReutersCode)
        if (dto.getComponentReutersCode() != null && !dto.getComponentReutersCode().isBlank()) {
            String ticker = dto.getComponentReutersCode();
            if (ticker.contains(".")) {
                ticker = ticker.split("\\.")[0];
            }
            Optional<Stock> stock = stockRepository.findByStockCode(ticker);
            if (stock.isPresent())
                return stock.get();
        }

        // 3. Name Match (Fallback)
        if (dto.getComponentName() != null && !dto.getComponentName().isBlank()) {
            return stockRepository.findAll().stream()
                    .filter(s -> s.getStockName().equals(dto.getComponentName()))
                    .findFirst()
                    .orElse(null);
        }

        return null;
    }
}
