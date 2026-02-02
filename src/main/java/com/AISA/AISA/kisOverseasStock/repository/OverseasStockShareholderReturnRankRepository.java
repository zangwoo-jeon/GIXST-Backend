package com.AISA.AISA.kisOverseasStock.repository;

import com.AISA.AISA.kisOverseasStock.entity.OverseasStockShareholderReturnRank;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OverseasStockShareholderReturnRankRepository
        extends JpaRepository<OverseasStockShareholderReturnRank, Long> {
    List<OverseasStockShareholderReturnRank> findAllByOrderByRankAsc(Pageable pageable);
}
