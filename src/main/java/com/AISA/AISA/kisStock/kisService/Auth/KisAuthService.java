package com.AISA.AISA.kisStock.kisService.Auth;

import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.kisStock.dto.Auth.KisAuthRequest;
import com.AISA.AISA.kisStock.dto.Auth.KisAuthResponse;
import com.AISA.AISA.kisStock.exception.KisApiErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import jakarta.annotation.PostConstruct;

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
    private final ObjectMapper objectMapper; // Jackson ObjectMapper 주입

    public String getAccessToken() {
        // 1차 체크 (락 없이)
        if (accessToken != null && tokenExpiresAt != null && LocalDateTime.now().isBefore(tokenExpiresAt)) {
            log.debug("기존의 액세스 토큰을 재사용합니다.");
            return accessToken;
        }

        // 동기화 블록 (락 획득)
        synchronized (this) {
            // 2차 체크 (다른 스레드가 이미 갱신했는지 확인)
            if (accessToken != null && tokenExpiresAt != null && LocalDateTime.now().isBefore(tokenExpiresAt)) {
                log.debug("다른 스레드에 의해 갱신된 토큰을 사용합니다.");
                return accessToken;
            }

            log.info("토큰이 없거나 만료되었습니다. 토큰을 발급합니다.");
            return refreshAccessToken();
        }
    }

    public void invalidateToken() {
        synchronized (this) {
            log.info("KIS API 액세스 토큰을 무효화합니다.");
            this.accessToken = null;
            this.tokenExpiresAt = null;
        }
    }

    @PostConstruct
    public void init() {
        try {
            log.info("서버 시작 시 KIS API 액세스 토큰 초기화를 시도합니다...");
            getAccessToken();
        } catch (Exception e) {
            log.error("초기 KIS API 토큰 발급 실패: {}", e.getMessage());
        }
    }

    public String refreshAccessToken() {
        KisAuthRequest authRequest = KisAuthRequest.builder()
                .grantType("client_credentials")
                .appkey(appkey)
                .appsecret(appsecret)
                .build();

        String responseBody = null; // Declare outside try block
        try {
            // 응답을 먼저 String으로 받아서 확인
            responseBody = webClient.post()
                    .uri(uriBuilder -> uriBuilder.path(authUrl).build())
                    .contentType(MediaType.APPLICATION_JSON) // Content-Type 명시
                    .bodyValue(authRequest)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.error("KIS Auth Response Body (Debugging): {}", responseBody); // Change to ERROR for visibility

            if (responseBody == null || responseBody.isBlank()) {
                throw new BusinessException(KisApiErrorCode.TOKEN_ISSUANCE_FAILED);
            }

            // String -> Object 매핑
            KisAuthResponse authResponse = objectMapper.readValue(responseBody, KisAuthResponse.class);

            if (authResponse != null && authResponse.getAccessToken() != null) {
                this.accessToken = "Bearer " + authResponse.getAccessToken();
                this.tokenExpiresAt = LocalDateTime.now().plusSeconds(authResponse.getExpiresIn() - 60);
                log.info("KIS API 액세스 토큰이 발급되었습니다. 만기 시간은 : {}", this.tokenExpiresAt);
                return this.accessToken;
            } else {
                log.error("KIS API 액세스 토큰 발급 실패 (유효하지 않은 응답): {}", responseBody);
                throw new BusinessException(KisApiErrorCode.TOKEN_ISSUANCE_FAILED);
            }
        } catch (WebClientResponseException e) {
            log.error("KIS API 호출 실패 (HTTP {}): {}", e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new BusinessException(KisApiErrorCode.TOKEN_ISSUANCE_FAILED);
        } catch (JsonProcessingException e) {
            log.error("KIS API 응답 파싱 오류. 응답 내용이 JSON이 아닐 수 있습니다. Body: {}", responseBody, e);
            throw new BusinessException(KisApiErrorCode.TOKEN_ISSUANCE_FAILED);
        } catch (Exception e) {
            log.error("KIS API 호출 중 오류 발생: {}", e.getMessage(), e);
            throw new BusinessException(KisApiErrorCode.TOKEN_ISSUANCE_FAILED);
        }
    }
}
