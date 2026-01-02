package com.AISA.AISA.kisStock.kisController;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisStock.kisService.Auth.KisAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/kis/auth")
@RequiredArgsConstructor
@Tag(name = "KIS 인증 API", description = "KIS API 인증 및 토큰 관리")
public class KisAuthController {

    private final KisAuthService kisAuthService;

    @PostMapping("/refresh")
    @Operation(summary = "Access Token 수동 갱신", description = "KIS Access Token을 수동으로 즉시 갱신합니다.")
    public ResponseEntity<SuccessResponse<String>> refreshAccessToken() {
        String newToken = kisAuthService.refreshAccessToken();
        return ResponseEntity.ok(new SuccessResponse<>(true, "Access Token 갱신 성공", newToken));
    }
}
