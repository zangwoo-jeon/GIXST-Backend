package com.AISA.AISA.kisOverseasStock.repository;

import com.AISA.AISA.kisOverseasStock.entity.OverseasStockFinancialStatement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KisOverseasStockFinancialStatementRepository
        extends JpaRepository<OverseasStockFinancialStatement, Long> {

    List<OverseasStockFinancialStatement> findByStockCodeAndDivCodeOrderByStacYymmAsc(String stockCode, String divCode);

    Optional<OverseasStockFinancialStatement> findByStockCodeAndStacYymmAndDivCode(String stockCode, String stacYymm,
            String divCode);
}
