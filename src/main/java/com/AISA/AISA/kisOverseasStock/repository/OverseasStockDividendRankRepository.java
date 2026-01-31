package com.AISA.AISA.kisOverseasStock.repository;

import com.AISA.AISA.kisOverseasStock.entity.OverseasStockDividendRank;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OverseasStockDividendRankRepository extends JpaRepository<OverseasStockDividendRank, Long> {
    List<OverseasStockDividendRank> findAllByOrderByRankAsc();
}
