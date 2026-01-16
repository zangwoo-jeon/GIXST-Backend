package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockInvestorDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StockInvestorDailyRepository extends JpaRepository<StockInvestorDaily, Long> {

        // 특정 종목의 특정 기간 데이터 조회 (날짜 오름차순)
        List<StockInvestorDaily> findByStockAndDateBetweenOrderByDateAsc(Stock stock, LocalDate startDate,
                        LocalDate endDate);

        // 특정 종목의 가장 최근 데이터 날짜 조회 (데이터 갱신 필요 여부 확인용)
        @Query("SELECT MAX(s.date) FROM StockInvestorDaily s WHERE s.stock = :stock")
        Optional<LocalDate> findLatestDateByStock(@Param("stock") Stock stock);

        // 특정 종목의 특정 날짜 데이터 존재 여부
        boolean existsByStockAndDate(Stock stock, LocalDate date);

        // 최근 n개월간 모든 종목의 수급 데이터 합계 조회 (랭킹용)
        @Query("SELECT s.stock.stockCode as stockCode, s.stock.stockName as stockName, " +
                        "SUM(s.personalNetBuyAmount) as personalNetBuyAmount, " +
                        "SUM(s.foreignerNetBuyAmount) as foreignerNetBuyAmount, " +
                        "SUM(s.institutionNetBuyAmount) as institutionNetBuyAmount " +
                        "FROM StockInvestorDaily s " +
                        "WHERE s.date >= :startDate " +
                        "GROUP BY s.stock.stockCode, s.stock.stockName")
        List<com.AISA.AISA.kisStock.dto.InvestorTrend.InvestorTrendAggregationProjection> findAggregatedInvestorTrend(
                        @Param("startDate") LocalDate startDate);
}
