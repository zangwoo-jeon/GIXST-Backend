package com.AISA.AISA.global.util;

/**
 * Utility class for handling stock code normalization.
 * Some overseas stock codes contain '/', which can cause issues in REST URLs.
 * This utility helps convert between the internal normalized format (with '.')
 * and the KIS API format (with '/').
 */
public class StockCodeUtils {

    /**
     * Converts an internal stock code (normalized with '.') to the KIS API format
     * (with '/').
     * Example: "BRK.A" -> "BRK/A"
     */
    public static String toKisCode(String stockCode) {
        if (stockCode == null) {
            return null;
        }
        return stockCode.replace('.', '/');
    }

    /**
     * Converts a KIS API stock code (with '/') to the internal normalized format
     * (with '.').
     * Example: "BRK/A" -> "BRK.A"
     */
    public static String toInternalCode(String stockCode) {
        if (stockCode == null) {
            return null;
        }
        return stockCode.replace('/', '.');
    }
}
