package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.kisStock.Entity.stock.MarketInvestorDaily;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.config.KisApiProperties;
import com.AISA.AISA.kisStock.dto.InvestorTrend.AccumulatedInvestorTrendDto;
import com.AISA.AISA.kisStock.dto.InvestorTrend.AccumulatedInvestorProjection;
import com.AISA.AISA.kisStock.dto.KisMarketInvestorTrendResponse;
import com.AISA.AISA.kisStock.dto.MarketAccumulatedTrendDto;
import com.AISA.AISA.kisStock.dto.MarketInvestorTrendDto;
import com.AISA.AISA.kisStock.repository.MarketAccumulatedProjection;
import com.AISA.AISA.kisStock.repository.MarketInvestorDailyRepository;
import com.AISA.AISA.kisStock.repository.StockInvestorDailyRepository;
import com.AISA.AISA.kisStock.repository.StockRepository;
import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.kisStock.exception.KisApiErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KisInvestorService {

        private final StockInvestorDailyRepository stockInvestorDailyRepository;
        private final StockRepository stockRepository;
        private final KisApiClient kisApiClient;
        private final KisApiProperties kisApiProperties;
        private final WebClient webClient;
        private final MarketInvestorDailyRepository marketInvestorDailyRepository;

        @Transactional(readOnly = true)
        public AccumulatedInvestorTrendDto getAccumulatedInvestorTrend(String stockCode, String period) {
                Stock stock = stockRepository.findByStockCode(stockCode)
                                .orElseThrow(() -> new BusinessException(KisApiErrorCode.STOCK_NOT_FOUND));

                if (stock.getStockType() != Stock.StockType.DOMESTIC) {
                        throw new BusinessException(KisApiErrorCode.INVALID_STOCK_TYPE);
                }

                LocalDate startDate;
                if ("1w".equalsIgnoreCase(period)) {
                        startDate = LocalDate.now().minusWeeks(1);
                } else if ("1m".equalsIgnoreCase(period)) {
                        startDate = LocalDate.now().minusMonths(1);
                } else if ("6m".equalsIgnoreCase(period)) {
                        startDate = LocalDate.now().minusMonths(6);
                } else if ("1y".equalsIgnoreCase(period)) {
                        startDate = LocalDate.now().minusYears(1);
                } else {
                        startDate = LocalDate.now().minusMonths(3);
                }

                AccumulatedInvestorProjection projection = stockInvestorDailyRepository
                                .findAggregatedInvestorTrendByStock(stock, startDate);

                if (projection == null) {
                        return AccumulatedInvestorTrendDto.builder()
                                        .personalNetBuyAmount(BigDecimal.ZERO)
                                        .foreignerNetBuyAmount(BigDecimal.ZERO)
                                        .institutionNetBuyAmount(BigDecimal.ZERO)
                                        .build();
                }

                return AccumulatedInvestorTrendDto.builder()
                                .personalNetBuyAmount(
                                                projection.getPersonalNetBuyAmount() != null
                                                                ? projection.getPersonalNetBuyAmount()
                                                                : BigDecimal.ZERO)
                                .foreignerNetBuyAmount(
                                                projection.getForeignerNetBuyAmount() != null
                                                                ? projection.getForeignerNetBuyAmount()
                                                                : BigDecimal.ZERO)
                                .institutionNetBuyAmount(
                                                projection.getInstitutionNetBuyAmount() != null
                                                                ? projection.getInstitutionNetBuyAmount()
                                                                : BigDecimal.ZERO)
                                .build();
        }

        public MarketInvestorTrendDto getMarketInvestorTrend(String marketCode) {
                // marketCode: 0001 (KOSPI), 1001 (KOSDAQ)
                String marketName = marketCode.equals("0001") ? "KSP" : "KSQ";
                String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

                KisMarketInvestorTrendResponse response = fetchMarketInvestorTrendFromApi(marketCode, marketName,
                                today);

                if (response == null || response.getOutput() == null) {
                        return MarketInvestorTrendDto.builder().trends(new ArrayList<>()).build();
                }

                // Sync with DB
                saveMarketInvestorTrendToDb(marketCode, response.getOutput());

                List<MarketInvestorTrendDto.MarketTrendItem> trends = response.getOutput().stream()
                                .map(out -> MarketInvestorTrendDto.MarketTrendItem.builder()
                                                .date(out.getStckBsopDate())
                                                .indexPrice(out.getBstpNmixPrpr())
                                                .change(out.getBstpNmixPrdyVrss())
                                                .changeRate(out.getBstpNmixPrdyCtrt())
                                                .changeSign(out.getPrdyVrssSign())
                                                .foreignerNetBuy(out.getFrgnNtbyTrPbmn())
                                                .personalNetBuy(out.getPrsnNtbyTrPbmn())
                                                .institutionNetBuy(out.getOrgnNtbyTrPbmn())
                                                .securitiesNetBuy(out.getScrtNtbyTrPbmn())
                                                .investmentTrustNetBuy(out.getIvtrNtbyTrPbmn())
                                                .privateFundNetBuy(out.getPeFundNtbyTrPbmn())
                                                .bankNetBuy(out.getBankNtbyTrPbmn())
                                                .insuranceNetBuy(out.getInsuNtbyTrPbmn())
                                                .merchantBankNetBuy(out.getMrbnNtbyTrPbmn())
                                                .pensionFundNetBuy(out.getFundNtbyTrPbmn())
                                                .etcCorporateNetBuy(out.getEtcCorpNtbyTrPbmn())
                                                .etcNetBuy(out.getEtcNtbyTrPbmn())
                                                .build())
                                .collect(Collectors.toList());

                return MarketInvestorTrendDto.builder().trends(trends).build();
        }

        private KisMarketInvestorTrendResponse fetchMarketInvestorTrendFromApi(String marketCode, String marketName,
                        String date) {
                return kisApiClient.fetch(token -> webClient.get()
                                .uri(uriBuilder -> uriBuilder
                                                .path(kisApiProperties.getInvestorTrendMarketUrl())
                                                .queryParam("FID_COND_MRKT_DIV_CODE", "U")
                                                .queryParam("FID_INPUT_ISCD", marketCode)
                                                .queryParam("FID_INPUT_DATE_1", date)
                                                .queryParam("FID_INPUT_ISCD_1", marketName)
                                                .queryParam("FID_INPUT_DATE_2", date)
                                                .queryParam("FID_INPUT_ISCD_2", marketCode)
                                                .build())
                                .header("authorization", token)
                                .header("appkey", kisApiProperties.getAppkey())
                                .header("appsecret", kisApiProperties.getAppsecret())
                                .header("tr_id", "FHPTJ04040000")
                                .header("custtype", "P"), KisMarketInvestorTrendResponse.class);
        }

        @Transactional
        public void saveMarketInvestorTrendToDb(String marketCode,
                        List<KisMarketInvestorTrendResponse.Output> outputs) {
                for (KisMarketInvestorTrendResponse.Output out : outputs) {
                        try {
                                LocalDate date = LocalDate.parse(out.getStckBsopDate(),
                                                DateTimeFormatter.ofPattern("yyyyMMdd"));
                                Optional<MarketInvestorDaily> existing = marketInvestorDailyRepository
                                                .findByMarketCodeAndDate(marketCode, date);

                                if (existing.isPresent())
                                        continue;

                                MarketInvestorDaily daily = MarketInvestorDaily.builder()
                                                .marketCode(marketCode)
                                                .date(date)
                                                .indexPrice(parseBigDecimalSafe(out.getBstpNmixPrpr()))
                                                .personalNetBuy(parseBigDecimalSafe(out.getPrsnNtbyTrPbmn()))
                                                .foreignerNetBuy(parseBigDecimalSafe(out.getFrgnNtbyTrPbmn()))
                                                .institutionNetBuy(parseBigDecimalSafe(out.getOrgnNtbyTrPbmn()))
                                                .securitiesNetBuy(parseBigDecimalSafe(out.getScrtNtbyTrPbmn()))
                                                .investmentTrustNetBuy(parseBigDecimalSafe(out.getIvtrNtbyTrPbmn()))
                                                .privateFundNetBuy(parseBigDecimalSafe(out.getPeFundNtbyTrPbmn()))
                                                .bankNetBuy(parseBigDecimalSafe(out.getBankNtbyTrPbmn()))
                                                .insuranceNetBuy(parseBigDecimalSafe(out.getInsuNtbyTrPbmn()))
                                                .merchantBankNetBuy(parseBigDecimalSafe(out.getMrbnNtbyTrPbmn()))
                                                .pensionFundNetBuy(parseBigDecimalSafe(out.getFundNtbyTrPbmn()))
                                                .etcCorporateNetBuy(parseBigDecimalSafe(out.getEtcCorpNtbyTrPbmn()))
                                                .etcNetBuy(parseBigDecimalSafe(out.getEtcNtbyTrPbmn()))
                                                .build();

                                marketInvestorDailyRepository.save(daily);
                        } catch (Exception e) {
                                log.error("Failed to save market investor trend for date {}: {}", out.getStckBsopDate(),
                                                e.getMessage());
                        }
                }
        }

        public MarketAccumulatedTrendDto getAccumulatedMarketInvestorTrend(String marketCode, String period) {
                LocalDate endDate = LocalDate.now();
                LocalDate startDate;

                switch (period.toLowerCase()) {
                        case "1w":
                                startDate = endDate.minusWeeks(1);
                                break;
                        case "1m":
                                startDate = endDate.minusMonths(1);
                                break;
                        case "3m":
                                startDate = endDate.minusMonths(3);
                                break;
                        case "1y":
                                startDate = endDate.minusYears(1);
                                break;
                        default:
                                startDate = endDate.minusMonths(3);
                }

                MarketAccumulatedProjection aggregated = marketInvestorDailyRepository
                                .findAggregatedMarketInvestorTrend(marketCode, startDate);

                if (aggregated == null) {
                        return MarketAccumulatedTrendDto.builder()
                                        .marketCode(marketCode)
                                        .period(period)
                                        .startDate(startDate.toString())
                                        .endDate(endDate.toString())
                                        .personalNetBuy(BigDecimal.ZERO)
                                        .foreignerNetBuy(BigDecimal.ZERO)
                                        .institutionNetBuy(BigDecimal.ZERO)
                                        .build();
                }

                return MarketAccumulatedTrendDto.builder()
                                .marketCode(marketCode)
                                .period(period)
                                .startDate(startDate.toString())
                                .endDate(endDate.toString())
                                .personalNetBuy(aggregated.getPersonal())
                                .foreignerNetBuy(aggregated.getForeigner())
                                .institutionNetBuy(aggregated.getInstitution())
                                .securitiesNetBuy(aggregated.getSecurities())
                                .investmentTrustNetBuy(aggregated.getInvestmentTrust())
                                .privateFundNetBuy(aggregated.getPrivateFund())
                                .bankNetBuy(aggregated.getBank())
                                .insuranceNetBuy(aggregated.getInsurance())
                                .merchantBankNetBuy(aggregated.getMerchantBank())
                                .pensionFundNetBuy(aggregated.getPensionFund())
                                .etcCorporateNetBuy(aggregated.getEtcCorporate())
                                .etcNetBuy(aggregated.getEtc())
                                .build();
        }

        public void fetchHistoricalMarketData(String marketCode, LocalDate startDate, LocalDate endDate) {
                String marketName = marketCode.equals("0001") ? "KSP" : "KSQ";
                LocalDate currentEndDate = endDate != null ? endDate : LocalDate.now();
                LocalDate targetStartDate = startDate != null ? startDate : currentEndDate.minusYears(1);

                log.info("Starting historical market data sync for {} from {} to {}", marketCode, targetStartDate,
                                currentEndDate);

                // Fetch data in chunks (backwards from currentEndDate to targetStartDate)
                while (currentEndDate.isAfter(targetStartDate) || currentEndDate.isEqual(targetStartDate)) {
                        String dateStr = currentEndDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                        KisMarketInvestorTrendResponse response = fetchMarketInvestorTrendFromApi(marketCode,
                                        marketName,
                                        dateStr);

                        if (response == null || response.getOutput() == null || response.getOutput().isEmpty()) {
                                break;
                        }

                        // Save only the data within requested range
                        List<KisMarketInvestorTrendResponse.Output> filteredOutput = response.getOutput().stream()
                                        .filter(out -> {
                                                LocalDate outDate = LocalDate.parse(out.getStckBsopDate(),
                                                                DateTimeFormatter.ofPattern("yyyyMMdd"));
                                                return (outDate.isAfter(targetStartDate)
                                                                || outDate.isEqual(targetStartDate))
                                                                && (outDate.isBefore(endDate != null
                                                                                ? endDate.plusDays(1)
                                                                                : LocalDate.now().plusDays(1)));
                                        })
                                        .collect(Collectors.toList());

                        saveMarketInvestorTrendToDb(marketCode, filteredOutput);

                        // Find the oldest date in full output to determine next fetch point
                        String oldestDateStr = response.getOutput().get(response.getOutput().size() - 1)
                                        .getStckBsopDate();
                        LocalDate oldestDate = LocalDate.parse(oldestDateStr, DateTimeFormatter.ofPattern("yyyyMMdd"));

                        // If oldest date in response is already before or at targetStartDate, we're
                        // done
                        if (oldestDate.isBefore(targetStartDate) || oldestDate.isEqual(targetStartDate)) {
                                break;
                        }

                        currentEndDate = oldestDate.minusDays(1);

                        try {
                                Thread.sleep(200); // Rate limit
                        } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                        }
                }
                log.info("Completed historical market data sync for {}", marketCode);
        }

        private BigDecimal parseBigDecimalSafe(String value) {
                if (value == null || value.trim().isEmpty() || "-".equals(value.trim())) {
                        return BigDecimal.ZERO;
                }
                try {
                        return new BigDecimal(value.replace(",", "").trim());
                } catch (Exception e) {
                        return BigDecimal.ZERO;
                }
        }
}
