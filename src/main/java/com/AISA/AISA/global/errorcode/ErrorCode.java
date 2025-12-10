package com.AISA.AISA.global.errorcode;

import org.springframework.http.HttpStatus;

public interface ErrorCode {
    HttpStatus getHttpStatus();
    boolean isSuccess();
    String getCode();
    String getMessage();
}
