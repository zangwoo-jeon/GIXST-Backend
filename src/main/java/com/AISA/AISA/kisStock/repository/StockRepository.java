package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, Long> {
    Optional<Stock> findByStockCode(String stockCode);
}
