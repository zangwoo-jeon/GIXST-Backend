package com.AISA.AISA.global.jwt;

import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.global.jwt.dto.TokenRequestDto;
import com.AISA.AISA.global.jwt.dto.TokenResponseDto;
import com.AISA.AISA.global.jwt.exception.JwtErrorCode;
import io.jsonwebtoken.ExpiredJwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JwtTokenService {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional
    public TokenResponseDto reissue(TokenRequestDto request) {
        // 1. Refresh Token 검증 (만료 여부 확인)
        try {
            jwtTokenProvider.validateTokenThrows(request.getRefreshToken());
        } catch (ExpiredJwtException e) {
            throw new BusinessException(JwtErrorCode.REFRESH_TOKEN_EXPIRED);
        } catch (Exception e) {
            throw new BusinessException(JwtErrorCode.INVALID_TOKEN);
        }

        // 2. Access Token 에서 User Name 가져오기 (만료된 토큰이어도 클레임은 가져올 수 있어야 함)
        Authentication authentication = jwtTokenProvider.getAuthentication(request.getAccessToken());

        // 3. DB에서 Refresh Token 조회 및 검증
        RefreshToken refreshToken = refreshTokenRepository.findByUserName(authentication.getName())
                .orElseThrow(() -> new BusinessException(JwtErrorCode.INVALID_TOKEN)); // 로그아웃 등으로 삭제된 경우

        if (!refreshToken.getToken().equals(request.getRefreshToken())) {
            // 토큰이 일치하지 않음 -> 탈취 의심 -> 해당 유저의 모든 토큰 무효화 (삭제)
            refreshTokenRepository.delete(refreshToken);
            throw new BusinessException(JwtErrorCode.INVALID_TOKEN);
        }

        // 4. 새로운 토큰 생성
        String newAccessToken = jwtTokenProvider.createAccessToken(authentication);
        String newRefreshToken = jwtTokenProvider.createRefreshToken(authentication);

        // 5. Refresh Token Rotation (DB 업데이트)
        refreshToken.updateToken(newRefreshToken);

        return TokenResponseDto.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .grantType("Bearer")
                .build();
    }
}
