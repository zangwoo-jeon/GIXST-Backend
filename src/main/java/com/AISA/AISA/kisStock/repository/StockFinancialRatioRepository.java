package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.StockFinancialRatio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockFinancialRatioRepository extends JpaRepository<StockFinancialRatio, Long> {

        // 특정 종목의 비율 이력 조회
        Optional<StockFinancialRatio> findByStockCodeAndStacYymmAndDivCode(String stockCode, String stacYymm,
                        String divCode);

        // ROE 랭킹 조회
        List<StockFinancialRatio> findAllByDivCodeAndStacYymmAndIsSuspendedFalseOrderByRoeDesc(String divCode,
                        String stacYymm);

        // EPS 랭킹 조회
        List<StockFinancialRatio> findAllByDivCodeAndStacYymmAndIsSuspendedFalseOrderByEpsDesc(String divCode,
                        String stacYymm);

        // 부채비율 낮은 순 랭킹 조회
        List<StockFinancialRatio> findAllByDivCodeAndStacYymmAndIsSuspendedFalseOrderByDebtRatioAsc(String divCode,
                        String stacYymm);

        // PER 낮은 순 랭킹 조회 (0보다 큰 값만)
        List<StockFinancialRatio> findAllByDivCodeAndStacYymmAndIsSuspendedFalseAndPerGreaterThanOrderByPerAsc(
                        String divCode,
                        String stacYymm,
                        java.math.BigDecimal zero);

        // PBR 낮은 순 랭킹 조회 (0보다 큰 값만)
        List<StockFinancialRatio> findAllByDivCodeAndStacYymmAndIsSuspendedFalseAndPbrGreaterThanOrderByPbrAsc(
                        String divCode,
                        String stacYymm,
                        java.math.BigDecimal zero);

        // PSR 낮은 순 랭킹 조회 (0보다 큰 값만)
        List<StockFinancialRatio> findAllByDivCodeAndStacYymmAndIsSuspendedFalseAndPsrGreaterThanOrderByPsrAsc(
                        String divCode,
                        String stacYymm,
                        java.math.BigDecimal zero);

        // 가장 최근 결산월 조회
        StockFinancialRatio findTop1ByDivCodeOrderByStacYymmDesc(String divCode);

        // 특정 종목의 가장 최근 결산월 조회
        StockFinancialRatio findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(String stockCode, String divCode);
}
