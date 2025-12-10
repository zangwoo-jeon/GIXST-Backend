package com.AISA.AISA.member.adapter.in.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class MemberSignupRequest {

    private String displayName;
    private String userName;
    private String password;
}
