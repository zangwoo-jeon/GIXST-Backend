package com.AISA.AISA.global.response;

public record SuccessResponse<T> (
        boolean Success,
        String message,
        T data
        ) {}
