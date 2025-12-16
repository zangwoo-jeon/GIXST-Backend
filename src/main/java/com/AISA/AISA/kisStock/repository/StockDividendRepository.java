package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.StockDividend;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockDividendRepository extends JpaRepository<StockDividend, Long> {
    List<StockDividend> findByStock_StockCodeAndRecordDateBetweenOrderByRecordDateDesc(String stockCode,
            String startDate, String endDate);

    boolean existsByStock_StockCodeAndRecordDate(String stockCode, String recordDate);
}
