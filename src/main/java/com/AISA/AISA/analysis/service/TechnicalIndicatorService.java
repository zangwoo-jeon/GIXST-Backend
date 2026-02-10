package com.AISA.AISA.analysis.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class TechnicalIndicatorService {

    public double calculateRsi(List<BigDecimal> prices, int period) {
        if (prices.size() <= period)
            return 50.0;
        double gain = 0, loss = 0;
        for (int i = prices.size() - period; i < prices.size(); i++) {
            double diff = prices.get(i).doubleValue() - prices.get(i - 1).doubleValue();
            if (diff > 0)
                gain += diff;
            else
                loss -= diff;
        }
        if (loss == 0)
            return 100.0;
        double rs = (gain / period) / (loss / period);
        return 100.0 - (100.0 / (1.0 + rs));
    }

    public double calculateMA(List<BigDecimal> prices, int period) {
        if (prices.size() < period)
            return 0.0;
        return prices.subList(prices.size() - period, prices.size()).stream()
                .mapToDouble(BigDecimal::doubleValue)
                .average().orElse(0.0);
    }

    /**
     * Calculates Stochastic Slow (%K, %D).
     * Returns double[]{slowK, slowD}
     */
    public double[] calculateStochasticSlow(List<BigDecimal> highPrices, List<BigDecimal> lowPrices,
            List<BigDecimal> closePrices, int n, int slowKPeriod, int slowDPeriod) {
        int size = closePrices.size();
        if (size < n + slowKPeriod + slowDPeriod)
            return new double[] { 50.0, 50.0 };

        List<Double> fastKList = new ArrayList<>();
        // Calculate Fast %K for enough days to smooth into Slow %K and Slow %D
        for (int i = size - (slowKPeriod + slowDPeriod + 5); i < size; i++) {
            if (i < n - 1)
                continue;
            double currentClose = closePrices.get(i).doubleValue();
            double lowestLow = lowPrices.subList(i - n + 1, i + 1).stream().mapToDouble(BigDecimal::doubleValue).min()
                    .orElse(currentClose);
            double highestHigh = highPrices.subList(i - n + 1, i + 1).stream().mapToDouble(BigDecimal::doubleValue)
                    .max().orElse(currentClose);

            double fastK = (highestHigh == lowestLow) ? 50.0
                    : ((currentClose - lowestLow) / (highestHigh - lowestLow)) * 100.0;
            fastKList.add(fastK);
        }

        if (fastKList.size() < slowKPeriod + slowDPeriod)
            return new double[] { 50.0, 50.0 };

        List<Double> slowKList = new ArrayList<>();
        for (int i = slowKPeriod - 1; i < fastKList.size(); i++) {
            double avgK = fastKList.subList(i - slowKPeriod + 1, i + 1).stream().mapToDouble(d -> d).average()
                    .orElse(50.0);
            slowKList.add(avgK);
        }

        double slowK = slowKList.get(slowKList.size() - 1);
        double slowD = slowKList.subList(slowKList.size() - slowDPeriod, slowKList.size()).stream().mapToDouble(d -> d)
                .average().orElse(50.0);

        return new double[] { slowK, slowD };
    }

    /**
     * Calculates 5-day smoothed slope of MA20.
     */
    public double calculateSmoothedSlope(List<BigDecimal> prices, int maPeriod, int slopeWindow) {
        if (prices.size() < maPeriod + slopeWindow + 1)
            return 0.0;

        List<Double> maHistory = new ArrayList<>();
        // Calculate MA history to get slopes
        for (int i = prices.size() - (slopeWindow + 5); i < prices.size(); i++) {
            if (i < maPeriod - 1)
                continue;
            maHistory.add(calculateMA(prices.subList(0, i + 1), maPeriod));
        }

        List<Double> slopes = new ArrayList<>();
        for (int i = 1; i < maHistory.size(); i++) {
            slopes.add(maHistory.get(i) - maHistory.get(i - 1));
        }

        if (slopes.size() < slopeWindow)
            return 0.0;
        return slopes.subList(slopes.size() - slopeWindow, slopes.size()).stream().mapToDouble(d -> d).average()
                .orElse(0.0);
    }

    /**
     * Calculates Volume Z-Score based on last 20 days.
     */
    public double calculateVolumeZScore(List<BigDecimal> volumes) {
        if (volumes.size() < 20)
            return 0.0;
        List<Double> last20 = volumes.subList(volumes.size() - 20, volumes.size()).stream()
                .map(BigDecimal::doubleValue)
                .collect(Collectors.toList());

        DescriptiveStatistics stats = new DescriptiveStatistics();
        for (Double value : last20) {
            if (value != null) {
                stats.addValue(value);
            }
        }

        double mean = stats.getMean();
        double stdDev = stats.getStandardDeviation();
        double todayVolume = volumes.get(volumes.size() - 1).doubleValue();

        if (stdDev == 0)
            return 0.0;
        return (todayVolume - mean) / stdDev;
    }
}
