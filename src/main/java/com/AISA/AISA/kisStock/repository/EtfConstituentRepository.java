package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.EtfConstituent;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EtfConstituentRepository extends JpaRepository<EtfConstituent, Long> {
    List<EtfConstituent> findByEtf(Stock etf);

    void deleteByEtf(Stock etf);
}
