package com.AISA.AISA.portfolio.PortfolioGroup.exception;

import com.AISA.AISA.global.errorcode.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PortfolioErrorCode implements ErrorCode {

    PORTFOLIO_NOT_FOUND(HttpStatus.NOT_FOUND, false, "P404-1", "해당 포트폴리오를 찾을 수 없습니다."),
    MEMBER_HAS_NO_PORTFOLIOS(HttpStatus.NOT_FOUND, false, "P404-2", "해당 회원의 포트폴리오가 존재하지 않습니다."),
    STOCK_NOT_FOUND(HttpStatus.NOT_FOUND, false, "P404-3", "해당 주식을 찾을 수 없습니다."),
    PORTFOLIO_STOCK_NOT_FOUND(HttpStatus.NOT_FOUND, false, "P404-4", "포트폴리오 내 해당 주식을 찾을 수 없습니다."),
    INVALID_SEQUENCE(HttpStatus.BAD_REQUEST, false, "P400-1", "잘못된 순서입니다.");

    private final HttpStatus httpStatus;
    private final boolean isSuccess;
    private final String code;
    private final String message;
}
