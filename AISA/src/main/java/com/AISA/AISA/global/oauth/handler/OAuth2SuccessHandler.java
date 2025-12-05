package com.AISA.AISA.global.oauth.handler;

import com.AISA.AISA.global.jwt.JwtTokenProvider;
import com.AISA.AISA.global.jwt.RefreshToken;
import com.AISA.AISA.global.jwt.RefreshTokenRepository;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.Collection;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String email = (String) oAuth2User.getAttributes().get("email");
        Collection<? extends GrantedAuthority> authorities = oAuth2User.getAuthorities();

        // JWT 생성을 위한 인증 객체 생성 (Subject를 이메일로 설정하기 위함)
        Authentication auth = new UsernamePasswordAuthenticationToken(email, null, authorities);

        String accessToken = jwtTokenProvider.createAccessToken(auth);
        String refreshToken = jwtTokenProvider.createRefreshToken(auth);

        // 리프레시 토큰 저장
        RefreshToken token = refreshTokenRepository.findByUserName(email)
                .map(t -> {
                    t.updateToken(refreshToken);
                    return t;
                })
                .orElse(RefreshToken.builder()
                        .userName(email)
                        .token(refreshToken)
                        .build());

        refreshTokenRepository.save(token);

        String targetUrl = UriComponentsBuilder.fromUriString("http://localhost:3000/oauth2/redirect")
                .queryParam("accessToken", accessToken)
                .queryParam("refreshToken", refreshToken)
                .build().toUriString();

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}
