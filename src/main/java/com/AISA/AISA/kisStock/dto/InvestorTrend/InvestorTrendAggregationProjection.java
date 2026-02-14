package com.AISA.AISA.kisStock.dto.InvestorTrend;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import java.math.BigDecimal;

public interface InvestorTrendAggregationProjection {
    String getStockCode();

    String getStockName();

    BigDecimal getPersonalNetBuyAmount();

    BigDecimal getForeignerNetBuyAmount();

    BigDecimal getInstitutionNetBuyAmount();

    Stock.StockType getStockType();

}
