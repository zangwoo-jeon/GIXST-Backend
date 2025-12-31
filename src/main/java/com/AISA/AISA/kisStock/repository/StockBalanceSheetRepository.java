package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.StockBalanceSheet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockBalanceSheetRepository extends JpaRepository<StockBalanceSheet, Long> {
    List<StockBalanceSheet> findByStockCodeAndDivCodeOrderByStacYymmDesc(String stockCode, String divCode);

    StockBalanceSheet findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(String stockCode, String divCode);
}
