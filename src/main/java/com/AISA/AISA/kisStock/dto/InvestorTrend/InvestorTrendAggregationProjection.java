package com.AISA.AISA.kisStock.dto.InvestorTrend;

import java.math.BigDecimal;

public interface InvestorTrendAggregationProjection {
    String getStockCode();

    String getStockName();

    BigDecimal getPersonalNetBuyAmount();

    BigDecimal getForeignerNetBuyAmount();

    BigDecimal getInstitutionNetBuyAmount();

}
