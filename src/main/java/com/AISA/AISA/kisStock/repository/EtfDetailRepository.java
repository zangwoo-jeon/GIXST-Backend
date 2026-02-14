package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.EtfDetail;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

import java.util.Optional;

@Repository
public interface EtfDetailRepository extends JpaRepository<EtfDetail, Long> {

    @EntityGraph(attributePaths = "stock")
    List<EtfDetail> findAll(Sort sort);

    @EntityGraph(attributePaths = "stock")
    List<EtfDetail> findByUnderlyingIndexAndTrackingMultiplierAndReplicationMethodAndStock_StockCodeNot(
            String underlyingIndex, Double trackingMultiplier, String replicationMethod, String stockCode);

    @EntityGraph(attributePaths = "stock")
    List<EtfDetail> findByUnderlyingIndexAndStock_StockCodeNot(String underlyingIndex, String stockCode);

    @EntityGraph(attributePaths = "stock")
    List<EtfDetail> findByUnderlyingIndexContaining(String indexName);
}
