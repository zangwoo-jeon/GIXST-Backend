package com.AISA.AISA.analysis.service;

import com.AISA.AISA.analysis.dto.DomesticMomentumAnalysisDto;
import com.AISA.AISA.analysis.entity.DomesticShortTermReport;
import com.AISA.AISA.analysis.repository.DomesticShortTermReportRepository;
import com.AISA.AISA.analysis.util.TechnicalIndicatorUtils;
import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockDailyData;
import com.AISA.AISA.kisStock.Entity.stock.StockInvestorDaily;
import com.AISA.AISA.kisStock.dto.StockPrice.StockPriceDto;
import com.AISA.AISA.kisStock.exception.KisApiErrorCode;
import com.AISA.AISA.kisStock.kisService.KisStockService;
import com.AISA.AISA.kisStock.repository.StockDailyDataRepository;
import com.AISA.AISA.kisStock.repository.StockInvestorDailyRepository;
import com.AISA.AISA.kisStock.repository.StockRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DomesticShortTermAnalysisService {

    private final StockRepository stockRepository;
    private final StockDailyDataRepository stockDailyDataRepository;
    private final StockInvestorDailyRepository stockInvestorDailyRepository;
    private final DomesticShortTermReportRepository shortTermReportRepository;
    private final KisStockService kisStockService;
    private final GeminiService geminiService;
    private final ObjectMapper objectMapper;

    // Constants for signal logic
    private static final double VOLUME_RATIO_THRESHOLD = 70.0; // 70%

    @Transactional
    public DomesticMomentumAnalysisDto analyze(String stockCode, boolean forceRefresh) {
        Stock stock = stockRepository.findByStockCode(stockCode)
                .orElseThrow(() -> new BusinessException(KisApiErrorCode.STOCK_NOT_FOUND));

        // 1. Get Current Price
        StockPriceDto priceDto = kisStockService.getStockPrice(stockCode);
        BigDecimal currentPrice = new BigDecimal(priceDto.getStockPrice().replace(",", ""));

        // 2. Load cached report and check refresh condition
        if (!forceRefresh) {
            DomesticMomentumAnalysisDto cached = loadSavedReport(stockCode);
            if (cached != null) {
                DomesticShortTermReport savedReport = shortTermReportRepository.findByStockCode(stockCode).orElse(null);
                if (savedReport != null && !shouldRefresh(savedReport, currentPrice)) {
                    log.info("[DomesticShortTerm] Using cached report for {}", stockCode);
                    return cached;
                }
            }
        }

        log.info("[DomesticShortTerm] Generating new analysis for {}", stockCode);

        // 3. Fetch Historical Data (30 days for indicators)
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(60); // Enough buffer for 20-day MA and indicators
        List<StockDailyData> priceHistory = stockDailyDataRepository
                .findByStock_StockCodeAndDateBetweenOrderByDateAsc(stockCode, startDate, today);

        if (priceHistory.size() < 20) {
            throw new RuntimeException("Insufficient price history data (need at least 20 days)");
        }

        List<BigDecimal> closes = new ArrayList<>(priceHistory.stream().map(StockDailyData::getClosingPrice)
                .collect(Collectors.toList()));
        List<BigDecimal> highs = new ArrayList<>(priceHistory.stream().map(StockDailyData::getHighPrice).collect(Collectors.toList()));
        List<BigDecimal> lows = new ArrayList<>(priceHistory.stream().map(StockDailyData::getLowPrice).collect(Collectors.toList()));
        List<BigDecimal> volumes = new ArrayList<>(priceHistory.stream().map(StockDailyData::getVolume).collect(Collectors.toList()));

        // 당일 장중 데이터 보강 (DB에 없는 오늘 고가/저가 반영)
        try {
            String todayStr = today.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
            var todayChart = kisStockService.getStockChart(stockCode, todayStr, todayStr, "D");
            if (todayChart != null && todayChart.getPriceList() != null && !todayChart.getPriceList().isEmpty()) {
                var todayData = todayChart.getPriceList().get(0);
                highs.add(new BigDecimal(todayData.getHighPrice()));
                lows.add(new BigDecimal(todayData.getLowPrice()));
                closes.add(new BigDecimal(todayData.getClosePrice()));
                volumes.add(new BigDecimal(todayData.getVolume()));
            }
        } catch (Exception e) {
            log.warn("[DomesticShortTerm] Failed to fetch today's chart data for {}: {}", stockCode, e.getMessage());
        }

        // 4. Calculate Indicators (Shortened settings: RSI 9, Stoch 5/3, MACD 6/13/2)
        TechnicalIndicatorUtils.RsiResult rsiResult = TechnicalIndicatorUtils.calculateRSI(closes, 9, 6);
        DomesticMomentumAnalysisDto.MACD macd = TechnicalIndicatorUtils.calculateMACD(closes, 6, 13, 2);
        DomesticMomentumAnalysisDto.Stochastic stochastic = TechnicalIndicatorUtils.calculateStochastic(highs, lows,
                closes, 5, 3);

        // 5. Volume Filter
        long currentVol = volumes.get(volumes.size() - 1).longValue();
        double avgVol5Day = volumes.subList(volumes.size() - 5, volumes.size()).stream()
                .mapToDouble(BigDecimal::doubleValue).average().orElse(0.0);
        double avgVol20Day = volumes.subList(volumes.size() - 20, volumes.size()).stream()
                .mapToDouble(BigDecimal::doubleValue).average().orElse(0.0);
        double volumeRatio = (avgVol5Day / avgVol20Day) * 100.0;
        boolean volumeFilterMet = volumeRatio >= VOLUME_RATIO_THRESHOLD;

        // 6. Investor Trend (5 days)
        List<StockInvestorDaily> investorTrendHistory = stockInvestorDailyRepository
                .findByStockAndDateBetweenOrderByDateAsc(stock, today.minusDays(10), today); // Buffer for holidays

        // Get last 5 TRADING days
        List<StockInvestorDaily> last5DaysInvestor = investorTrendHistory.stream()
                .sorted((d1, d2) -> d2.getDate().compareTo(d1.getDate()))
                .limit(5)
                .collect(Collectors.toList());

        BigDecimal foreignerNetBuy5Day = last5DaysInvestor.stream()
                .map(StockInvestorDaily::getForeignerNetBuyAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal institutionNetBuy5Day = last5DaysInvestor.stream()
                .map(StockInvestorDaily::getInstitutionNetBuyAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 7. Signal Logic & Composite Score
        String trendDirection = determineTrendDirection(closes);
        int netBuyScore = calculateNetBuyScore(last5DaysInvestor, avgVol5Day);
        int trendScore = calculateTrendScore(rsiResult.getRsi(), macd.getHistogram(), netBuyScore, volumeFilterMet);

        String trendAlignment = determineTrendAlignment(rsiResult.getRsi(), macd.getHistogram(), foreignerNetBuy5Day,
                institutionNetBuy5Day);
        String compositeSignal = determineCompositeSignal(trendScore, volumeFilterMet, trendAlignment);

        // 8. Construct DTO
        DomesticMomentumAnalysisDto.ShortTermVerdict verdict = DomesticMomentumAnalysisDto.ShortTermVerdict.builder()
                .trendScore(trendScore)
                .trendDirection(trendDirection)
                .momentum(determineMomentum(rsiResult.getRsi(), macd.getHistogram()))
                .volatility(determineVolatility(closes))
                .technicalIndicators(DomesticMomentumAnalysisDto.TechnicalIndicators.builder()
                        .rsi(rsiResult.getRsi())
                        .rsiSignal(rsiResult.getSignal())
                        .macd(macd)
                        .stochastic(stochastic)
                        .build())
                .investorTrend(DomesticMomentumAnalysisDto.InvestorTrend.builder()
                        .foreigner5DayNetBuy(foreignerNetBuy5Day)
                        .institution5DayNetBuy(institutionNetBuy5Day)
                        .trendAlignment(trendAlignment)
                        .build())
                .volumeFilter(DomesticMomentumAnalysisDto.VolumeFilter.builder()
                        .currentVolume(currentVol)
                        .avgVolume5Day((long) avgVol5Day)
                        .avgVolume20Day((long) avgVol20Day)
                        .volumeRatio(volumeRatio)
                        .isMet(volumeFilterMet)
                        .build())
                .compositeSignal(compositeSignal)
                .rationale(generateRationale(compositeSignal, volumeFilterMet, trendAlignment))
                .investmentAttractiveness(determineAttractiveness(compositeSignal))
                .action(determineAction(compositeSignal))
                .holdingHorizon("1~3개월")
                .supportResistance(calculateSupportResistance(highs, lows))
                .build();

        DomesticMomentumAnalysisDto result = DomesticMomentumAnalysisDto.builder()
                .stockCode(stockCode)
                .stockName(stock.getStockName())
                .currentPrice(priceDto.getStockPrice())
                .shortTermVerdict(verdict)
                .build();

        // 9. AI Explanation
        String prompt = generatePrompt(stock, verdict);
        String aiExplanation = geminiService.generateAdvice(prompt);
        result.setExplanation(parseExplanation(aiExplanation));

        // 10. Save and Return
        saveReport(stockCode, stock.getStockName(), currentPrice, (int) rsiResult.getRsi(), macd.getHistogram(),
                foreignerNetBuy5Day, institutionNetBuy5Day, result);
        return result;
    }

    private boolean shouldRefresh(DomesticShortTermReport saved, BigDecimal currentPrice) {
        // Daily refresh based on modified date
        if (saved.getLastModifiedDate().toLocalDate().isBefore(LocalDate.now())) {
            return true;
        }
        // Price volatility refresh (0.5%)
        BigDecimal lastPrice = saved.getLastPrice();
        if (lastPrice != null && lastPrice.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal change = currentPrice.subtract(lastPrice).abs();
            BigDecimal threshold = lastPrice.multiply(new BigDecimal("0.005"));
            if (change.compareTo(threshold) > 0) {
                return true;
            }
        }
        return false;
    }

    private void saveReport(String stockCode, String stockName, BigDecimal price, int rsi, BigDecimal macdHist,
            BigDecimal fBuy, BigDecimal iBuy, DomesticMomentumAnalysisDto dto) {
        try {
            DomesticShortTermReport report = shortTermReportRepository.findByStockCode(stockCode)
                    .orElse(DomesticShortTermReport.builder().stockCode(stockCode).build());

            report.setStockName(stockName);
            report.setLastPrice(price);
            report.setLastRsi(rsi);
            report.setLastMacdHistogram(macdHist);
            report.setLastForeigner5DayNetBuy(fBuy);
            report.setLastInstitution5DayNetBuy(iBuy);
            report.setFullReportJson(objectMapper.writeValueAsString(dto));
            report.setLastModifiedDate(LocalDateTime.now());

            shortTermReportRepository.save(report);
        } catch (Exception e) {
            log.error("[DomesticShortTerm] Failed to save report: {}", e.getMessage());
        }
    }

    private DomesticMomentumAnalysisDto loadSavedReport(String stockCode) {
        return shortTermReportRepository.findByStockCode(stockCode)
                .map(report -> {
                    try {
                        return objectMapper.readValue(report.getFullReportJson(), DomesticMomentumAnalysisDto.class);
                    } catch (Exception e) {
                        return null;
                    }
                }).orElse(null);
    }

    private String determineTrendDirection(List<BigDecimal> closes) {
        if (closes.size() < 20) return "Sideways";
        BigDecimal ma5 = calculateMA(closes, 5);
        BigDecimal ma20 = calculateMA(closes, 20);
        if (ma5.compareTo(ma20) > 0) return "Bullish";
        if (ma5.compareTo(ma20) < 0) return "Bearish";
        return "Sideways";
    }

    private String determineVolatility(List<BigDecimal> closes) {
        int window = Math.min(20, closes.size() - 1);
        if (window < 2) return "보통";
        double sum = 0;
        for (int i = closes.size() - window; i < closes.size(); i++) {
            double prev = closes.get(i - 1).doubleValue();
            if (prev > 0) {
                sum += Math.abs((closes.get(i).doubleValue() - prev) / prev);
            }
        }
        double avgDailyChange = sum / window;
        if (avgDailyChange >= 0.025) return "높음";
        if (avgDailyChange <= 0.010) return "낮음";
        return "보통";
    }

    private BigDecimal calculateMA(List<BigDecimal> prices, int period) {
        List<BigDecimal> recent = prices.subList(prices.size() - period, prices.size());
        BigDecimal sum = recent.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(period), 2, RoundingMode.HALF_UP);
    }

    private int calculateTrendScore(double rsi, BigDecimal macdHist, int netBuyScore, boolean volumeMet) {
        int score = 50 + netBuyScore; // 수급 점수 반영 (최대 ±24)
        // 추세 추종형: RSI 높을수록 강한 추세, 낮을수록 약세 구간
        if (rsi > 65)
            score += 10;
        else if (rsi < 35)
            score -= 10;
        if (macdHist.compareTo(BigDecimal.ZERO) > 0)
            score += 10;
        if (volumeMet)
            score += 5;
        // 동시 충족 보너스: RSI + MACD + 거래량 모두 상승 확인 시 추가 가산
        if (rsi > 60 && macdHist.compareTo(BigDecimal.ZERO) > 0 && volumeMet)
            score += 5;
        return Math.max(0, Math.min(100, score));
    }

    private int calculateNetBuyScore(List<StockInvestorDaily> last5Days, double avgVol5Day) {
        if (last5Days.isEmpty() || avgVol5Day <= 0) return 0;

        boolean hasQuantityData = last5Days.stream()
                .anyMatch(d -> d.getForeignerNetBuyQuantity() != null || d.getInstitutionNetBuyQuantity() != null);

        if (hasQuantityData) {
            // 수량 기반 비율: volume과 단위 동일 (주)
            long fQty = last5Days.stream()
                    .mapToLong(d -> d.getForeignerNetBuyQuantity() != null ? d.getForeignerNetBuyQuantity() : 0L)
                    .sum();
            long iQty = last5Days.stream()
                    .mapToLong(d -> d.getInstitutionNetBuyQuantity() != null ? d.getInstitutionNetBuyQuantity() : 0L)
                    .sum();
            double totalVol5Day = avgVol5Day * last5Days.size();
            return scoreRatio(fQty / totalVol5Day) + scoreRatio(iQty / totalVol5Day);
        } else {
            // 폴백: 금액 부호만 확인
            BigDecimal fAmt = last5Days.stream().map(StockInvestorDaily::getForeignerNetBuyAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal iAmt = last5Days.stream().map(StockInvestorDaily::getInstitutionNetBuyAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            int score = 0;
            if (fAmt.compareTo(BigDecimal.ZERO) > 0) score += 10;
            if (iAmt.compareTo(BigDecimal.ZERO) > 0) score += 10;
            return score;
        }
    }

    private int scoreRatio(double ratio) {
        if (ratio > 0.02) return 12;
        if (ratio > 0.005) return 7;
        if (ratio < -0.02) return -8;
        if (ratio < -0.005) return -4;
        return 0;
    }

    private static final BigDecimal INVESTOR_THRESHOLD = new BigDecimal("500"); // 5억원 이상 (단위: 백만원)

    private String determineTrendAlignment(double rsi, BigDecimal macdHist, BigDecimal fBuy, BigDecimal iBuy) {
        // 추세 추종형: RSI > 60 + MACD 양수 = 기술적 상승, RSI < 40 + MACD 음수 = 기술적 하락
        boolean techUp = rsi > 60 && macdHist.compareTo(BigDecimal.ZERO) > 0;
        boolean techDown = rsi < 40 && macdHist.compareTo(BigDecimal.ZERO) < 0;
        BigDecimal netTotal = fBuy.add(iBuy);
        boolean investorBuy = netTotal.compareTo(INVESTOR_THRESHOLD) > 0;
        boolean investorSell = netTotal.negate().compareTo(INVESTOR_THRESHOLD) > 0;

        if (techUp && investorBuy)
            return "일치 (매수 우위)";
        if (techDown && investorSell)
            return "일치 (매도 우위)";
        return "불일치 (대기)";
    }

    private String determineCompositeSignal(int score, boolean volumeMet, String alignment) {
        if (!volumeMet)
            return "Wait (Low Volume)";
        if (score >= 75 && alignment.equals("일치 (매수 우위)"))
            return "Strong Buy";
        if (score >= 65 && alignment.equals("일치 (매수 우위)"))
            return "Buy";
        if (score <= 40 && alignment.equals("일치 (매도 우위)"))
            return "Sell";
        return "Neutral";
    }

    private String generateRationale(String signal, boolean volumeMet, String alignment) {
        if (!volumeMet)
            return "거래량 필터 미충족으로 인한 잡음 신호 경계가 필요합니다.";
        return String.format("추세 점수와 수급 흐름이 %s 상태이며, %s 신호가 포착되었습니다.", alignment, signal);
    }

    private String determineAttractiveness(String signal) {
        if (signal.contains("Buy"))
            return "Attractive";
        if (signal.contains("Sell"))
            return "Low";
        return "Neutral";
    }

    private String determineAction(String signal) {
        if (signal.contains("Buy"))
            return "Buy";
        if (signal.contains("Sell"))
            return "Sell";
        return "Wait";
    }

    private String determineMomentum(double rsi, BigDecimal macdHist) {
        if (rsi > 65 && macdHist.compareTo(BigDecimal.ZERO) > 0)
            return "강한 상승 모멘텀";
        if (rsi < 35 && macdHist.compareTo(BigDecimal.ZERO) < 0)
            return "강한 하락 모멘텀";
        if (rsi > 55 && macdHist.compareTo(BigDecimal.ZERO) > 0)
            return "상승 모멘텀";
        if (rsi < 45 && macdHist.compareTo(BigDecimal.ZERO) < 0)
            return "하락 모멘텀";
        return "보통";
    }

    private DomesticMomentumAnalysisDto.SupportResistance calculateSupportResistance(List<BigDecimal> highs,
            List<BigDecimal> lows) {
        int window = Math.min(20, highs.size());
        List<BigDecimal> recentHighs = highs.subList(highs.size() - window, highs.size());
        List<BigDecimal> recentLows = lows.subList(lows.size() - window, lows.size());
        BigDecimal resistance = recentHighs.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal support = recentLows.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        return DomesticMomentumAnalysisDto.SupportResistance.builder()
                .supportLevel(support.setScale(0, RoundingMode.HALF_UP))
                .resistanceLevel(resistance.setScale(0, RoundingMode.HALF_UP))
                .build();
    }

    private String generatePrompt(Stock stock, DomesticMomentumAnalysisDto.ShortTermVerdict v) {
        StringBuilder sb = new StringBuilder();
        sb.append("너는 '국내 주식 전문 기술적 분석가'이다. 아래 데이터를 기반으로 짧고 명확한 단기 투자 전략을 해설하라.\n\n");
        sb.append(String.format("종목: %s (%s)\n", stock.getStockName(), stock.getStockCode()));
        sb.append(String.format("- 기술적 지표: RSI %.2f, MACD Hist %s, Stoch %%K %.2f\n",
                v.getTechnicalIndicators().getRsi(), v.getTechnicalIndicators().getMacd().getHistogram(),
                v.getTechnicalIndicators().getStochastic().getK()));
        sb.append(String.format("- 거래량 필터: %s (전용 70%% 기준)\n", v.getVolumeFilter().isMet() ? "충족" : "미충족"));
        sb.append(String.format("- 수급 동향: 외국인 %s, 기관 %s (최근 5일 누적)\n",
                formatAmount(v.getInvestorTrend().getForeigner5DayNetBuy()),
                formatAmount(v.getInvestorTrend().getInstitution5DayNetBuy())));
        sb.append(String.format("- 종합 신호: %s (추세 점수: %d)\n\n", v.getCompositeSignal(), v.getTrendScore()));
        sb.append(
                "작성 가이드:\n1. 지표 신호와 수급의 일치 여부를 중심으로 해설하라.\n2. 거래량이 부족할 경우 강력한 주의를 부여하라.\n3. 핵심 매매 타이밍을 1문장으로 요약하라.\n\n");
        sb.append("형식: 한글 작성. 각 항목을 '|'로 구분하여 단일 문장으로 반환.");
        return sb.toString();
    }

    // amount 단위: 백만원
    private String formatAmount(BigDecimal amount) {
        BigDecimal abs = amount.abs();
        String sign = amount.signum() < 0 ? "-" : "+";
        if (abs.compareTo(new BigDecimal("100")) >= 0) {
            // 100백만원 = 1억원 이상 → 억원 표시
            return sign + abs.divide(new BigDecimal("100"), 1, RoundingMode.HALF_UP) + "억원";
        } else if (abs.compareTo(new BigDecimal("1")) >= 0) {
            // 1백만원 이상 → 만원 표시
            return sign + abs.multiply(new BigDecimal("100")).setScale(0, RoundingMode.HALF_UP) + "만원";
        }
        return "미미 (100만원 미만)";
    }

    private List<String> parseExplanation(String text) {
        if (text == null)
            return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (String s : text.split("\\|")) {
            result.add(s.trim());
        }
        return result;
    }

    @Transactional
    public void clearShortTermReports() {
        long count = shortTermReportRepository.count();
        log.info("[DomesticShortTerm] Clearing all reports. Current count: {}", count);
        shortTermReportRepository.truncateTable();
        log.info("[DomesticShortTerm] All domestic short-term reports cleared.");
    }
}
