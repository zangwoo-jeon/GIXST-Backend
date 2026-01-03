package com.AISA.AISA.global.oauth.handler;

import com.AISA.AISA.global.jwt.JwtTokenProvider;
import com.AISA.AISA.global.jwt.RefreshToken;
import com.AISA.AISA.global.jwt.RefreshTokenRepository;
import com.AISA.AISA.global.oauth.repository.RedisOAuth2AuthorizationRequestRepository;
import com.AISA.AISA.global.util.CookieUtils;
import com.AISA.AISA.member.adapter.in.Member;
import com.AISA.AISA.member.adapter.in.MemberRepository;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
@Slf4j
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

        private final JwtTokenProvider jwtTokenProvider;
        private final MemberRepository memberRepository;
        private final RefreshTokenRepository refreshTokenRepository;
        private final RedisOAuth2AuthorizationRequestRepository redisOAuth2AuthorizationRequestRepository;

        @Override
        public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                        Authentication authentication) throws IOException, ServletException {
                OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
                // CustomOAuth2UserService에서 넣어준 userName 사용 (가장 확실한 식별자)
                String userName = (String) oAuth2User.getAttributes().get("userName");

                Member member = memberRepository.findByUserName(userName)
                                .orElseThrow(() -> new RuntimeException("회원 정보를 찾을 수 없습니다."));

                Authentication auth = new UsernamePasswordAuthenticationToken(member.getUserName(), null,
                                authentication.getAuthorities());

                String accessToken = jwtTokenProvider.createAccessToken(auth);
                String refreshToken = jwtTokenProvider.createRefreshToken(auth);

                // 리프레시 토큰 저장
                RefreshToken token = refreshTokenRepository.findByUserName(member.getUserName())
                                .map(t -> {
                                        t.updateToken(refreshToken);
                                        return t;
                                })
                                .orElse(RefreshToken.builder()
                                                .userName(member.getUserName())
                                                .token(refreshToken)
                                                .build());

                refreshTokenRepository.save(token);

                String targetUrl = CookieUtils.getCookie(request, "redirect_uri")
                                .map(Cookie::getValue)
                                .orElse("https://gixst.vercel.app/oauth2/redirect");

                targetUrl = UriComponentsBuilder.fromUriString(targetUrl)
                                .queryParam("accessToken", accessToken)
                                .queryParam("refreshToken", refreshToken)
                                .build().toUriString();

                redisOAuth2AuthorizationRequestRepository.removeAuthorizationRequestCookies(request, response);
                getRedirectStrategy().sendRedirect(request, response, targetUrl);
        }
}
