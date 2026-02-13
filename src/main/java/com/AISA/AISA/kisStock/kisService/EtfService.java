package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.kisStock.Entity.stock.EtfConstituent;
import com.AISA.AISA.kisStock.Entity.stock.EtfDetail;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.config.KisApiProperties;
import com.AISA.AISA.kisStock.dto.StockPrice.EtfConstituentResponseDto;
import com.AISA.AISA.kisStock.dto.StockPrice.EtfDetailResponseDto;
import com.AISA.AISA.kisStock.dto.StockPrice.KisEtfPriceResponseDto;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class EtfService {

    private final KisApiProperties kisApiProperties;
    private final KisApiClient kisApiClient;
    private final StockRepository stockRepository;
    private final EtfDetailRepository etfDetailRepository;
    private final EtfConstituentRepository etfConstituentRepository;
    private final WebClient webClient;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional(readOnly = true)
    public Optional<EtfDetailResponseDto> getEtfDetail(String stockCode) {
        return stockRepository.findByStockCode(stockCode)
                .flatMap(stock -> etfDetailRepository.findById(stock.getStockId()))
                .map(entity -> {
                    KisEtfPriceResponseDto.Output realTimeData = getEtfRealTimeData(stockCode);
                    String nav = null;
                    String trackingError = null;
                    String discrepancyRate = null;

                    if (realTimeData != null) {
                        nav = realTimeData.getNav();
                        trackingError = realTimeData.getTrcErrt();

                        // Calculate Discrepancy Rate: (Price - NAV) / NAV * 100
                        try {
                            double priceVal = Double.parseDouble(realTimeData.getStckPrpr());
                            double navVal = Double.parseDouble(realTimeData.getNav());
                            if (navVal != 0) {
                                double diff = ((priceVal - navVal) / navVal) * 100;
                                discrepancyRate = String.format("%.2f", diff);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to calculate discrepancy rate for {}: {}", stockCode, e.getMessage());
                        }
                    }

                    return EtfDetailResponseDto.of(entity, nav, trackingError, discrepancyRate);
                });
    }

    private KisEtfPriceResponseDto.Output getEtfRealTimeData(String stockCode) {
        try {
            KisEtfPriceResponseDto response = kisApiClient.fetch(token -> webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(kisApiProperties.getEtfPriceUrl())
                            .queryParam("fid_input_iscd", stockCode)
                            .queryParam("fid_cond_mrkt_div_code", "J")
                            .build())
                    .header("authorization", token)
                    .header("appkey", kisApiProperties.getAppkey())
                    .header("appsecret", kisApiProperties.getAppsecret())
                    .header("tr_id", "FHPST02400000")
                    .header("custtype", "P"), KisEtfPriceResponseDto.class);

            if (response != null && "0".equals(response.getRtCd())) {
                return response.getOutput();
            }
        } catch (Exception e) {
            log.error("Failed to fetch real-time ETF data for {}: {}", stockCode, e.getMessage());
        }
        return null;
    }

    @Transactional(readOnly = true)
    public List<EtfDetailResponseDto> getTopEtfsByLowExpense(int limit) {
        return etfDetailRepository.findAll(Sort.by(Sort.Direction.ASC, "totalExpense"))
                .stream()
                .limit(limit)
                .map(EtfDetailResponseDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EtfDetailResponseDto> getEtfsByUnderlyingIndex(String indexName) {
        return etfDetailRepository.findByUnderlyingIndexContaining(indexName).stream()
                .map(EtfDetailResponseDto::from)
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

    public void syncAllUsEtfConstituents() {
        List<Stock> etfs = stockRepository.findByStockType(Stock.StockType.US_ETF);

        log.info("Starting bulk sync for {} US ETFs", etfs.size());

        for (Stock etf : etfs) {
            try {
                // Sequential saving
                transactionTemplate.execute(status -> {
                    syncUsEtfConstituents(etf);
                    return null;
                });

                // Small delay
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Failed to sync constituents for US ETF {}: {}", etf.getStockCode(), e.getMessage());
            }
        }
        log.info("US ETF Bulk sync completed.");
    }

    @Transactional
    public void syncConstituents(String stockCode) {
        Stock etf = stockRepository.findByStockCode(stockCode)
                .orElseThrow(() -> new IllegalArgumentException("ETF not found: " + stockCode));

        if (etf.getStockType() == Stock.StockType.US_ETF) {
            syncUsEtfConstituents(etf);
            return;
        }

        if (etf.getStockType() != Stock.StockType.DOMESTIC_ETF && etf.getStockType() != Stock.StockType.FOREIGN_ETF) {
            throw new IllegalArgumentException(
                    "Constituent sync is only supported for domestic-listed ETFs and US ETFs.");
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
    public List<EtfConstituentResponseDto> getConstituents(String stockCode) {
        Stock etf = stockRepository.findByStockCode(stockCode)
                .orElseThrow(() -> new IllegalArgumentException("ETF not found: " + stockCode));
        return etfConstituentRepository.findByEtfOrderByWeightDesc(etf).stream()
                .limit(10)
                .map(EtfConstituentResponseDto::from)
                .toList();
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

    private void syncUsEtfConstituents(Stock etf) {
        String scriptPath = "src/main/resources/scripts/fetch_us_etf_holdings.py";
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("python", scriptPath, etf.getStockCode());
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.error("Python script failed for {}: {}", etf.getStockCode(), output);
                return;
            }

            String jsonOutput = output.toString();
            if (jsonOutput.trim().isEmpty() || jsonOutput.equals("[]")) {
                log.warn("No Top 10 holdings found for US ETF: {}", etf.getStockCode());
                return;
            }

            List<Map<String, Object>> holdings = objectMapper.readValue(jsonOutput,
                    new TypeReference<List<Map<String, Object>>>() {
                    });

            if (holdings.isEmpty()) {
                return;
            }

            // Clear existing constituents
            etfConstituentRepository.deleteByEtf(etf);

            for (Map<String, Object> holding : holdings) {
                String ticker = (String) holding.get("ticker");
                String name = (String) holding.get("name");
                Double weightVal = (Double) holding.get("weight");

                BigDecimal weight = (weightVal != null)
                        ? BigDecimal.valueOf(weightVal).multiply(BigDecimal.valueOf(100))
                        : BigDecimal.ZERO;

                // Look for US_STOCK in DB
                Stock constituent = stockRepository.findByStockCode(ticker).orElse(null);

                EtfConstituent ec = EtfConstituent.builder()
                        .etf(etf)
                        .constituent(constituent)
                        .componentName(name)
                        .componentSymbol(ticker)
                        .weight(weight)
                        .lastUpdated(LocalDateTime.now())
                        .build();

                etfConstituentRepository.save(ec);
            }
            log.info("Successfully synced {} Top 10 holdings for US ETF: {}", holdings.size(), etf.getStockCode());

        } catch (Exception e) {
            log.error("Error syncing US ETF holdings for {}: {}", etf.getStockCode(), e.getMessage());
            throw new RuntimeException(e);
        }
    }
}
