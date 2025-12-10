package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.StockDividendRank;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StockDividendRankRepository extends JpaRepository<StockDividendRank, Long> {
}
