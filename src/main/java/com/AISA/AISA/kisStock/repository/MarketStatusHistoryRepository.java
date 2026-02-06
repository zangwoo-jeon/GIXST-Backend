package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.Index.MarketStatusHistory;
import com.AISA.AISA.kisStock.enums.MarketType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MarketStatusHistoryRepository extends JpaRepository<MarketStatusHistory, Long> {

    List<MarketStatusHistory> findAllByMarketNameAndDateBetweenOrderByDateAsc(
            MarketType marketName, LocalDate startDate, LocalDate endDate);

    Optional<MarketStatusHistory> findByMarketNameAndDate(MarketType marketName, LocalDate date);

    boolean existsByMarketNameAndDate(MarketType marketName, LocalDate date);
}
