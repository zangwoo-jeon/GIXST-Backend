package com.AISA.AISA.global.oauth.service;

import com.AISA.AISA.global.oauth.OAuthAttributes;
import com.AISA.AISA.member.adapter.in.Member;
import com.AISA.AISA.member.adapter.in.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

        private final MemberRepository memberRepository;

        @Override
        public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
                log.info("CustomOAuth2UserService.loadUser called");
                try {
                        OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate = new DefaultOAuth2UserService();
                        OAuth2User oAuth2User = delegate.loadUser(userRequest);

                        String registrationId = userRequest.getClientRegistration().getRegistrationId();
                        log.info("Registration ID: {}", registrationId);

                        String userNameAttributeName = userRequest.getClientRegistration().getProviderDetails()
                                        .getUserInfoEndpoint().getUserNameAttributeName();

                        OAuthAttributes attributes = OAuthAttributes.of(registrationId, userNameAttributeName,
                                        oAuth2User.getAttributes());

                        log.info("Attributes extracted. Email: {}, Name: {}, ProviderId: {}", attributes.getEmail(),
                                        attributes.getName(), attributes.getProviderId());

                        Member member = saveOrUpdate(attributes);
                        log.info("Member saved/updated successfully. MemberId: {}", member.getMemberId());

                        Map<String, Object> attributesMap = new HashMap<>(attributes.getAttributes());
                        attributesMap.put("email", attributes.getEmail());
                        attributesMap.put("userName", member.getUserName()); // SuccessHandler에서 사용

                        return new DefaultOAuth2User(
                                        Collections.singleton(
                                                        new SimpleGrantedAuthority(member.getMembershipType().name())),
                                        attributesMap,
                                        attributes.getNameAttributeKey());
                } catch (Exception e) {
                        log.error("Error in CustomOAuth2UserService.loadUser", e);
                        throw e;
                }
        }

        private Member saveOrUpdate(OAuthAttributes attributes) {
                String email = attributes.getEmail();
                log.info("saveOrUpdate called. Email: {}", email);

                // 1. 이메일이 존재하면 이메일로 사용자 조회
                if (email != null && !email.isBlank()) {
                        Member memberByEmail = memberRepository.findByEmail(email)
                                        .map(entity -> entity.update(attributes.getName(), email))
                                        .orElse(null);

                        if (memberByEmail != null) {
                                log.info("Found existing member by email. Updating...");
                                // 이미 존재하는 이메일이면 해당 계정 정보를 업데이트하여 반환
                                return memberRepository.save(memberByEmail);
                        }
                }

                // 2. Provider ID로 조회 (이메일이 없거나, 이메일로 찾지 못한 경우)
                Member member = memberRepository
                                .findByProviderAndProviderId(attributes.getProvider(), attributes.getProviderId())
                                .map(entity -> entity.update(attributes.getName(), email))
                                .orElseGet(() -> {
                                        log.info("Member not found by email or provider. Creating new member...");
                                        // 3. 존재하지 않는 경우 신규 회원 생성
                                        String uniqueNickname = attributes.getName();
                                        while (memberRepository.existsByDisplayName(uniqueNickname)) {
                                                uniqueNickname = attributes.getName() + "_"
                                                                + UUID.randomUUID().toString().substring(0, 5);
                                        }
                                        log.info("Unique nickname generated: {}", uniqueNickname);

                                        String generatedUserName = (email != null && !email.isBlank()) ? email
                                                        : attributes.getProvider() + "_" + attributes.getProviderId();
                                        String uniqueUserName = generatedUserName;

                                        while (memberRepository.existsByUserName(uniqueUserName)) {
                                                uniqueUserName = generatedUserName + "_"
                                                                + UUID.randomUUID().toString().substring(0, 5);
                                        }
                                        log.info("Unique username generated: {}", uniqueUserName);

                                        return Member.builder()
                                                        .userName(uniqueUserName)
                                                        .displayName(uniqueNickname)
                                                        .email(email)
                                                        .password(UUID.randomUUID().toString()) // 임시 비밀번호
                                                        .provider(attributes.getProvider())
                                                        .providerId(attributes.getProviderId())
                                                        .build();
                                });

                log.info("Saving member...");
                return memberRepository.save(member);
        }
}
