package com.AISA.AISA.kisStock.repository;

import com.AISA.AISA.kisStock.Entity.stock.SubIndustry;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SubIndustryRepository extends JpaRepository<SubIndustry, Long> {
    Optional<SubIndustry> findByCode(String code);
}
