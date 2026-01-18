package com.AISA.AISA.kisStock.dto.InvestorTrend;

import java.math.BigDecimal;

public interface AccumulatedInvestorProjection {
    BigDecimal getPersonalNetBuyAmount();

    BigDecimal getForeignerNetBuyAmount();

    BigDecimal getInstitutionNetBuyAmount();
}
