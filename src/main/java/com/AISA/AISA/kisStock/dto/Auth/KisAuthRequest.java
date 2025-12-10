package com.AISA.AISA.kisStock.dto.Auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class KisAuthRequest {
    @JsonProperty("grant_type")
    private final String grantType;

    @JsonProperty("appkey")
    private final String appkey;

    @JsonProperty("appsecret")
    private final String appsecret;
}
