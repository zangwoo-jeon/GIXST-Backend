package com.AISA.AISA.analysis.util;

import com.AISA.AISA.analysis.dto.DomesticMomentumAnalysisDto;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public class TechnicalIndicatorUtils {

    /**
     * RSI with Wilder's Smoothing
     * Formula: 100 - (100 / (1 + RS))
     * RS = Avg Gain / Avg Loss
     */
    public static RsiResult calculateRSI(List<BigDecimal> prices, int period, int signalPeriod) {
        if (prices.size() <= period)
            return new RsiResult(50.0, 50.0, 50.0);

        List<BigDecimal> gains = new ArrayList<>();
        List<BigDecimal> losses = new ArrayList<>();
        for (int i = 1; i < prices.size(); i++) {
            BigDecimal diff = prices.get(i).subtract(prices.get(i - 1));
            if (diff.compareTo(BigDecimal.ZERO) > 0) {
                gains.add(diff);
                losses.add(BigDecimal.ZERO);
            } else {
                gains.add(BigDecimal.ZERO);
                losses.add(diff.abs());
            }
        }

        double avgGain = 0, avgLoss = 0;
        for (int i = 0; i < period; i++) {
            avgGain += gains.get(i).doubleValue();
            avgLoss += losses.get(i).doubleValue();
        }
        avgGain /= period;
        avgLoss /= period;

        List<Double> rsiValues = new ArrayList<>();
        double rsi = avgLoss == 0 ? 100.0 : 100.0 - (100.0 / (1.0 + avgGain / avgLoss));
        rsiValues.add(rsi);

        for (int i = period; i < gains.size(); i++) {
            avgGain = (avgGain * (period - 1) + gains.get(i).doubleValue()) / period;
            avgLoss = (avgLoss * (period - 1) + losses.get(i).doubleValue()) / period;
            rsi = avgLoss == 0 ? 100.0 : 100.0 - (100.0 / (1.0 + avgGain / avgLoss));
            rsiValues.add(rsi);
        }

        double latestRsi = rsiValues.get(rsiValues.size() - 1);
        double prevRsi = rsiValues.size() >= 2
                ? rsiValues.get(rsiValues.size() - 2)
                : latestRsi;
        double signal = latestRsi;
        if (rsiValues.size() >= signalPeriod) {
            double sum = 0;
            for (int i = rsiValues.size() - signalPeriod; i < rsiValues.size(); i++) {
                sum += rsiValues.get(i);
            }
            signal = sum / signalPeriod;
        }

        return new RsiResult(
                Math.round(latestRsi * 100.0) / 100.0,
                Math.round(signal * 100.0) / 100.0,
                Math.round(prevRsi * 100.0) / 100.0);
    }

    public static MacdResult calculateMACD(List<BigDecimal> prices, int shortPeriod,
            int longPeriod, int signalPeriod) {
        if (prices.size() < longPeriod) {
            DomesticMomentumAnalysisDto.MACD macd = DomesticMomentumAnalysisDto.MACD.builder()
                    .macdLine(BigDecimal.ZERO)
                    .signalLine(BigDecimal.ZERO)
                    .histogram(BigDecimal.ZERO)
                    .build();
            return new MacdResult(macd, List.of());
        }

        List<BigDecimal> macdLines = new ArrayList<>();
        int iterations = Math.min(prices.size() - longPeriod + 1, signalPeriod + 10);

        for (int i = prices.size() - iterations; i < prices.size(); i++) {
            List<BigDecimal> subList = prices.subList(0, i + 1);
            BigDecimal emaShort = calculateEMA(subList, shortPeriod);
            BigDecimal emaLong = calculateEMA(subList, longPeriod);
            macdLines.add(emaShort.subtract(emaLong));
        }

        // histogram 시계열 생성: 각 시점의 MACD - Signal(SMA)
        List<BigDecimal> histogramSeries = new ArrayList<>();
        for (int i = 0; i < macdLines.size(); i++) {
            BigDecimal sig = BigDecimal.ZERO;
            int cnt = 0;
            for (int j = i; j >= 0 && cnt < signalPeriod; j--, cnt++) {
                sig = sig.add(macdLines.get(j));
            }
            if (cnt > 0) {
                sig = sig.divide(BigDecimal.valueOf(cnt), 4, RoundingMode.HALF_UP);
            }
            histogramSeries.add(macdLines.get(i).subtract(sig));
        }

        BigDecimal latestMacd = macdLines.get(macdLines.size() - 1);
        BigDecimal latestSignal = BigDecimal.ZERO;
        int count = 0;
        for (int i = macdLines.size() - 1; i >= 0 && count < signalPeriod; i--, count++) {
            latestSignal = latestSignal.add(macdLines.get(i));
        }
        if (count > 0) {
            latestSignal = latestSignal.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
        }
        BigDecimal histogram = latestMacd.subtract(latestSignal);

        DomesticMomentumAnalysisDto.MACD macd = DomesticMomentumAnalysisDto.MACD.builder()
                .macdLine(latestMacd.setScale(4, RoundingMode.HALF_UP))
                .signalLine(latestSignal.setScale(4, RoundingMode.HALF_UP))
                .histogram(histogram.setScale(4, RoundingMode.HALF_UP))
                .build();

        // 최근 5일 histogram 반환
        int recentCount = Math.min(5, histogramSeries.size());
        List<BigDecimal> recentHistograms = histogramSeries.subList(
                histogramSeries.size() - recentCount, histogramSeries.size());

        return new MacdResult(macd, new ArrayList<>(recentHistograms));
    }

    public static BigDecimal calculateEMA(List<BigDecimal> prices, int period) {
        BigDecimal multiplier = BigDecimal.valueOf(2.0 / (period + 1));
        BigDecimal ema = prices.get(0);
        for (int i = 1; i < prices.size(); i++) {
            ema = prices.get(i).subtract(ema).multiply(multiplier).add(ema);
        }
        return ema;
    }

    /**
     * Stochastic Oscillator (K_PERIOD, D_PERIOD)
     */
    public static DomesticMomentumAnalysisDto.Stochastic calculateStochastic(List<BigDecimal> highs,
            List<BigDecimal> lows, List<BigDecimal> closes, int kPeriod, int dPeriod) {
        if (closes.size() < kPeriod + dPeriod) {
            return DomesticMomentumAnalysisDto.Stochastic.builder().k(50.0).d(50.0).build();
        }

        List<Double> fastKValues = new ArrayList<>();
        for (int i = closes.size() - dPeriod - 1; i < closes.size(); i++) {
            int startIdx = i - kPeriod + 1;
            BigDecimal currentClose = closes.get(i);
            BigDecimal lowestLow = lows.subList(startIdx, i + 1).stream().min(BigDecimal::compareTo).get();
            BigDecimal highestHigh = highs.subList(startIdx, i + 1).stream().max(BigDecimal::compareTo).get();

            BigDecimal range = highestHigh.subtract(lowestLow);
            if (range.compareTo(BigDecimal.ZERO) == 0) {
                fastKValues.add(50.0);
            } else {
                double fk = currentClose.subtract(lowestLow)
                        .divide(range, 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .doubleValue();
                fastKValues.add(fk);
            }
        }

        double latestK = fastKValues.get(fastKValues.size() - 1);
        double latestD = fastKValues.stream().mapToDouble(Double::doubleValue).average().orElse(50.0);

        return DomesticMomentumAnalysisDto.Stochastic.builder()
                .k(Math.round(latestK * 100.0) / 100.0)
                .d(Math.round(latestD * 100.0) / 100.0)
                .build();
    }

    @Getter
    @AllArgsConstructor
    public static class RsiResult {
        private final double rsi;
        private final double signal;
        private final double prevRsi;
    }

    @Getter
    @AllArgsConstructor
    public static class MacdResult {
        private final DomesticMomentumAnalysisDto.MACD macd;
        private final List<BigDecimal> recentHistograms;
    }
}
