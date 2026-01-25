package com.AISA.AISA.kisOverseasStock.repository;

import com.AISA.AISA.kisOverseasStock.entity.OverseasStockTradingMultiple;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface OverseasStockTradingMultipleRepository extends JpaRepository<OverseasStockTradingMultiple, Long> {
    Optional<OverseasStockTradingMultiple> findByStockCode(String stockCode);
}
