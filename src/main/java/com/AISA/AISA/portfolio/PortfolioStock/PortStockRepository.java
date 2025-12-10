package com.AISA.AISA.portfolio.PortfolioStock;

import com.AISA.AISA.portfolio.PortfolioGroup.Portfolio;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PortStockRepository extends JpaRepository<PortStock, UUID> {
    List<PortStock> findByPortfolio_PortId(UUID portId);

    List<PortStock> findByPortfolio(Portfolio portfolio);

    Optional<PortStock> findByPortfolio_PortIdAndStock_StockCode(UUID portId, String stockCode);

    @Query("SELECT MAX(p.sequence) FROM PortStock p WHERE p.portfolio.portId = :portId")
    Integer findMaxSequenceByPortfolioId(
            @Param("portId") UUID portId);

    List<PortStock> findByPortfolio_PortIdOrderBySequenceAsc(UUID portId);

    List<PortStock> findByPortfolio_PortIdAndSequenceBetween(UUID portId, int startSequence, int endSequence);

    long countByPortfolio_PortId(UUID portId);

    List<PortStock> findByPortfolio_PortIdAndSequenceGreaterThan(UUID portId, int sequence);
}
