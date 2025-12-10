package com.AISA.AISA.global.response;

import com.AISA.AISA.global.errorcode.ErrorCode;
import lombok.Builder;
import lombok.Data;
import org.springframework.http.ResponseEntity;

@Data
@Builder
public class ResponseErrorEntity {
    private int status;
    private boolean isSuccess;
    private String name;
    private String code;
    private String message;

    public static ResponseEntity<ResponseErrorEntity> toResponseEntity(ErrorCode e) {
        return ResponseEntity
                .status(e.getHttpStatus())
                .body(ResponseErrorEntity.builder()
                        .status(e.getHttpStatus().value())
                        .isSuccess(e.isSuccess())
                        .name(e.getClass().getSimpleName())
                        .code(e.getCode())
                        .message(e.getMessage())
                        .build());
    }
}
