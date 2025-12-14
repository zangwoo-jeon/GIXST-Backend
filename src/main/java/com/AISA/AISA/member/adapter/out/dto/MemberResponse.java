package com.AISA.AISA.member.adapter.out.dto;

import com.AISA.AISA.member.adapter.in.Member;
import com.AISA.AISA.member.domain.MembershipType;
import lombok.Getter;

import java.util.UUID;

@Getter
public class MemberResponse {
    private final UUID memberId;
    private final String displayName;
    private final String userName;
    private final String provider;
    private final MembershipType membershipType;

    public MemberResponse(Member member) {
        this.memberId = member.getMemberId();
        this.displayName = member.getDisplayName();
        this.userName = member.getUserName();
        this.provider = member.getProvider();
        this.membershipType = member.getMembershipType();
    }
}
