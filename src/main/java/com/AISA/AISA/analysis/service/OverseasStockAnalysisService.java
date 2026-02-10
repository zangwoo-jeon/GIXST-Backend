package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.entity.OverseasStockStaticAnalysis;
import com.AISA.AISA.analysis.repository.OverseasStockStaticAnalysisRepository;
import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.kisOverseasStock.entity.OverseasStockFinancialStatement;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockFinancialStatementRepository;
import com.AISA.AISA.kisOverseasStock.repository.KisOverseasStockRepository;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.exception.KisApiErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class OverseasStockAnalysisService {

    private final OverseasStockStaticAnalysisRepository staticAnalysisRepository;
    private final KisOverseasStockRepository overseasStockRepository;
    private final KisOverseasStockFinancialStatementRepository financialStatementRepository;
    private final GeminiService geminiService;

    @Transactional
    @CacheEvict(value = "overseasStaticAnalysis", key = "#stockCode", condition = "#refresh")
    public String getStaticAnalysis(String stockCode, boolean refresh) {
        if (refresh) {
            staticAnalysisRepository.deleteByStockCode(stockCode);
        }
        return getStaticAnalysis(stockCode);
    }

    @Transactional
    @Cacheable(value = "overseasStaticAnalysis", key = "#stockCode")
    public String getStaticAnalysis(String stockCode) {
        Optional<OverseasStockStaticAnalysis> cachedAnalysis = staticAnalysisRepository.findByStockCode(stockCode);
        if (cachedAnalysis.isPresent()) {
            OverseasStockStaticAnalysis staticData = cachedAnalysis.get();
            // 1년(8760시간) 유효성 체크
            if (staticData.getContent() != null && !staticData.isExpired(8760)) {
                return staticData.getContent();
            }
        }

        Stock stock = overseasStockRepository.findByStockCode(stockCode)
                .filter(s -> s.getStockType() == Stock.StockType.US_STOCK || s.getStockType() == Stock.StockType.US_ETF)
                .orElseThrow(() -> new BusinessException(KisApiErrorCode.STOCK_NOT_FOUND));

        // 최근 연간 실적 조회 (참고 데이터용)
        List<OverseasStockFinancialStatement> recentAnnuals = financialStatementRepository
                .findByStockCodeAndDivCodeOrderByStacYymmAsc(stockCode, "0"); // 오름차순

        // 최신 3개년만 추림
        if (recentAnnuals.size() > 3) {
            recentAnnuals = recentAnnuals.subList(recentAnnuals.size() - 3, recentAnnuals.size());
        }

        String analysisContent = generateStaticAnalysisText(stock, recentAnnuals);

        if (cachedAnalysis.isPresent()) {
            OverseasStockStaticAnalysis existing = cachedAnalysis.get();
            existing.updateContent(analysisContent);
            staticAnalysisRepository.save(existing);
        } else {
            staticAnalysisRepository.save(OverseasStockStaticAnalysis.builder()
                    .stockCode(stockCode)
                    .content(analysisContent)
                    .lastModifiedDate(java.time.LocalDateTime.now())
                    .createdDate(java.time.LocalDateTime.now())
                    .build());
        }
        return analysisContent;
    }

    private String generateStaticAnalysisText(Stock stock, List<OverseasStockFinancialStatement> recentAnnuals) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("다음 미국 주식 종목의 **기업 개요**, **미래 성장 동력**, **리스크 요인**을 분석해줘.\n\n");
        prompt.append(String.format("종목명: %s (%s)\n", stock.getStockName(), stock.getStockCode()));

        if (recentAnnuals != null && !recentAnnuals.isEmpty()) {
            prompt.append("\n[최근 연간 실적 추이 (참고용)]\n");
            for (OverseasStockFinancialStatement s : recentAnnuals) {
                prompt.append(String.format("- %s: 매출 %s / 순이익 %s\n",
                        s.getStacYymm(),
                        s.getTotalRevenue() != null ? s.getTotalRevenue() : "N/A",
                        s.getNetIncome() != null ? s.getNetIncome() : "N/A"));
            }
        }

        prompt.append("\n[요청사항]\n");
        prompt.append("1. **Google Search**를 활용하여 최신 정보(뉴스, 공시)를 반영해.\n");
        prompt.append("2. 다음 3가지 항목만 작성해 (마크다운 헤더 필수):\n");
        prompt.append("   - **## 1. 기업 개요**: 비즈니스 모델 중심 요약\n");
        prompt.append("   - **## 2. 미래 성장 동력**: 구체적인 기술, 시장 확장성 등\n");
        prompt.append("   - **## 3. 리스크 요인**: 경쟁 심화, 규제 등 잠재적 악재\n");
        prompt.append("3. 가치평가(적정 주가 등)는 절대 포함하지 마.\n");
        prompt.append("4. 전문 투자자에게 보고하는 금융 전문가 톤으로 작성해.\n");

        return geminiService.generateAdvice(prompt.toString());
    }
}
