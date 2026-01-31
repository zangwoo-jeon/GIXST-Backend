package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.dto.MarketValuationDto;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockBalanceSheet;
import com.AISA.AISA.kisStock.Entity.stock.StockFinancialStatement;
import com.AISA.AISA.kisStock.Entity.stock.StockMarketCap;
import com.AISA.AISA.kisStock.enums.BondYield;
import com.AISA.AISA.kisStock.enums.MarketType;
import com.AISA.AISA.kisStock.repository.StockBalanceSheetRepository;
import com.AISA.AISA.kisStock.repository.StockFinancialStatementRepository;
import com.AISA.AISA.kisStock.repository.StockMarketCapRepository;
import com.AISA.AISA.kisStock.repository.StockRepository;
import com.AISA.AISA.kisStock.repository.IndexDailyDataRepository;
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
        private KisMacroService kisMacroService;

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

                // Units are in 100M KRW in DB
                // 4M * 10^8 = 400T KRW
                StockMarketCap cap1 = StockMarketCap.create(stock1, new BigDecimal("4000000"));
                StockMarketCap cap2 = StockMarketCap.create(stock2, new BigDecimal("1000000"));
                when(stockMarketCapRepository.findByStockIn(anyList())).thenReturn(List.of(cap1, cap2));

                // Income Statements (latest annual)
                StockFinancialStatement stmt1 = StockFinancialStatement.builder()
                                .stockCode("005930").stacYymm("202412").divCode("0").netIncome(new BigDecimal("300000"))
                                .build(); // 30T KRW
                StockFinancialStatement stmt2 = StockFinancialStatement.builder()
                                .stockCode("000660").stacYymm("202312").divCode("0").netIncome(new BigDecimal("100000"))
                                .build(); // 10T KRW

                when(stockFinancialStatementRepository.findByStockCodeInAndDivCode(anyCollection(), anyString()))
                                .thenReturn(List.of(stmt1, stmt2));

                // Balance Sheets (latest annual)
                StockBalanceSheet bs1 = StockBalanceSheet.builder()
                                .stockCode("005930").stacYymm("202412").divCode("0")
                                .totalCapital(new BigDecimal("4000000")).build(); // 400T KRW
                StockBalanceSheet bs2 = StockBalanceSheet.builder()
                                .stockCode("000660").stacYymm("1000000").divCode("0")
                                .totalCapital(new BigDecimal("1000000")).build(); // 100T KRW

                when(stockBalanceSheetRepository.findByStockCodeInAndDivCode(anyCollection(), anyString()))
                                .thenReturn(List.of(bs1, bs2));

                // When
                MarketValuationDto result = marketValuationService.calculateMarketValuation(market);

                // Then
                assertNotNull(result);
                assertEquals(market, result.getMarket());
                assertEquals(2, result.getMetadata().getStockCount());

                // Total Market Cap = 500T
                assertTrue(result.getMetadata().getTotalMarketCap().contains("500.0조 원"));

                // Total Net Income = 40T
                // This field is no longer in top level, but kept in metadata or removed?
                // Actually user's suggested DTO didn't have totalNetIncome, but I might want to
                // check metadata.
                // In my new DTO, metadata has totalMarketCap and stockCount.

                // PER = 500T / 40T = 12.5
                assertEquals(new BigDecimal("12.50"), result.getValuation().getPer());

                // PBR = 500T / 500T = 1.00
                assertEquals(new BigDecimal("1.00"), result.getValuation().getPbr());

                // CAPE Mocks
                when(kisMacroService.fetchMacroData(anyString(), anyString(), anyString(), anyString(), anyString(),
                                anyString())).thenReturn(List.of(
                                                new MacroIndicatorDto("20240101", "105.0"),
                                                new MacroIndicatorDto("20230101", "100.0")));

                when(kisMacroService.getLatestBondYield(BondYield.KR_10Y)).thenReturn(new BigDecimal("3.50"));

                when(indexDailyDataRepository.findAllByMarketNameAndDateBetweenOrderByDateDesc(anyString(), any(),
                                any())).thenReturn(List.of(
                                                IndexDailyData.builder().marketName("KOSPI")
                                                                .date(LocalDate.now())
                                                                .closingPrice(new BigDecimal("2500")).build()));

                // Re-run to test CAPE and Yield Gap
                result = marketValuationService.calculateMarketValuation(market);
                assertNotNull(result.getValuation().getCape());
                assertEquals(new BigDecimal("3.50"), result.getValuation().getBondYield());
                assertNotNull(result.getValuation().getYieldGap());
                assertNotNull(result.getTimeSeries());
                assertFalse(result.getTimeSeries().isEmpty());
                assertNotNull(result.getTimeSeries().get(0).getYieldGap());
                assertNotNull(result.getTotalScore());
                assertNotNull(result.getGrade());
        }

        @Test
        public void testCalculateKOSDAQValuation() {
                // Given
                MarketType market = MarketType.KOSDAQ;
                Stock stock1 = Stock.create("214150", "클래시스", market);
                Stock stock2 = Stock.create("086520", "에코프로", market);
                List<Stock> stocks = List.of(stock1, stock2);

                when(stockRepository.findByMarketName(market)).thenReturn(stocks);

                // 100M KRW units
                StockMarketCap cap1 = StockMarketCap.create(stock1, new BigDecimal("40000")); // 4T
                StockMarketCap cap2 = StockMarketCap.create(stock2, new BigDecimal("60000")); // 6T
                when(stockMarketCapRepository.findByStockIn(anyList())).thenReturn(List.of(cap1, cap2));

                StockFinancialStatement stmt1 = StockFinancialStatement.builder()
                                .stockCode("214150").stacYymm("202412").divCode("0").netIncome(new BigDecimal("1000"))
                                .build(); // 100B
                StockFinancialStatement stmt2 = StockFinancialStatement.builder()
                                .stockCode("086520").stacYymm("202412").divCode("0").netIncome(new BigDecimal("1000"))
                                .build(); // 100B

                when(stockFinancialStatementRepository.findByStockCodeInAndDivCode(anyCollection(), anyString()))
                                .thenReturn(List.of(stmt1, stmt2));

                StockBalanceSheet bs1 = StockBalanceSheet.builder()
                                .stockCode("214150").stacYymm("202412").divCode("0")
                                .totalCapital(new BigDecimal("10000")).build(); // 1T
                StockBalanceSheet bs2 = StockBalanceSheet.builder()
                                .stockCode("086520").stacYymm("202412").divCode("0")
                                .totalCapital(new BigDecimal("90000")).build(); // 9T

                when(stockBalanceSheetRepository.findByStockCodeInAndDivCode(anyCollection(), anyString()))
                                .thenReturn(List.of(bs1, bs2));

                // When
                MarketValuationDto result = marketValuationService.calculateMarketValuation(market);

                // Then
                assertNotNull(result);
                assertEquals(market, result.getMarket());

                // Total Market Cap = 10T
                assertTrue(result.getMetadata().getTotalMarketCap().contains("10.0조 원"));

                // PER = 10T / 200B = 50.00
                assertEquals(new BigDecimal("50.00"), result.getValuation().getPer());
        }
}
