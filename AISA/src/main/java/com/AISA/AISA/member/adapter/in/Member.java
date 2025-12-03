package com.AISA.AISA.member.adapter.in;

import com.AISA.AISA.member.domain.MembershipType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "member", uniqueConstraints = {
        @UniqueConstraint(name = "uk_member_user_name", columnNames = "user_name"),
        @UniqueConstraint(name = "uk_member_display_name", columnNames = "display_name")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID memberId;

    @Column(name = "display_name", nullable = false)
    private String displayName;
    @Column(name = "user_name", nullable = false)
    private String userName;
    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MembershipType membershipType;

    private LocalDateTime subscriptionStartDate;
    private LocalDateTime subscriptionEndDate;

    @Builder
    public Member(String userName, String displayName, String password) {
        this.userName = userName;
        this.displayName = displayName;
        this.password = password;
        this.membershipType = MembershipType.FREE;
    }

    public void changePassword(String newPassword) {
        this.password = newPassword;
    }

    public void upgradeMembership(MembershipType membershipType) {
        this.membershipType = membershipType;
        this.subscriptionStartDate = LocalDateTime.now();
        this.subscriptionEndDate = LocalDateTime.now().plusMonths(1);
    }
}
