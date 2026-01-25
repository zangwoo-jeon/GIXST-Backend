package com.AISA.AISA.kisOverseasStock.repository;

import com.AISA.AISA.kisOverseasStock.entity.OverseasStockCashFlow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KisOverseasStockCashFlowRepository extends JpaRepository<OverseasStockCashFlow, Long> {
    Optional<OverseasStockCashFlow> findByStockCodeAndStacYymmAndDivCode(String stockCode, String stacYymm,
            String divCode);

    List<OverseasStockCashFlow> findByStockCodeAndDivCodeOrderByStacYymmDesc(String stockCode, String divCode);
}
