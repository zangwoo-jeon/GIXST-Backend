package com.AISA.AISA.kisStock.repository;

import java.math.BigDecimal;

public interface MarketAccumulatedProjection {
    BigDecimal getPersonal();

    BigDecimal getForeigner();

    BigDecimal getInstitution();

    BigDecimal getSecurities();

    BigDecimal getInvestmentTrust();

    BigDecimal getPrivateFund();

    BigDecimal getBank();

    BigDecimal getInsurance();

    BigDecimal getMerchantBank();

    BigDecimal getPensionFund();

    BigDecimal getEtcCorporate();

    BigDecimal getEtc();
}
