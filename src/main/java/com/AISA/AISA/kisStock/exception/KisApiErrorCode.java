package com.AISA.AISA.kisStock.exception;

import com.AISA.AISA.global.errorcode.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum KisApiErrorCode implements ErrorCode {

    TOKEN_ISSUANCE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, false, "K500-1", "KIS API 접근 토큰 발급에 실패했습니다."),
    STOCK_PRICE_FETCH_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, false, "k500-2", "KIS API를 통한 주식 현재가 조회에 실패했습니다."),
    STOCK_NOT_FOUND(HttpStatus.NOT_FOUND, false, "S404-1", "주식 정보를 찾을 수 없습니다."),
    INVALID_MARKET_CODE(HttpStatus.BAD_REQUEST, false, "K400-1", "유효하지 않은 시장 코드입니다."),
    INDEX_FETCH_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, false, "K500-3", "KIS API를 통한 지수 조회에 실패했습니다."),
    DIVIDEND_FETCH_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, false, "K500-4", "KIS API를 통한 배당 조회에 실패했습니다.");
    private final HttpStatus httpStatus;
    private final boolean isSuccess;
    private final String code;
    private final String message;
}