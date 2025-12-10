package com.AISA.AISA.portfolio.PortfolioGroup.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@NoArgsConstructor
public class PortfolioCreateRequest {
    private UUID memberId;
    private String portName;
}
