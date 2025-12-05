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

                return new DefaultOAuth2User(
                                Collections.singleton(new SimpleGrantedAuthority(member.getMembershipType().name())),
                                attributesMap,
                                attributes.getNameAttributeKey());
        }

        private Member saveOrUpdate(OAuthAttributes attributes) {
                Member member = memberRepository.findByUserName(attributes.getEmail())
                                .orElse(attributes.toEntity());

                return memberRepository.save(member);
        }
}
