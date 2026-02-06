package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.FuturesInvestorDaily;
import com.AISA.AISA.kisStock.enums.FuturesMarketType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface FuturesInvestorDailyRepository extends JpaRepository<FuturesInvestorDaily, Long> {

    List<FuturesInvestorDaily> findAllByMarketTypeAndDateBetweenOrderByDateAsc(
            FuturesMarketType marketType, LocalDate startDate, LocalDate endDate);

    boolean existsByMarketTypeAndDate(FuturesMarketType marketType, LocalDate date);
}
