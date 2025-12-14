package com.AISA.AISA.global.exception;

import com.AISA.AISA.global.errorcode.CommonErrorCode;
import com.AISA.AISA.global.response.ResponseErrorEntity;
import com.AISA.AISA.member.adapter.in.exception.MemberErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
@Component
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    protected ResponseEntity<ResponseErrorEntity> handleBusinessException(BusinessException e) {
        log.error("BusinessException occurred: {}", e.getMessage(), e);
        return ResponseErrorEntity.toResponseEntity(e.getErrorCode());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ResponseErrorEntity> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.error("DataIntegrityViolationException occurred: {}", e.getMessage(), e);
        String rootCauseMessage = e.getMostSpecificCause().getMessage();

        // user_name이 중복이면 DUPLICATE_USERNAME, display_name이 중복이면 DUPLICATE_DISPLAY_NAME
        // 처리
        if (rootCauseMessage.contains("uk_member_user_name")) {
            return ResponseErrorEntity.toResponseEntity(MemberErrorCode.DUPLICATE_USERNAME);
        } else if (rootCauseMessage.contains("uk_member_display_name")) {
            return ResponseErrorEntity.toResponseEntity(MemberErrorCode.DUPLICATE_DISPLAY_NAME);
        }

        // 그 외의 데이터 무결성 오류는 일반적인 서버 오류로 처리
        return ResponseErrorEntity.toResponseEntity(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(Exception.class)
    protected ResponseEntity<ResponseErrorEntity> handleException(Exception e) {
        log.error("Unhandled Exception occurred: {}", e.getMessage(), e);
        // For debugging purposes, we might want to return the message, but generally
        // INTERNAL_SERVER_ERROR is safer.
        // However, to debug this issue, I will check if I can include the cause.
        // CommonErrorCode.INTERNAL_SERVER_ERROR usually has a generic message.
        // Let's rely on logs mainly, but since I can't see logs, I'll temporary print
        // the message?
        // No, let's look at the logs via tool if possible? No tool for logs.
        // I'll assume the user can see the response.
        // I will return INTERNAL_SERVER_ERROR but maybe I can notify the user to assume
        // it's a connection issue.
        // Wait, if I add this handler, the User will see my JSON response, NOT the
        // timestamp one.
        // This confirms if it's hitting my app.
        return ResponseErrorEntity.toResponseEntity(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

}