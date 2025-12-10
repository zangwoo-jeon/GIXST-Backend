package com.AISA.AISA.member.adapter.in;

import com.AISA.AISA.global.jwt.dto.TokenResponseDto;
import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.member.adapter.in.dto.*;
import com.AISA.AISA.member.adapter.out.dto.MemberResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RequestMapping("/api/auth")
@RestController
@RequiredArgsConstructor
@Tag(name = "멤버 API", description = "멤버 관련 API")
public class MemberController {

    private final MemberService memberService;

    @PostMapping("/register")
    @Operation(summary = "회원가입", description = "입력한 유저 정보를 바탕으로 회원가입을 진행합니다.")
    public ResponseEntity<SuccessResponse<Member>> signup(
            @RequestBody MemberSignupRequest request) throws Exception {

        Member signUpMember = memberService.signup(request);
        return ResponseEntity.ok(new SuccessResponse<>(true, "회원가입 성공", signUpMember));
    }

    @PostMapping("/login")
    @Operation(summary = "로그인", description = "로그인을 진행하고 토큰을 발급받습니다.")
    public ResponseEntity<SuccessResponse<TokenResponseDto>> login(
            @RequestBody LoginRequestDto request) {
        TokenResponseDto token = memberService.login(request);
        return ResponseEntity.ok(new SuccessResponse<>(true, "로그인 성공", token));
    }

    @GetMapping("/check-username")
    @Operation(summary = "아이디 중복 확인", description = "아이디 중복 여부를 확인합니다.")
    public ResponseEntity<SuccessResponse<Void>> checkUserName(@RequestParam String userName) {
        memberService.checkUserNameDuplicate(userName);
        return ResponseEntity.ok(new SuccessResponse<>(true, "사용 가능한 아이디입니다.", null));
    }

    @GetMapping("/check-displayname")
    @Operation(summary = "닉네임 중복 확인", description = "닉네임 중복 여부를 확인합니다.")
    public ResponseEntity<SuccessResponse<Void>> checkDisplayName(@RequestParam String displayName) {
        memberService.checkDisplayNameDuplicate(displayName);
        return ResponseEntity.ok(new SuccessResponse<>(true, "사용 가능한 닉네임입니다.", null));
    }

    @GetMapping("/members")
    @Operation(summary = "전체 회원 조회", description = "모든 회원 정보를 조회합니다.")
    public ResponseEntity<SuccessResponse<List<MemberResponse>>> getAllMembers() {
        List<MemberResponse> members = memberService.findAllMember();
        return ResponseEntity.ok(new SuccessResponse<>(true, "전체 회원 조회 성공", members));
    }

    @GetMapping("/members/{memberId}")
    @Operation(summary = "특정 회원 조회", description = "특정 회원 정보를 조회합니다.")
    public ResponseEntity<SuccessResponse<MemberResponse>> getMemberById(
            @PathVariable UUID memberId) {
        MemberResponse member = memberService.findMemberById(memberId);
        return ResponseEntity.ok(new SuccessResponse<>(true, "회원 조회 성공", member));
    }

    @DeleteMapping("/members/{memberId}")
    @Operation(summary = "회원 삭제", description = "특정 회원을 삭제합니다.")
    public ResponseEntity<SuccessResponse<Void>> deleteMember(
            @PathVariable UUID memberId) {
        memberService.deleteMemberById(memberId);
        return ResponseEntity.ok(new SuccessResponse<>(true, "회원 삭제 성공", null));
    }

    @PatchMapping("/{memberId}/password")
    @Operation(summary = "비밀번호 변경", description = "특정 회원의 비밀번호를 변경합니다.")
    public ResponseEntity<SuccessResponse<Void>> changePassword(
            @PathVariable UUID memberId,
            @RequestBody PasswordChangeRequest request) {
        memberService.changePassword(memberId, request);
        return ResponseEntity.ok(new SuccessResponse<>(true, "비밀번호 변경 성공", null));
    }

}
