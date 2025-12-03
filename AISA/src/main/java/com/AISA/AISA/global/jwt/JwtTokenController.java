package com.AISA.AISA.global.jwt;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.global.jwt.dto.TokenRequestDto;
import com.AISA.AISA.global.jwt.dto.TokenResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("/api/auth")
@RestController
@RequiredArgsConstructor
@Tag(name = "토큰 API", description = "토큰 관련 API")
public class JwtTokenController {

    private final JwtTokenService jwtTokenService;

    @PostMapping("/reissue")
    @Operation(summary = "토큰 재발급", description = "Refresh Token을 이용하여 Access Token을 재발급받습니다.")
    public ResponseEntity<SuccessResponse<TokenResponseDto>> reissue(
            @RequestBody TokenRequestDto request) {
        TokenResponseDto token = jwtTokenService.reissue(request);
        return ResponseEntity.ok(new SuccessResponse<>(true, "토큰 재발급 성공", token));
    }
}
