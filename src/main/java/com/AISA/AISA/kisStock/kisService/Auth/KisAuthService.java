package com.AISA.AISA.kisStock.kisService.Auth;

import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.kisStock.dto.Auth.KisAuthRequest;
import com.AISA.AISA.kisStock.dto.Auth.KisAuthResponse;
import com.AISA.AISA.kisStock.exception.KisApiErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class KisAuthService {
    @Value("${kis.base-url}")
    private String baseUrl;

    @Value("${kis.appkey}")
    private String appkey;

    @Value("${kis.appsecret}")
    private String appsecret;

    @Value("${kis.auth-url}")
    private String authUrl;

    private String accessToken;
    private LocalDateTime tokenExpiresAt;

    private final WebClient webClient;

    public String getAccessToken() {
        // 토큰이 없거나 만료되면 새로 발급
        if (accessToken == null || tokenExpiresAt == null ||
                LocalDateTime.now().isAfter(tokenExpiresAt)) {
            log.info("토큰이 없거나 만들어지지 않았습니다. 토큰을 발급합니다.");
            return refreshAccessToken();
        }
        log.debug("기존의 액세스 토큰을 재사용합니다.");
        return accessToken;
    }

    private String refreshAccessToken() {
        KisAuthRequest authRequest = KisAuthRequest.builder()
                .grantType("client_credentials")
                .appkey(appkey)
                .appsecret(appsecret)
                .build();

        KisAuthResponse authResponse = webClient.post()
                .uri(uriBuilder -> uriBuilder.path(authUrl).build())
                .bodyValue(authRequest)
                .retrieve()
                .bodyToMono(KisAuthResponse.class)
                .block();

        if (authResponse != null && authResponse.getAccessToken() != null) {
            this.accessToken = "Bearer " + authResponse.getAccessToken();
            this.tokenExpiresAt = LocalDateTime.now().plusSeconds(authResponse.getExpiresIn() - 60);
            log.info("KIS API 액세스 토큰이 발급되었습니다. 만기 시간은 : {}", this.tokenExpiresAt);
            return this.accessToken;
        } else {
            log.error("KIS API 액세스 토큰 발급에 실패했습니다. 응답: {}", authResponse);
            throw new BusinessException(KisApiErrorCode.TOKEN_ISSUANCE_FAILED);
        }
    }
}
