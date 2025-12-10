package com.AISA.AISA.global.errorcode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, false, "C400-1", "입력값이 올바르지 않습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, false, "C500-1", "서버 내부 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final boolean isSuccess;
    private final String code;
    private final String message;
}