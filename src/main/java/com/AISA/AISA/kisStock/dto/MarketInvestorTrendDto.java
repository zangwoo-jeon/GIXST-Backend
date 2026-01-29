package com.AISA.AISA.kisStock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MarketInvestorTrendDto {
    private List<MarketTrendItem> trends;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class MarketTrendItem {
        private String date;
        private String indexPrice;
        private String change;
        private String changeRate;
        private String changeSign;

        // Cumulative Net Buy Amount (Million KRW)
        private String foreignerNetBuy;
        private String personalNetBuy;
        private String institutionNetBuy;

        // Detailed Institution
        private String securitiesNetBuy; // 증권
        private String investmentTrustNetBuy; // 투신
        private String privateFundNetBuy; // 사모부드
        private String bankNetBuy; // 은행
        private String insuranceNetBuy; // 보험
        private String merchantBankNetBuy; // 종금
        private String pensionFundNetBuy; // 기금
        private String etcCorporateNetBuy; // 기타법인
        private String etcNetBuy; // 기타
    }
}
