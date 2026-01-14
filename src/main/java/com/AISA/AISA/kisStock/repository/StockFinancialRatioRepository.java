package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.StockFinancialRatio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockFinancialRatioRepository extends JpaRepository<StockFinancialRatio, Long> {

        // 특정 종목의 비율 이력 조회
        Optional<StockFinancialRatio> findByStockCodeAndStacYymmAndDivCode(String stockCode, String stacYymm,
                        String divCode);

        // ROE 랭킹 조회
        @Query("SELECT s FROM StockFinancialRatio s WHERE s.divCode = :divCode AND s.stacYymm = :stacYymm AND s.isSuspended = false ORDER BY s.roe DESC")
        List<StockFinancialRatio> findAllByDivCodeAndStacYymmAndIsSuspendedFalseOrderByRoeDesc(
                        @Param("divCode") String divCode, @Param("stacYymm") String stacYymm);

        // EPS 랭킹 조회
        @Query("SELECT s FROM StockFinancialRatio s WHERE s.divCode = :divCode AND s.stacYymm = :stacYymm AND s.isSuspended = false ORDER BY s.eps DESC")
        List<StockFinancialRatio> findAllByDivCodeAndStacYymmAndIsSuspendedFalseOrderByEpsDesc(
                        @Param("divCode") String divCode, @Param("stacYymm") String stacYymm);

        // 부채비율 낮은 순 랭킹 조회
        @Query("SELECT s FROM StockFinancialRatio s WHERE s.divCode = :divCode AND s.stacYymm = :stacYymm AND s.isSuspended = false ORDER BY s.debtRatio ASC")
        List<StockFinancialRatio> findAllByDivCodeAndStacYymmAndIsSuspendedFalseOrderByDebtRatioAsc(
                        @Param("divCode") String divCode, @Param("stacYymm") String stacYymm);

        // PER 낮은 순 랭킹 조회 (0보다 큰 값만)
        @Query("SELECT s FROM StockFinancialRatio s WHERE s.divCode = :divCode AND s.stacYymm = :stacYymm AND s.isSuspended = false AND s.per > :zero ORDER BY s.per ASC")
        List<StockFinancialRatio> findAllByDivCodeAndStacYymmAndIsSuspendedFalseAndPerGreaterThanOrderByPerAsc(
                        @Param("divCode") String divCode,
                        @Param("stacYymm") String stacYymm,
                        @Param("zero") java.math.BigDecimal zero);

        // PBR 낮은 순 랭킹 조회 (0보다 큰 값만)
        @Query("SELECT s FROM StockFinancialRatio s WHERE s.divCode = :divCode AND s.stacYymm = :stacYymm AND s.isSuspended = false AND s.pbr > :zero ORDER BY s.pbr ASC")
        List<StockFinancialRatio> findAllByDivCodeAndStacYymmAndIsSuspendedFalseAndPbrGreaterThanOrderByPbrAsc(
                        @Param("divCode") String divCode,
                        @Param("stacYymm") String stacYymm,
                        @Param("zero") java.math.BigDecimal zero);

        // PSR 낮은 순 랭킹 조회 (0보다 큰 값만)
        @Query("SELECT s FROM StockFinancialRatio s WHERE s.divCode = :divCode AND s.stacYymm = :stacYymm AND s.isSuspended = false AND s.psr > :zero ORDER BY s.psr ASC")
        List<StockFinancialRatio> findAllByDivCodeAndStacYymmAndIsSuspendedFalseAndPsrGreaterThanOrderByPsrAsc(
                        @Param("divCode") String divCode,
                        @Param("stacYymm") String stacYymm,
                        @Param("zero") java.math.BigDecimal zero);

        // 가장 최근 결산월 조회
        StockFinancialRatio findTop1ByDivCodeOrderByStacYymmDesc(String divCode);

        // 특정 종목의 가장 최근 결산월 조회
        StockFinancialRatio findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(String stockCode, String divCode);

        // 과거 5개년 데이터 조회 (Average ROE 계산용)
        List<StockFinancialRatio> findTop5ByStockCodeAndDivCodeOrderByStacYymmDesc(String stockCode, String divCode);

        // Fetch ALL ratios for a given divCode to perform in-memory grouping and
        // Ranking
        List<StockFinancialRatio> findAllByDivCode(String divCode);

        List<StockFinancialRatio> findByDivCodeAndStacYymmGreaterThanEqual(String divCode, String stacYymm);
}
