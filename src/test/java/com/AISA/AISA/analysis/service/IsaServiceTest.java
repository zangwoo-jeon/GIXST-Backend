package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.dto.IsaDTOs.IsaAccountType;
import com.AISA.AISA.analysis.dto.IsaDTOs.IsaBacktestRequest;
import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.Stock.StockType;
import com.AISA.AISA.kisStock.enums.MarketType;
import com.AISA.AISA.kisStock.exception.KisApiErrorCode;
import com.AISA.AISA.portfolio.PortfolioStock.PortStock;
import com.AISA.AISA.portfolio.PortfolioStock.PortStockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class IsaServiceTest {

    @Mock
    private PortStockRepository portStockRepository;

    @InjectMocks
    private IsaService isaService;

    @Test
    @DisplayName("ISA 백테스트 시 해외 주식이 포함되어 있으면 BusinessException이 발생해야 한다")
    public void calculateIsaBenefitsByBacktest_ShouldThrowException_WhenOverseasStockIncluded() {
        // Given
        UUID portId = UUID.randomUUID();
        IsaBacktestRequest request = new IsaBacktestRequest();
        request.setPortId(portId);
        request.setStartDate("20240101");
        request.setEndDate("20241231");
        request.setAccountType(IsaAccountType.GENERAL);

        // Mock Stocks
        Stock domesticStock = Stock.create("005930", "삼성전자", MarketType.KOSPI);

        Stock overseasStock = Stock.createOverseas("AAPL", "Apple", MarketType.NAS);
        // Note: createOverseas sets type to US_STOCK, but let's be explicit if needed.
        // stock.updateStockType(StockType.US_STOCK) is already called in
        // createOverseas.

        PortStock ps1 = mock(PortStock.class);
        when(ps1.getStock()).thenReturn(domesticStock);

        PortStock ps2 = mock(PortStock.class);
        when(ps2.getStock()).thenReturn(overseasStock);

        when(portStockRepository.findByPortfolio_PortId(portId)).thenReturn(List.of(ps1, ps2));

        // When & Then
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            isaService.calculateIsaBenefitsByBacktest(request);
        });

        assertEquals(KisApiErrorCode.INVALID_STOCK_TYPE, exception.getErrorCode());
    }
}
