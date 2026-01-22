package com.AISA.AISA.kisOverseasStock.repository;

import com.AISA.AISA.kisOverseasStock.entity.OverseasStockBalanceSheet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KisOverseasStockBalanceSheetRepository extends JpaRepository<OverseasStockBalanceSheet, Long> {

    List<OverseasStockBalanceSheet> findAllByStockCode(String stockCode);

    List<OverseasStockBalanceSheet> findByStockCodeOrderByStacYymmAsc(String stockCode);

    Optional<OverseasStockBalanceSheet> findByStockCodeAndStacYymmAndDivCode(String stockCode, String stacYymm,
            String divCode);
}
