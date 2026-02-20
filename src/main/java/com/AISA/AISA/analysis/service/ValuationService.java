package com.AISA.AISA.analysis.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.AISA.AISA.analysis.entity.StockStaticAnalysis;
import com.AISA.AISA.analysis.repository.StockStaticAnalysisRepository;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialStatement;
import com.AISA.AISA.kisStock.repository.StockFinancialStatementRepository;
import com.AISA.AISA.kisStock.repository.StockRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ValuationService {

    private final StockRepository stockRepository;
    private final StockFinancialStatementRepository stockFinancialStatementRepository;
    private final GeminiService geminiService;
    private final StockStaticAnalysisRepository stockStaticAnalysisRepository;

    public String getStaticAnalysis(String stockCode, boolean refresh) {
        if (refresh) {
            stockStaticAnalysisRepository.deleteByStockCode(stockCode);
        }
        return getStaticAnalysis(stockCode);
    }

    @Transactional
    @Cacheable(value = "staticAnalysis", key = "#stockCode")
    public String getStaticAnalysis(String stockCode) {
        Optional<StockStaticAnalysis> cachedAnalysis = stockStaticAnalysisRepository.findByStockCode(stockCode);
        if (cachedAnalysis.isPresent()) {
            StockStaticAnalysis staticData = cachedAnalysis.get();
            if (staticData.getContent() != null && !staticData.isExpired(8760))
                return staticData.getContent();
        }
        Stock stock = stockRepository.findByStockCode(stockCode)
                .orElseThrow(() -> new IllegalArgumentException("Stock not found: " + stockCode));
        List<StockFinancialStatement> recentAnnuals = stockFinancialStatementRepository
                .findTop5ByStockCodeAndDivCodeOrderByStacYymmDesc(stockCode, "0");
        String analysisContent = generateStaticAnalysisText(stock, recentAnnuals);
        if (cachedAnalysis.isPresent()) {
            StockStaticAnalysis existing = cachedAnalysis.get();
            existing.updateContent(analysisContent);
            stockStaticAnalysisRepository.save(existing);
        } else {
            stockStaticAnalysisRepository
                    .save(StockStaticAnalysis.builder().stockCode(stockCode).content(analysisContent)
                            .lastModifiedDate(LocalDateTime.now()).createdDate(LocalDateTime.now()).build());
        }
        return analysisContent;
    }

    private String generateStaticAnalysisText(Stock stock, List<StockFinancialStatement> recentAnnuals) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("다음 기업의 **기업 개요**, **미래 성장 동력**, **리스크 요인**을 분석해줘.\n\n");
        prompt.append(String.format("종목명: %s (%s)\n", stock.getStockName(), stock.getStockCode()));
        prompt.append(String.format("시장: %s\n", stock.getMarketName()));

        if (stock.getStockIndustries() != null && !stock.getStockIndustries().isEmpty()) {
            String industries = stock.getStockIndustries().stream()
                    .map(si -> si.getSubIndustry().getName())
                    .collect(java.util.stream.Collectors.joining(", "));
            prompt.append(String.format("업종: %s\n", industries));
        }

        if (recentAnnuals != null && !recentAnnuals.isEmpty()) {
            prompt.append("\n[연간 실적 추이 (참고용)]\n");
            for (int i = recentAnnuals.size() - 1; i >= 0; i--) {
                StockFinancialStatement s = recentAnnuals.get(i);
                prompt.append(String.format("- %s: 매출 %s / 영익 %s\n", s.getStacYymm(), s.getSaleAccount(),
                        s.getOperatingProfit()));
            }
        }
        prompt.append(
                "\n[요청사항]\n1. 기업의 **정확한 사업 영역(업종)**을 파악하여 분석해. 명칭이 비슷하더라도 다른 업종(예: 바이오 vs 패션)으로 오해하지 않도록 주의해.\n2. 다음 3가지 항목만 작성해 (마크다운 헤더 필수):\n   - **## 1. 기업 개요**\n   - **## 2. 미래 성장 동력**\n   - **## 3. 리스크 요인**\n3. 가치평가는 절대 포함하지 마.\n4. 금융 전문가 톤으로 요약.\n");
        return geminiService.generateAdvice(prompt.toString());
    }

}
