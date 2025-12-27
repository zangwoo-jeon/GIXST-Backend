package com.AISA.AISA.member.adapter.in.exception;

import com.AISA.AISA.global.errorcode.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements ErrorCode {
    INVALID_CREDENTIALS_LENGTH(HttpStatus.BAD_REQUEST, false, "M400-1", "아이디 혹은 비밀번호가 8자 이상이어야 합니다."),
    INVALID_PASSWORD_POLICY(HttpStatus.BAD_REQUEST, false, "M400-2", "비밀번호는 문자, 숫자를 포함해야 합니다."),
    DUPLICATE_USERNAME(HttpStatus.CONFLICT, false, "M409-1", "이미 존재하는 아이디입니다"),
    DUPLICATE_DISPLAY_NAME(HttpStatus.CONFLICT, false, "M409-2", "이미 존재하는 닉네임입니다"),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, false, "M409-3", "이미 존재하는 이메일입니다"),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, false, "M404-1", "해당 회원을 찾을 수 없습니다."),
    INVALID_CURRENT_PASSWORD(HttpStatus.BAD_REQUEST, false, "M400-3", "현재 비밀번호가 일치하지 않습니다."),
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, false, "M401-1", "비밀번호가 일치하지 않습니다.");

    private final HttpStatus httpStatus;
    private final boolean isSuccess;
    private final String code;
    private final String message;
}
