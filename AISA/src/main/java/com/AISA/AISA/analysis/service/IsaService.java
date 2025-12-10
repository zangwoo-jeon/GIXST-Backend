package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.dto.IsaDTOs.IsaAccountType;
import com.AISA.AISA.analysis.dto.IsaDTOs.IsaBacktestRequest;
import com.AISA.AISA.analysis.dto.IsaDTOs.IsaBacktestResponse;
import com.AISA.AISA.analysis.dto.IsaDTOs.IsaCalculateRequest;
import com.AISA.AISA.analysis.dto.IsaDTOs.IsaCalculateResponse;
import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.Stock.StockType;
import com.AISA.AISA.kisStock.Entity.stock.StockDailyData;
import com.AISA.AISA.kisStock.dto.Dividend.StockDividendInfoDto;

import com.AISA.AISA.kisStock.kisService.DividendService;
import com.AISA.AISA.kisStock.repository.StockDailyDataRepository;
import com.AISA.AISA.portfolio.PortfolioGroup.exception.PortfolioErrorCode;
import com.AISA.AISA.portfolio.PortfolioStock.PortStock;
import com.AISA.AISA.portfolio.PortfolioStock.PortStockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class IsaService {

    private final PortStockRepository portStockRepository;
    private final StockDailyDataRepository stockDailyDataRepository;
    private final DividendService dividendService;

    private static final BigDecimal NORMAL_TAX_RATE = new BigDecimal("0.154"); // 15.4%
    private static final BigDecimal ISA_REDUCED_TAX_RATE = new BigDecimal("0.099"); // 9.9%

    public IsaCalculateResponse calculateTaxBenefits(IsaCalculateRequest request) {
        BigDecimal income = request.getDividendIncome();
        IsaAccountType type = request.getAccountType();

        // 1. Calculate Normal Account Tax
        BigDecimal normalTax = income.multiply(NORMAL_TAX_RATE).setScale(0, RoundingMode.DOWN);

        // 2. Calculate ISA Account Tax
        BigDecimal isaTax = BigDecimal.ZERO;
        BigDecimal taxFreeLimit = type.getTaxFreeLimit();

        if (income.compareTo(taxFreeLimit) > 0) {
            BigDecimal taxableIncome = income.subtract(taxFreeLimit);
            isaTax = taxableIncome.multiply(ISA_REDUCED_TAX_RATE).setScale(0, RoundingMode.DOWN);
        }

        // 3. Calculate Tax Savings
        BigDecimal taxSavings = normalTax.subtract(isaTax);

        return IsaCalculateResponse.builder()
                .normalTax(normalTax)
                .isaTax(isaTax)
                .taxSavings(taxSavings)
                .build();
    }

    @Transactional(readOnly = true)
    public IsaBacktestResponse calculateIsaBenefitsByBacktest(IsaBacktestRequest request) {
        UUID portId = request.getPortId();
        String startDateStr = request.getStartDate();
        String endDateStr = request.getEndDate();
        IsaAccountType accountType = request.getAccountType();

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        LocalDate startDate = LocalDate.parse(startDateStr, formatter);
        LocalDate endDate = LocalDate.parse(endDateStr, formatter);

        List<PortStock> portStocks = portStockRepository.findByPortfolio_PortId(portId);
        if (portStocks.isEmpty()) {
            throw new BusinessException(PortfolioErrorCode.PORTFOLIO_NOT_FOUND);
        }

        BigDecimal totalDividendIncome = BigDecimal.ZERO;
        BigDecimal totalCapitalGains = BigDecimal.ZERO; // PnL (Price Return)
        BigDecimal principal = BigDecimal.ZERO; // Total Initial Investment

        // For Normal Account Tax Calculation
        BigDecimal normalDividendTax = BigDecimal.ZERO;
        BigDecimal normalCapitalGainsTax = BigDecimal.ZERO;

        for (PortStock portStock : portStocks) {
            Stock stock = portStock.getStock();
            BigDecimal quantity = new BigDecimal(portStock.getQuantity());

            // 1. Calculate Price Return (PnL)
            BigDecimal startPrice = getPriceAtDate(stock, startDate);
            BigDecimal endPrice = getPriceAtDate(stock, endDate);
            BigDecimal pnlPerShare = endPrice.subtract(startPrice);
            BigDecimal totalPnl = pnlPerShare.multiply(quantity);

            totalCapitalGains = totalCapitalGains.add(totalPnl);
            principal = principal.add(startPrice.multiply(quantity));

            // Normal Account Tax on PnL
            if (stock.getStockType() == StockType.FOREIGN_ETF && totalPnl.compareTo(BigDecimal.ZERO) > 0) {
                // Domestic Listed Foreign ETF: 15.4% tax on gains
                normalCapitalGainsTax = normalCapitalGainsTax.add(totalPnl.multiply(NORMAL_TAX_RATE));
            }
            // Domestic Stock: 0% tax on gains

            // 2. Calculate Dividend Income
            List<StockDividendInfoDto> dividends = dividendService.getDividendInfo(stock.getStockCode(), startDateStr,
                    endDateStr);
            BigDecimal dividendPerShare = dividends.stream()
                    .map(StockDividendInfoDto::getDividendAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalDividend = dividendPerShare.multiply(quantity);

            totalDividendIncome = totalDividendIncome.add(totalDividend);

            // Normal Account Tax on Dividends (15.4%)
            normalDividendTax = normalDividendTax.add(totalDividend.multiply(NORMAL_TAX_RATE));
        }

        // 3. Calculate Taxes
        // Normal Account Total Tax
        BigDecimal normalTotalTax = normalDividendTax.add(normalCapitalGainsTax).setScale(0, RoundingMode.DOWN);

        // ISA Account Tax (Loss Offsetting)
        // Net Income = Dividend Income + Capital Gains (can be negative)
        BigDecimal isaNetIncome = totalDividendIncome.add(totalCapitalGains);

        // If Net Income is negative, Tax is 0
        BigDecimal isaTax = BigDecimal.ZERO;
        if (isaNetIncome.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal taxFreeLimit = accountType.getTaxFreeLimit();
            if (isaNetIncome.compareTo(taxFreeLimit) > 0) {
                BigDecimal taxableIncome = isaNetIncome.subtract(taxFreeLimit);
                isaTax = taxableIncome.multiply(ISA_REDUCED_TAX_RATE).setScale(0, RoundingMode.DOWN);
            }
        }

        BigDecimal taxSavings = normalTotalTax.subtract(isaTax);
        BigDecimal finalReturn = totalCapitalGains.add(totalDividendIncome).subtract(isaTax);

        // ROI Calculation
        BigDecimal roi = BigDecimal.ZERO;
        if (principal.compareTo(BigDecimal.ZERO) > 0) {
            roi = finalReturn.divide(principal, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
        }

        return IsaBacktestResponse.builder()
                .totalDividendIncome(totalDividendIncome)
                .totalCapitalGains(totalCapitalGains)
                .normalTotalTax(normalTotalTax)
                .isaTotalTax(isaTax)
                .taxSavings(taxSavings)
                .finalReturn(finalReturn)
                .principal(principal)
                .roi(roi)
                .build();
    }

    private BigDecimal getPriceAtDate(Stock stock, LocalDate date) {
        // Find exact date or nearest past date
        Optional<StockDailyData> data = stockDailyDataRepository
                .findFirstByStockAndDateLessThanEqualOrderByDateDesc(stock, date);
        if (data.isPresent()) {
            return data.get().getClosingPrice();
        }
        // If no past data, find nearest future date (e.g. if start date is holiday)
        data = stockDailyDataRepository.findFirstByStockAndDateGreaterThanEqualOrderByDateAsc(stock, date);
        return data.map(StockDailyData::getClosingPrice).orElse(BigDecimal.ZERO);
    }
}
