package com.AISA.AISA.global.oauth.repository;

import com.AISA.AISA.global.util.CookieUtils;
import com.nimbusds.oauth2.sdk.util.StringUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class RedisOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    @Qualifier("oAuth2RedisTemplate")
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String OAUTH2_REQUEST_CACHE_KEY_PREFIX = "oauth2:auth:request:";
    private static final long DURATION_MINUTES = 5;

    public static final String REDIRECT_URI_PARAM_COOKIE_NAME = "redirect_uri";
    private static final int COOKIE_EXPIRE_SECONDS = 180;

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        String state = request.getParameter("state");
        if (state == null) {
            return null;
        }
        String key = OAUTH2_REQUEST_CACHE_KEY_PREFIX + state;
        log.info("Loading OAuth2 request from Redis. Key: {}", key);
        return (OAuth2AuthorizationRequest) redisTemplate.opsForValue().get(key);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest, HttpServletRequest request,
            HttpServletResponse response) {
        if (authorizationRequest == null) {
            removeAuthorizationRequest(request, response);
            removeAuthorizationRequestCookies(request, response);
            return;
        }
        String state = authorizationRequest.getState();
        if (state == null) {
            throw new IllegalArgumentException("OAuth2AuthorizationRequest must have a state parameter");
        }
        String key = OAUTH2_REQUEST_CACHE_KEY_PREFIX + state;
        log.info("Saving OAuth2 request to Redis. Key: {}, Expiration: {} mins", key, DURATION_MINUTES);
        redisTemplate.opsForValue().set(key, authorizationRequest, DURATION_MINUTES, TimeUnit.MINUTES);

        String redirectUriAfterLogin = request.getParameter(REDIRECT_URI_PARAM_COOKIE_NAME);
        if (StringUtils.isNotBlank(redirectUriAfterLogin)) {
            CookieUtils.addCookie(response, REDIRECT_URI_PARAM_COOKIE_NAME, redirectUriAfterLogin,
                    COOKIE_EXPIRE_SECONDS);
        }
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
            HttpServletResponse response) {
        String state = request.getParameter("state");
        if (state == null) {
            return null;
        }
        String key = OAUTH2_REQUEST_CACHE_KEY_PREFIX + state;
        log.info("Removing OAuth2 request from Redis. Key: {}", key);
        OAuth2AuthorizationRequest authorizationRequest = (OAuth2AuthorizationRequest) redisTemplate.opsForValue()
                .get(key);
        if (authorizationRequest != null) {
            redisTemplate.delete(key);
        }
        return authorizationRequest;
    }

    public void removeAuthorizationRequestCookies(HttpServletRequest request, HttpServletResponse response) {
        CookieUtils.deleteCookie(request, response, REDIRECT_URI_PARAM_COOKIE_NAME);
    }
}
