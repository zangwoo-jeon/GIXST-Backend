package com.AISA.AISA.member.adapter.in;

import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.global.jwt.JwtTokenProvider;
import com.AISA.AISA.global.jwt.RefreshToken;
import com.AISA.AISA.global.jwt.RefreshTokenRepository;
import com.AISA.AISA.member.adapter.in.dto.LoginRequestDto;
import com.AISA.AISA.member.adapter.in.dto.MemberSignupRequest;
import com.AISA.AISA.member.adapter.in.dto.PasswordChangeRequest;
import com.AISA.AISA.member.adapter.in.dto.DisplayNameChangeRequest;
import com.AISA.AISA.member.adapter.in.dto.EmailChangeRequest;
import com.AISA.AISA.global.jwt.dto.TokenResponseDto;
import com.AISA.AISA.member.adapter.in.exception.MemberErrorCode;
import com.AISA.AISA.member.adapter.out.dto.MemberResponse;
import com.AISA.AISA.portfolio.PortfolioGroup.PortfolioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static java.util.Collections.singletonList;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PortfolioRepository portfolioRepository;

    @Transactional
    public Member signup(MemberSignupRequest request) {
        // ID와 비밀번호는 8자 이상이여야 한다.
        if (request.getUserName().length() < 8 || request.getPassword().length() < 8) {
            throw new BusinessException(MemberErrorCode.INVALID_CREDENTIALS_LENGTH);
        }

        // 비밀번호는 문자와 숫자를 모두 포함해야 한다.
        String password = request.getPassword();
        if (!password.matches(".*[a-zA-Z].*") || !password.matches(".*\\d.*")) {
            throw new BusinessException(MemberErrorCode.INVALID_PASSWORD_POLICY);
        }

        // 중복 체크
        checkUserNameDuplicate(request.getUserName());
        checkDisplayNameDuplicate(request.getDisplayName());

        Member newMember = Member.builder()
                .userName(request.getUserName())
                .displayName(request.getDisplayName())
                .password(passwordEncoder.encode(request.getPassword()))
                .provider("local")
                .build();
        return memberRepository.save(newMember);
    }

    public void checkUserNameDuplicate(String userName) {
        if (memberRepository.existsByUserName(userName)) {
            throw new BusinessException(MemberErrorCode.DUPLICATE_USERNAME);
        }
    }

    public void checkDisplayNameDuplicate(String displayName) {
        if (memberRepository.existsByDisplayName(displayName)) {
            throw new BusinessException(MemberErrorCode.DUPLICATE_DISPLAY_NAME);
        }
    }

    public void checkEmailDuplicate(String email) {
        if (memberRepository.existsByEmail(email)) {
            throw new BusinessException(MemberErrorCode.DUPLICATE_EMAIL);
        }
    }

    public MemberResponse findMemberById(UUID memberId) {

        return memberRepository.findById(memberId)
                .map(MemberResponse::new)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    public MemberResponse findMemberByUserName(String userName) {
        return memberRepository.findByUserName(userName)
                .map(MemberResponse::new)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    public List<MemberResponse> findAllMember() {
        return memberRepository.findAll().stream()
                .map(MemberResponse::new)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteMemberById(UUID memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));

        // 해당 멤버의 모든 포트폴리오 삭제
        portfolioRepository.deleteByMemberId(memberId);

        memberRepository.delete(member);
    }

    @Transactional
    public void changePassword(UUID memberId, PasswordChangeRequest request) {

        // 비밀번호 바꿀 회원 찾기
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));

        // 현재 비밀번호가 일치하는지 확인
        if (!passwordEncoder.matches(request.getCurrentPassword(), member.getPassword())) {
            throw new BusinessException(MemberErrorCode.INVALID_CURRENT_PASSWORD);
        }

        String newPassword = request.getNewPassword();

        // 현재 비밀번호가 8자 이상인지 확인
        if (newPassword.length() < 8) {
            throw new BusinessException(MemberErrorCode.INVALID_CREDENTIALS_LENGTH);
        }
        // 현재 비밀번호가 문자와 숫자를 모두 포함하는지 확인
        if (!newPassword.matches(".*[a-zA-Z].*") || !newPassword.matches(".*\\d.*")) {
            throw new BusinessException(MemberErrorCode.INVALID_PASSWORD_POLICY);
        }

        // 비밀번호 변경
        member.changePassword(passwordEncoder.encode(newPassword));
    }

    @Transactional
    public void changeDisplayName(UUID memberId, DisplayNameChangeRequest request) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));

        String newDisplayName = request.getNewDisplayName();

        // 닉네임 중복 체크
        if (!member.getDisplayName().equals(newDisplayName)) {
            checkDisplayNameDuplicate(newDisplayName);
        }

        member.changeDisplayName(newDisplayName);
    }

    @Transactional
    public void changeEmail(UUID memberId, EmailChangeRequest request) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));

        String newEmail = request.getNewEmail();

        // 이메일 유효성 검사 및 중복 체크 (null이 아니고 비어있지 않을 때만)
        if (newEmail != null && !newEmail.isBlank()) {
            // 본인의 이메일이 아닐 경우 중복 체크
            if (!newEmail.equals(member.getEmail())) {
                checkEmailDuplicate(newEmail);
            }
        }

        member.changeEmail(newEmail);
    }

    @Transactional
    public TokenResponseDto login(LoginRequestDto request) {
        // 1. 회원 확인
        Member member = memberRepository.findByUserName(request.getUserName())
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));

        // 2. 비밀번호 확인
        if (!passwordEncoder.matches(request.getPassword(), member.getPassword())) {
            throw new BusinessException(MemberErrorCode.INVALID_PASSWORD);
        }

        // 3. 토큰 생성
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                member.getUserName(), null, singletonList(
                        new SimpleGrantedAuthority("ROLE_USER")));

        String accessToken = jwtTokenProvider.createAccessToken(authentication);
        String refreshToken = jwtTokenProvider.createRefreshToken(authentication);

        // Refresh Token 저장
        refreshTokenRepository.save(RefreshToken.builder()
                .userName(member.getUserName())
                .token(refreshToken)
                .build());

        return TokenResponseDto.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .grantType("Bearer")
                .build();
    }

}
