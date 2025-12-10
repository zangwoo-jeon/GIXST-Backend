package com.AISA.AISA.portfolio.PortfolioGroup;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PortfolioRepository extends JpaRepository<Portfolio, UUID> {
    List<Portfolio> findByMemberId(UUID memberId);

    Optional<Portfolio> findByPortIdAndMemberId(UUID portId, UUID memberId);
}
