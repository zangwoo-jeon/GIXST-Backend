package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.dto.MarketValuationDto;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockBalanceSheet;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialStatement;
import com.AISA.AISA.kisStock.Entity.stock.StockMarketCap;
import com.AISA.AISA.kisStock.enums.MarketType;
import com.AISA.AISA.kisStock.repository.StockBalanceSheetRepository;
import com.AISA.AISA.kisStock.repository.StockFinancialStatementRepository;
import com.AISA.AISA.kisStock.repository.StockMarketCapRepository;
import com.AISA.AISA.kisStock.repository.StockRepository;
import com.AISA.AISA.kisStock.repository.IndexDailyDataRepository;
import com.AISA.AISA.kisStock.repository.MarketInvestorDailyRepository;
import com.AISA.AISA.kisStock.repository.FuturesInvestorDailyRepository;
import com.AISA.AISA.kisStock.repository.StockDailyDataRepository;
import com.AISA.AISA.portfolio.macro.repository.MacroDailyDataRepository;
import com.AISA.AISA.kisStock.kisService.KisMacroService;
import com.AISA.AISA.kisStock.Entity.Index.IndexDailyData;
import com.AISA.AISA.portfolio.macro.dto.MacroIndicatorDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class MarketValuationServiceTest {

        @Mock
        private StockRepository stockRepository;
        @Mock
        private StockMarketCapRepository stockMarketCapRepository;
        @Mock
        private StockFinancialStatementRepository stockFinancialStatementRepository;
        @Mock
        private StockBalanceSheetRepository stockBalanceSheetRepository;
        @Mock
        private IndexDailyDataRepository indexDailyDataRepository;
        @Mock
        private MacroDailyDataRepository macroDailyDataRepository;
        @Mock
        private KisMacroService kisMacroService;

        @Mock
        private MarketInvestorDailyRepository marketInvestorDailyRepository;
        @Mock
        private FuturesInvestorDailyRepository futuresInvestorDailyRepository;
        @Mock
        private StockDailyDataRepository stockDailyDataRepository;
        @Mock
        private GeminiService geminiService;

        @InjectMocks
        private MarketValuationService marketValuationService;

        @Test
        public void testCalculateMarketValuation() {
                // Given
                MarketType market = MarketType.KOSPI;
                Stock stock1 = Stock.create("005930", "삼성전자", market);
                Stock stock2 = Stock.create("000660", "SK하이닉스", market);
                List<Stock> stocks = List.of(stock1, stock2);

                when(stockRepository.findByMarketName(market)).thenReturn(stocks);

                StockMarketCap cap1 = StockMarketCap.create(stock1, new BigDecimal("4000000"));
                StockMarketCap cap2 = StockMarketCap.create(stock2, new BigDecimal("1000000"));
                when(stockMarketCapRepository.findByStockIn(anyList())).thenReturn(List.of(cap1, cap2));

                // 10 years of earnings to satisfy CAPE (min 8y)
                List<StockFinancialStatement> hist = new ArrayList<>();
                int currentYear = LocalDate.now().getYear();
                for (int i = 1; i <= 10; i++) {
                        hist.add(StockFinancialStatement.builder()
                                        .stockCode("005930").stacYymm((currentYear - i) + "12").divCode("0")
                                        .netIncome(new BigDecimal("300000")).build());
                        hist.add(StockFinancialStatement.builder()
                                        .stockCode("000660").stacYymm((currentYear - i) + "12").divCode("0")
                                        .netIncome(new BigDecimal("100000")).build());
                }

                when(stockFinancialStatementRepository.findByStockCodeInAndDivCode(anyCollection(), anyString()))
                                .thenReturn(hist);

                StockBalanceSheet bs1 = StockBalanceSheet.builder()
                                .stockCode("005930").stacYymm("202412").divCode("0")
                                .totalCapital(new BigDecimal("4000000")).build();
                StockBalanceSheet bs2 = StockBalanceSheet.builder()
                                .stockCode("000660").stacYymm("1000000").divCode("0")
                                .totalCapital(new BigDecimal("1000000")).build();

                when(stockBalanceSheetRepository.findByStockCodeInAndDivCode(anyCollection(), anyString()))
                                .thenReturn(List.of(bs1, bs2));

                // Basic Mocks
                when(marketInvestorDailyRepository.findTop30ByMarketCodeOrderByDateDesc(anyString()))
                                .thenReturn(List.of());
                when(geminiService.generateMarketStrategy(any()))
                                .thenReturn(new GeminiService.StrategyResult("AI Valuation", "AI Trend",
                                                "AI Combined"));
                when(kisMacroService.fetchMacroData(anyString(), anyString(), anyString(), anyString(), anyString(),
                                anyString())).thenReturn(List.of());
                when(kisMacroService.getLatestBondYield(any())).thenReturn(new BigDecimal("3.50"));
                when(macroDailyDataRepository.findAllByStatCodeAndItemCodeAndDateBetweenOrderByDateAsc(anyString(),
                                anyString(), any(), any()))
                                .thenReturn(List.of());

                // When
                MarketValuationDto result = marketValuationService.calculateMarketValuation(market);

                // Then
                assertNotNull(result);
                assertEquals(market, result.getMarket());

                // CAPE Mocks (Updated for second call)
                when(kisMacroService.fetchMacroData(anyString(), anyString(), anyString(), anyString(), anyString(),
                                anyString())).thenReturn(List.of(
                                                new MacroIndicatorDto("20240101", "100.0")));

                // Mock Index History for KNN (Needs data > 30 days old)
                IndexDailyData today = IndexDailyData.builder().marketName("KOSPI").date(LocalDate.now())
                                .closingPrice(new BigDecimal("2500")).build();
                IndexDailyData monthAgo = IndexDailyData.builder().marketName("KOSPI")
                                .date(LocalDate.now().minusDays(40)).closingPrice(new BigDecimal("2400")).build(); // Valid
                                                                                                                   // for
                                                                                                                   // KNN
                IndexDailyData twoMonthsAgo = IndexDailyData.builder().marketName("KOSPI")
                                .date(LocalDate.now().minusDays(70)).closingPrice(new BigDecimal("2300")).build(); // Valid
                                                                                                                   // for
                                                                                                                   // KNN

                when(indexDailyDataRepository.findAllByMarketNameAndDateBetweenOrderByDateDesc(anyString(), any(),
                                any())).thenReturn(List.of(today, monthAgo, twoMonthsAgo));

                // Re-run to test CAPE and Signals
                result = marketValuationService.calculateMarketValuation(market);
                assertNotNull(result.getValuation().getCape());
                assertNotNull(result.getValuationAnalysis().getActionSignal());
                assertNotNull(result.getTrendAnalysis().getActionSignal());
                assertNotNull(result.getInvestmentStrategy());
                assertNotNull(result.getInvestmentStrategy().getFinalActionSignal());
                assertNotNull(result.getPredictionReport());
                assertNotNull(result.getPredictionReport().getShortTerm());
                assertNotNull(result.getPredictionReport().getMediumTerm());
                assertNotNull(result.getPredictionReport().getLongTerm());
                assertNotNull(result.getPredictionReport().getHistoricalMatch());
        }

        @Test
        public void testCalculateKOSDAQValuation() {
                MarketType market = MarketType.KOSDAQ;
                Stock stock1 = Stock.create("214150", "클래시스", market);
                List<Stock> stocks = List.of(stock1);

                when(stockRepository.findByMarketName(market)).thenReturn(stocks);
                StockMarketCap cap1 = StockMarketCap.create(stock1, new BigDecimal("40000"));
                when(stockMarketCapRepository.findByStockIn(anyList())).thenReturn(List.of(cap1));

                StockFinancialStatement stmt1 = StockFinancialStatement.builder()
                                .stockCode("214150").stacYymm("202412").divCode("0").netIncome(new BigDecimal("1000"))
                                .build();
                when(stockFinancialStatementRepository.findByStockCodeInAndDivCode(anyCollection(), anyString()))
                                .thenReturn(List.of(stmt1));

                StockBalanceSheet bs1 = StockBalanceSheet.builder()
                                .stockCode("214150").stacYymm("202412").divCode("0")
                                .totalCapital(new BigDecimal("10000")).build();
                when(stockBalanceSheetRepository.findByStockCodeInAndDivCode(anyCollection(), anyString()))
                                .thenReturn(List.of(bs1));

                when(marketInvestorDailyRepository.findTop30ByMarketCodeOrderByDateDesc(anyString()))
                                .thenReturn(List.of());

                MarketValuationDto result = marketValuationService.calculateMarketValuation(market);
                assertNotNull(result);
                assertEquals(market, result.getMarket());
                assertNotNull(result.getPredictionReport());
        }
}
