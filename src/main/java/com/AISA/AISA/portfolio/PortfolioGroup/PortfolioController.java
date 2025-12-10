package com.AISA.AISA.portfolio.PortfolioGroup;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.portfolio.PortfolioGroup.dto.PortfolioCreateRequest;
import com.AISA.AISA.portfolio.PortfolioGroup.dto.PortfolioNameUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RequestMapping("/api/portfolio")
@RestController
@RequiredArgsConstructor
@Tag(name = "포트폴리오 API", description = "포트폴리오 관련 API")
public class PortfolioController {
    private final PortfolioService portfolioService;

    @GetMapping("/list")
    @Operation(summary = "포트폴리오 조회", description = "로그인한 사용자의 포트폴리오 목록을 조회합니다.")
    public ResponseEntity<SuccessResponse<List<Portfolio>>> getPortfolios(
            java.security.Principal principal) {
        List<Portfolio> portfolios = portfolioService.findPortfolios(principal.getName());
        return ResponseEntity.ok(new SuccessResponse<>(true, "포트폴리오 목록 조회 성공", portfolios));

    }

    @PostMapping("/create")
    @Operation(summary = "포트폴리오 생성", description = "포트폴리오를 생성합니다.")
    public ResponseEntity<SuccessResponse<Portfolio>> createPortfolio(
            java.security.Principal principal,
            @RequestBody PortfolioCreateRequest request) {
        Portfolio createdPortfolio = portfolioService.createPortfolio(principal.getName(), request);
        return ResponseEntity.ok(new SuccessResponse<>(true, "포트폴리오 생성 성공", createdPortfolio));
    }

    @DeleteMapping("/remove/{portId}")
    @Operation(summary = "포트폴리오 삭제", description = "특정 포트폴리오를 삭제합니다.")
    public ResponseEntity<SuccessResponse<Void>> removePortfolio(
            java.security.Principal principal, @PathVariable UUID portId) {
        portfolioService.deletePortfolio(principal.getName(), portId);
        return ResponseEntity.ok(new SuccessResponse<>(true, "포트폴리오 삭제 성공", null));
    }

    @PutMapping("/changeName/{portId}")
    @Operation(summary = "포트폴리오 이름 변경", description = "포트폴리오 이름을 변경합니다.")
    public ResponseEntity<SuccessResponse<Void>> changePortfolioName(
            java.security.Principal principal, @PathVariable UUID portId,
            @RequestBody PortfolioNameUpdateRequest request) {
        portfolioService.updatePortfolioName(principal.getName(), portId, request);
        return ResponseEntity.ok(new SuccessResponse<>(true, "포트폴리오 이름 변경 성공", null));
    }

    @PutMapping("/main/{portId}")
    @Operation(summary = "메인 포트폴리오 변경", description = "메인 포트폴리오 그룹을 변경합니다.")
    public ResponseEntity<SuccessResponse<Void>> changeMainPortfolio(
            java.security.Principal principal,
            @PathVariable UUID portId) {
        portfolioService.changeMainPortfolio(principal.getName(), portId);
        return ResponseEntity.ok(new SuccessResponse<>(true, "메인 포트폴리오 변경 성공", null));
    }

}
