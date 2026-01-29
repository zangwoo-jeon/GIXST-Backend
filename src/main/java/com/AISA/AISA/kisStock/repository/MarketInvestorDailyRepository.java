package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.MarketInvestorDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface MarketInvestorDailyRepository extends JpaRepository<MarketInvestorDaily, Long> {

    Optional<MarketInvestorDaily> findByMarketCodeAndDate(String marketCode, LocalDate date);

    @Query(value = "SELECT " +
            "SUM(m.personal_net_buy) as personal, " +
            "SUM(m.foreigner_net_buy) as foreigner, " +
            "SUM(m.institution_net_buy) as institution, " +
            "SUM(m.securities_net_buy) as securities, " +
            "SUM(m.investment_trust_net_buy) as investmentTrust, " +
            "SUM(m.private_fund_net_buy) as privateFund, " +
            "SUM(m.bank_net_buy) as bank, " +
            "SUM(m.insurance_net_buy) as insurance, " +
            "SUM(m.merchant_bank_net_buy) as merchantBank, " +
            "SUM(m.pension_fund_net_buy) as pensionFund, " +
            "SUM(m.etc_corporate_net_buy) as etcCorporate, " +
            "SUM(m.etc_net_buy) as etc " +
            "FROM market_investor_daily m " +
            "WHERE m.market_code = :marketCode AND m.date >= :startDate", nativeQuery = true)
    MarketAccumulatedProjection findAggregatedMarketInvestorTrend(@Param("marketCode") String marketCode,
            @Param("startDate") LocalDate startDate);
}
