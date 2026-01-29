package com.AISA.AISA.kisStock.kisController;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockGrowthIndicator;
import com.AISA.AISA.kisStock.dto.StockGrowthRankingDto;
import com.AISA.AISA.kisStock.dto.StockPrice.StockPriceDto;
import com.AISA.AISA.kisStock.kisService.StockGrowthAnalysisService;
import com.AISA.AISA.kisStock.kisService.KisStockService;
import com.AISA.AISA.kisStock.repository.StockGrowthIndicatorRepository;
import com.AISA.AISA.kisStock.repository.StockRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/stocks/growth")
@RequiredArgsConstructor
@Tag(name = "주식 성장성 분석 API", description = "국내 주식 매출/영업이익/EPS 성장률 및 랭킹 관련 API")
public class StockGrowthRankingController {

    private final StockGrowthAnalysisService growthAnalysisService;
    private final StockGrowthIndicatorRepository growthIndicatorRepository;
    private final StockRepository stockRepository;
    private final KisStockService kisStockService;

    @PostMapping("/calculate")
    @Operation(summary = "성장성 지표 일괄 계산", description = "모든 국내 주식의 YoY, 3Y CAGR 지표를 계산하여 DB에 저장합니다. (0:년, 1:분기)")
    public ResponseEntity<SuccessResponse<String>> calculateIndicators(
            @RequestParam(defaultValue = "0") String divCode) {
        new Thread(() -> growthAnalysisService.calculateGrowthIndicators(divCode)).start();
        return ResponseEntity.ok(new SuccessResponse<>(true, "성장성 지표 계산 시작 (백그라운드)", "Started for divCode=" + divCode));
    }

    @GetMapping("/ranking")
    @Operation(summary = "성장성 랭킹 조회", description = "매출/영업이익/EPS 중 하나를 선택하여 성장률 랭킹을 조회합니다. (metric: sales, op, eps / type: yoy, cagr)")
    @Cacheable(value = "growthRanking", key = "#metric + '-' + #type + '-' + #limit")
    public ResponseEntity<SuccessResponse<StockGrowthRankingDto>> getGrowthRanking(
            @RequestParam(defaultValue = "sales") String metric,
            @RequestParam(defaultValue = "yoy") String type,
            @RequestParam(defaultValue = "20") int limit) {

        String annualDivCode = "0"; // 고정: 연간 데이터만 조회
        PageRequest pageRequest = PageRequest.of(0, limit);
        List<StockGrowthIndicator> topIndicators;

        if ("sales".equalsIgnoreCase(metric)) {
            topIndicators = "cagr".equalsIgnoreCase(type)
                    ? growthIndicatorRepository.findByDivCodeOrderBySales3YCagrDesc(annualDivCode, pageRequest)
                    : growthIndicatorRepository.findByDivCodeOrderBySalesYoYDesc(annualDivCode, pageRequest);
        } else if ("op".equalsIgnoreCase(metric)) {
            topIndicators = "cagr".equalsIgnoreCase(type)
                    ? growthIndicatorRepository.findByDivCodeOrderByOp3YCagrDesc(annualDivCode, pageRequest)
                    : growthIndicatorRepository.findByDivCodeOrderByOpYoYDesc(annualDivCode, pageRequest);
        } else {
            topIndicators = "cagr".equalsIgnoreCase(type)
                    ? growthIndicatorRepository.findByDivCodeOrderByEps3YCagrDesc(annualDivCode, pageRequest)
                    : growthIndicatorRepository.findByDivCodeOrderByEpsYoYDesc(annualDivCode, pageRequest);
        }

        List<Stock> stocks = stockRepository.findAll();
        Map<String, Stock> stockMap = stocks.stream().collect(Collectors.toMap(Stock::getStockCode, s -> s));

        List<StockGrowthRankingDto.GrowthRankingEntry> entries = new ArrayList<>();
        int rank = 1;

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        for (StockGrowthIndicator indicator : topIndicators) {
            Stock s = stockMap.get(indicator.getStockCode());
            String stockName = (s != null) ? s.getStockName() : "Unknown";
            String market = (s != null) ? s.getMarketName().name() : "-";

            StockPriceDto price = kisStockService.getStockPrice(indicator.getStockCode());

            entries.add(StockGrowthRankingDto.GrowthRankingEntry.builder()
                    .rank(String.valueOf(rank++))
                    .stockCode(indicator.getStockCode())
                    .stockName(stockName)
                    .marketName(market)
                    .stacYymm(indicator.getStacYymm())
                    .salesGrowth(
                            indicator.getSalesYoY() != null ? String.format("%.2f%%", indicator.getSalesYoY()) : "N/A")
                    .opGrowth(indicator.getOpYoY() != null ? String.format("%.2f%%", indicator.getOpYoY()) : "N/A")
                    .epsGrowth(indicator.getEpsYoY() != null ? String.format("%.2f%%", indicator.getEpsYoY()) : "N/A")
                    .isTurnaround(indicator.isTurnaround())
                    .calculatedAt(indicator.getCalculatedAt().format(formatter))
                    .currentPrice(price != null ? price.getStockPrice() : "0")
                    .marketCap(price != null ? price.getMarketCap() : "0")
                    .build());

            // Overwrite with CAGR if requested
            if ("cagr".equalsIgnoreCase(type)) {
                StockGrowthRankingDto.GrowthRankingEntry last = entries.get(entries.size() - 1);
                last.setSalesGrowth(
                        indicator.getSales3YCagr() != null ? String.format("%.2f%%", indicator.getSales3YCagr())
                                : "N/A");
                last.setOpGrowth(
                        indicator.getOp3YCagr() != null ? String.format("%.2f%%", indicator.getOp3YCagr()) : "N/A");
                last.setEpsGrowth(
                        indicator.getEps3YCagr() != null ? String.format("%.2f%%", indicator.getEps3YCagr()) : "N/A");
            }
        }

        return ResponseEntity
                .ok(new SuccessResponse<>(true, metric.toUpperCase() + " " + type.toUpperCase() + " 성장률 랭킹 조회 성공",
                        StockGrowthRankingDto.builder().ranks(entries).build()));
    }
}
