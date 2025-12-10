package com.AISA.AISA.analysis.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

import java.util.UUID;

public class IsaDTOs {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "ISA 절세 효과 계산 요청")
    public static class IsaCalculateRequest {
        @Schema(description = "예상 배당 수익금", example = "5000000")
        private BigDecimal dividendIncome;

        @Schema(description = "ISA 계좌 유형 (GENERAL: 일반형, SEOMIN: 서민형/농어민형)", example = "GENERAL")
        private IsaAccountType accountType;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "ISA 절세 효과 계산 응답")
    public static class IsaCalculateResponse {
        @Schema(description = "일반 계좌 예상 세금 (15.4%)")
        private BigDecimal normalTax;

        @Schema(description = "ISA 계좌 예상 세금 (비과세 한도 초과분 9.9%)")
        private BigDecimal isaTax;

        @Schema(description = "총 절세 금액 (일반 계좌 세금 - ISA 계좌 세금)")
        private BigDecimal taxSavings;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "ISA 백테스트 기반 절세 효과 계산 요청")
    public static class IsaBacktestRequest {
        @Schema(description = "포트폴리오 ID", example = "123e4567-e89b-12d3-a456-426614174000")
        private UUID portId;

        @Schema(description = "시작 날짜 (YYYYMMDD)", example = "20240101")
        private String startDate;

        @Schema(description = "종료 날짜 (YYYYMMDD)", example = "20241231")
        private String endDate;

        @Schema(description = "ISA 계좌 유형", example = "GENERAL")
        private IsaAccountType accountType;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "ISA 백테스트 기반 절세 효과 계산 응답")
    public static class IsaBacktestResponse {
        @Schema(description = "총 배당 수익")
        private BigDecimal totalDividendIncome;

        @Schema(description = "총 매매 손익 (평가 손익 합계)")
        private BigDecimal totalCapitalGains;

        @Schema(description = "일반 계좌 총 세금 (배당소득세 + 해외ETF 매매차익 과세)")
        private BigDecimal normalTotalTax;

        @Schema(description = "ISA 계좌 총 세금 (손익 통산 후 과세)")
        private BigDecimal isaTotalTax;

        @Schema(description = "총 절세 금액")
        private BigDecimal taxSavings;

        @Schema(description = "최종 수익금 (매매손익 + 배당수익 - 세금)")
        private BigDecimal finalReturn;

        @Schema(description = "원금 (초기 투자 금액)")
        private BigDecimal principal;

        @Schema(description = "수익률 (%)")
        private BigDecimal roi;
    }

    public enum IsaAccountType {
        GENERAL(new BigDecimal("2000000")), // 일반형: 200만원 비과세
        SEOMIN(new BigDecimal("4000000")); // 서민형: 400만원 비과세

        private final BigDecimal taxFreeLimit;

        IsaAccountType(BigDecimal taxFreeLimit) {
            this.taxFreeLimit = taxFreeLimit;
        }

        public BigDecimal getTaxFreeLimit() {
            return taxFreeLimit;
        }
    }
}
