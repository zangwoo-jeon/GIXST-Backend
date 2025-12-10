package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.StockFinancialRank;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockFinancialRankRepository extends JpaRepository<StockFinancialRank, Long> {

    // 매출액 순 (내림차순)
    List<StockFinancialRank> findAllByOrderBySaleAccountDesc();

    // 영업이익 순
    List<StockFinancialRank> findAllByOrderByOperatingProfitDesc();

    // 당기순이익 순
    List<StockFinancialRank> findAllByOrderByNetIncomeDesc();
}
