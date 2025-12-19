package com.AISA.AISA.portfolio.PortfolioGroup.dto;

import com.AISA.AISA.portfolio.PortfolioGroup.Portfolio;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@NoArgsConstructor
public class PortfolioResponse {
    private UUID portId;
    private UUID memberId;
    private String portName;
    private LocalDateTime createdAt;
    private boolean mainPort;

    public PortfolioResponse(Portfolio portfolio) {
        this.portId = portfolio.getPortId();
        this.memberId = portfolio.getMemberId();
        this.portName = portfolio.getPortName();
        this.createdAt = portfolio.getCreatedAt();
        this.mainPort = portfolio.isMainPort();
    }
}
