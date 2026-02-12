package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.EtfDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EtfDetailRepository extends JpaRepository<EtfDetail, Long> {
}
