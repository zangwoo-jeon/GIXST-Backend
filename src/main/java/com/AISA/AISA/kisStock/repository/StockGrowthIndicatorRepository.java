package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.StockGrowthIndicator;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockGrowthIndicatorRepository extends JpaRepository<StockGrowthIndicator, Long> {
    Optional<StockGrowthIndicator> findByStockCodeAndStacYymmAndDivCode(String stockCode, String stacYymm,
            String divCode);

    List<StockGrowthIndicator> findByDivCodeOrderBySalesYoYDesc(String divCode, Pageable pageable);

    List<StockGrowthIndicator> findByDivCodeOrderBySales3YCagrDesc(String divCode, Pageable pageable);

    List<StockGrowthIndicator> findByDivCodeOrderByOpYoYDesc(String divCode, Pageable pageable);

    List<StockGrowthIndicator> findByDivCodeOrderByOp3YCagrDesc(String divCode, Pageable pageable);

    List<StockGrowthIndicator> findByDivCodeOrderByEpsYoYDesc(String divCode, Pageable pageable);

    List<StockGrowthIndicator> findByDivCodeOrderByEps3YCagrDesc(String divCode, Pageable pageable);
}
