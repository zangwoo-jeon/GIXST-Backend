package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockMarketCap;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StockMarketCapRepository extends JpaRepository<StockMarketCap, Long> {
    Optional<StockMarketCap> findByStock(Stock stock);

    @EntityGraph(attributePaths = "stock")
    List<StockMarketCap> findTop100ByOrderByMarketCapDesc();

    @EntityGraph(attributePaths = "stock")
    List<StockMarketCap> findByOrderByMarketCapDesc(Pageable pageable);
}
