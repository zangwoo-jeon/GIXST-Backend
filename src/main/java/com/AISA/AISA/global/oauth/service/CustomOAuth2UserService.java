package com.AISA.AISA.global.oauth.service;

import com.AISA.AISA.global.oauth.OAuthAttributes;
import com.AISA.AISA.member.adapter.in.Member;
import com.AISA.AISA.member.adapter.in.MemberRepository;
import lombok.RequiredArgsConstructor;
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

@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

        private final MemberRepository memberRepository;

        @Override
        public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
                OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate = new DefaultOAuth2UserService();
                OAuth2User oAuth2User = delegate.loadUser(userRequest);

                String registrationId = userRequest.getClientRegistration().getRegistrationId();
                String userNameAttributeName = userRequest.getClientRegistration().getProviderDetails()
                                .getUserInfoEndpoint().getUserNameAttributeName();

                OAuthAttributes attributes = OAuthAttributes.of(registrationId, userNameAttributeName,
                                oAuth2User.getAttributes());

                Member member = saveOrUpdate(attributes);

                Map<String, Object> attributesMap = new HashMap<>(attributes.getAttributes());
                attributesMap.put("email", attributes.getEmail());
                attributesMap.put("userName", member.getUserName()); // SuccessHandler에서 사용

                return new DefaultOAuth2User(
                                Collections.singleton(new SimpleGrantedAuthority(member.getMembershipType().name())),
                                attributesMap,
                                attributes.getNameAttributeKey());
        }

        private Member saveOrUpdate(OAuthAttributes attributes) {
                // 1. 이메일로 찾기
                if (attributes.getEmail() != null && !attributes.getEmail().isBlank()) {
                        Member memberByEmail = memberRepository.findByEmail(attributes.getEmail())
                                        .map(entity -> entity.update(attributes.getName(), attributes.getEmail()))
                                        .orElse(null);

                        if (memberByEmail != null) {
                                return memberRepository.save(memberByEmail);
                        }
                }

                // 2. Provider ID로 찾기 (이메일 변경/삭제 되었거나 이메일 없는 경우)
                Member memberByProvider = memberRepository
                                .findByProviderAndProviderId(attributes.getProvider(), attributes.getProviderId())
                                .map(entity -> entity.update(attributes.getName(), attributes.getEmail()))
                                .orElse(attributes.toEntity()); // 3. 없으면 생성

                return memberRepository.save(memberByProvider);
        }
}
