package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.dto.OverseasQualityValuationDto.*;
import com.AISA.AISA.analysis.repository.OverseasQualityReportRepository;
import com.AISA.AISA.kisOverseasStock.entity.*;
import com.AISA.AISA.kisOverseasStock.repository.*;
import com.AISA.AISA.kisOverseasStock.service.KisOverseasStockInformationService;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class OverseasQualityAnalysisServiceTest {

    private OverseasQualityAnalysisService service;

    @Mock
    private KisOverseasStockRepository overseasStockRepository;
    @Mock
    private KisOverseasStockFinancialRatioRepository financialRatioRepository;
    @Mock
    private KisOverseasStockFinancialStatementRepository financialStatementRepository;
    @Mock
    private KisOverseasStockInformationService overseasService;
    @Mock
    private GeminiService geminiService;
    @Mock
    private KisOverseasStockCashFlowRepository cashFlowRepository;
    @Mock
    private KisOverseasStockBalanceSheetRepository balanceSheetRepository;
    @Mock
    private OverseasStockTradingMultipleRepository tradingMultipleRepository;
    @Mock
    private OverseasQualityReportRepository reportRepository;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new OverseasQualityAnalysisService(
                overseasStockRepository,
                financialRatioRepository,
                financialStatementRepository,
                overseasService,
                geminiService,
                cashFlowRepository,
                balanceSheetRepository,
                tradingMultipleRepository,
                reportRepository);
    }

    @Test
    void testCalculateQualityAnalysis_V4_ROIC() {
        String stockCode = "APP";
        Stock mockStock = Stock.createOverseas(stockCode, "AppLovin", null);

        when(overseasStockRepository.findByStockCode(stockCode)).thenReturn(Optional.of(mockStock));
        when(financialRatioRepository.findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(anyString(), anyString()))
                .thenReturn(new OverseasStockFinancialRatio());

        // Year 1 (t): High income, small IC
        OverseasStockFinancialStatement stmt1 = OverseasStockFinancialStatement.builder()
                .operatingIncome(new BigDecimal("1000"))
                .pretaxIncome(new BigDecimal("1000"))
                .incomeTax(new BigDecimal("100")) // Tax rate 10% -> clamp to 15%
                .totalRevenue(new BigDecimal("5000"))
                .build();

        // Year 2 (t-1):
        OverseasStockFinancialStatement stmt2 = OverseasStockFinancialStatement.builder()
                .operatingIncome(new BigDecimal("800"))
                .pretaxIncome(new BigDecimal("800"))
                .incomeTax(new BigDecimal("240")) // Tax rate 30% -> clamp to 30%
                .totalRevenue(new BigDecimal("4000"))
                .build();

        when(financialStatementRepository.findTop5ByStockCodeAndDivCodeOrderByStacYymmDesc(anyString(), anyString()))
                .thenReturn(Arrays.asList(stmt1, stmt2));

        // Year 1 (t): IC = 10000 - 2000 - 1000 = 7000
        OverseasStockBalanceSheet bs1 = OverseasStockBalanceSheet.builder()
                .totalAssets(new BigDecimal("10000"))
                .currentLiabilities(new BigDecimal("2000"))
                .cashAndEquivalents(new BigDecimal("1000"))
                .totalCapital(new BigDecimal("7000"))
                .build();
        // Year 2 (t-1): IC = 9000 - 2000 - 1000 = 6000
        OverseasStockBalanceSheet bs2 = OverseasStockBalanceSheet.builder()
                .totalAssets(new BigDecimal("9000"))
                .currentLiabilities(new BigDecimal("2000"))
                .cashAndEquivalents(new BigDecimal("1000"))
                .totalCapital(new BigDecimal("6000"))
                .build();
        // Year 3 (t-2): IC = 8000 - 2000 - 1000 = 5000
        OverseasStockBalanceSheet bs3 = OverseasStockBalanceSheet.builder()
                .totalAssets(new BigDecimal("8000"))
                .currentLiabilities(new BigDecimal("2000"))
                .cashAndEquivalents(new BigDecimal("1000"))
                .totalCapital(new BigDecimal("5000"))
                .build();

        when(balanceSheetRepository.findByStockCodeAndDivCodeOrderByStacYymmDesc(anyString(), anyString()))
                .thenReturn(Arrays.asList(bs1, bs2, bs3));

        when(overseasService.getCurrentPrice(anyString())).thenReturn(new BigDecimal("100"));
        when(geminiService.generateAdvice(anyString())).thenReturn(
                "{\"suitability\": \"OK\", \"moatDescription\": \"None\", \"monitoringPoints\": [], \"reEntryCondition\": \"None\"}");

        QualityReportResponse response = service.calculateQualityAnalysis(stockCode, true);

        // NOPAT t = 1000 * (1 - 0.15) = 850
        // Avg IC t = (7000 + 6000) / 2 = 6500
        // ROIC t = 850 / 6500 = 13.08%

        // NOPAT t-1 = 800 * (1 - 0.3) = 560
        // Avg IC t-1 = (6000 + 5000) / 2 = 5500
        // ROIC t-1 = 560 / 5500 = 10.18%

        // 2-yr Avg ROIC approx 11.6%

        assertNotNull(response);
        assertTrue(response.getBusinessQuality().getRoicVsWacc().contains("11.6"));
    }
}
