package com.AISA.AISA.member.adapter.in.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PasswordChangeRequest {
    private String currentPassword;
    private String newPassword;
}
