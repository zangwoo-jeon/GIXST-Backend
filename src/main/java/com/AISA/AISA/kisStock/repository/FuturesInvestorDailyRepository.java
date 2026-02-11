package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.FuturesInvestorDaily;
import com.AISA.AISA.kisStock.enums.FuturesMarketType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface FuturesInvestorDailyRepository extends JpaRepository<FuturesInvestorDaily, Long> {

    List<FuturesInvestorDaily> findAllByMarketTypeAndDateBetweenOrderByDateAsc(
            FuturesMarketType marketType, LocalDate startDate, LocalDate endDate);

    Optional<FuturesInvestorDaily> findByDateAndMarketType(LocalDate date, FuturesMarketType marketType);

    boolean existsByMarketTypeAndDate(FuturesMarketType marketType, LocalDate date);
}
