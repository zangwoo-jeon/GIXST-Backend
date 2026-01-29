package com.AISA.AISA.kisStock.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class KisMarketInvestorTrendResponse {
    @JsonProperty("rt_cd")
    private String rtCd;

    @JsonProperty("msg_cd")
    private String msgCd;

    @JsonProperty("msg1")
    private String msg1;

    @JsonProperty("output")
    private List<Output> output;

    @Getter
    @Setter
    public static class Output {
        @JsonProperty("stck_bsop_date")
        private String stckBsopDate; // 주식 영업 일자

        @JsonProperty("bstp_nmix_prpr")
        private String bstpNmixPrpr; // 업종 지수 현재가

        @JsonProperty("bstp_nmix_prdy_vrss")
        private String bstpNmixPrdyVrss; // 업종 지수 전일 대비

        @JsonProperty("prdy_vrss_sign")
        private String prdyVrssSign; // 전일 대비 부호

        @JsonProperty("bstp_nmix_prdy_ctrt")
        private String bstpNmixPrdyCtrt; // 업종 지수 전일 대비율

        @JsonProperty("frgn_ntby_qty")
        private String frgnNtbyQty; // 외국인 순매수 수량

        @JsonProperty("prsn_ntby_qty")
        private String prsnNtbyQty; // 개인 순매수 수량

        @JsonProperty("orgn_ntby_qty")
        private String orgnNtbyQty; // 기관계 순매수 수량

        @JsonProperty("scrt_ntby_qty")
        private String scrtNtbyQty; // 증권 순매수 수량

        @JsonProperty("ivtr_ntby_qty")
        private String ivtrNtbyQty; // 투자신탁 순매수 수량

        @JsonProperty("pe_fund_ntby_vol")
        private String peFundNtbyVol; // 사모 펀드 순매수 거래량

        @JsonProperty("bank_ntby_qty")
        private String bankNtbyQty; // 은행 순매수 수량

        @JsonProperty("insu_ntby_qty")
        private String insuNtbyQty; // 보험 순매수 수량

        @JsonProperty("mrbn_ntby_qty")
        private String mrbnNtbyQty; // 종금 순매수 수량

        @JsonProperty("fund_ntby_qty")
        private String fundNtbyQty; // 기금 순매수 수량

        @JsonProperty("etc_ntby_qty")
        private String etcNtbyQty; // 기타 순매수 수량

        @JsonProperty("etc_orgt_ntby_vol")
        private String etcOrgtNtbyVol; // 기타 단체 순매수 거래량

        @JsonProperty("etc_corp_ntby_vol")
        private String etcCorpNtbyVol; // 기타 법인 순매수 거래량

        @JsonProperty("frgn_ntby_tr_pbmn")
        private String frgnNtbyTrPbmn; // 외국인 순매수 거래 대금

        @JsonProperty("prsn_ntby_tr_pbmn")
        private String prsnNtbyTrPbmn; // 개인 순매수 거래 대금

        @JsonProperty("orgn_ntby_tr_pbmn")
        private String orgnNtbyTrPbmn; // 기관계 순매수 거래 대금

        @JsonProperty("scrt_ntby_tr_pbmn")
        private String scrtNtbyTrPbmn; // 증권 순매수 거래 대금

        @JsonProperty("ivtr_ntby_tr_pbmn")
        private String ivtrNtbyTrPbmn; // 투자신탁 순매수 거래 대금

        @JsonProperty("pe_fund_ntby_tr_pbmn")
        private String peFundNtbyTrPbmn; // 사모 펀드 순매수 거래 대금

        @JsonProperty("bank_ntby_tr_pbmn")
        private String bankNtbyTrPbmn; // 은행 순매수 거래 대금

        @JsonProperty("insu_ntby_tr_pbmn")
        private String insuNtbyTrPbmn; // 보험 순매수 거래 대금

        @JsonProperty("mrbn_ntby_tr_pbmn")
        private String mrbnNtbyTrPbmn; // 종금 순매수 거래 대금

        @JsonProperty("fund_ntby_tr_pbmn")
        private String fundNtbyTrPbmn; // 기금 순매수 거래 대금

        @JsonProperty("etc_ntby_tr_pbmn")
        private String etcNtbyTrPbmn; // 기타 순매수 거래 대금

        @JsonProperty("etc_orgt_ntby_tr_pbmn")
        private String etcOrgtNtbyTrPbmn; // 기타 단체 순매수 거래 대금

        @JsonProperty("etc_corp_ntby_tr_pbmn")
        private String etcCorpNtbyTrPbmn; // 기타 법인 순매수 거래 대금
    }
}
