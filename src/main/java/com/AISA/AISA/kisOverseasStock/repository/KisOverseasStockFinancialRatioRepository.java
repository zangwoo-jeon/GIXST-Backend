package com.AISA.AISA.kisOverseasStock.repository;

import com.AISA.AISA.kisOverseasStock.entity.OverseasStockFinancialRatio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KisOverseasStockFinancialRatioRepository extends JpaRepository<OverseasStockFinancialRatio, Long> {
    Optional<OverseasStockFinancialRatio> findByStockCodeAndStacYymmAndDivCode(String stockCode, String stacYymm,
            String divCode);

    List<OverseasStockFinancialRatio> findByStockCodeAndDivCodeOrderByStacYymmAsc(String stockCode, String divCode);

    OverseasStockFinancialRatio findTop1ByStockCodeAndDivCodeOrderByStacYymmDesc(String stockCode, String divCode);

    List<OverseasStockFinancialRatio> findTop5ByStockCodeAndDivCodeOrderByStacYymmDesc(String stockCode,
            String divCode);
}
