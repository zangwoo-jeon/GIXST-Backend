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

    @Column(name = "email", nullable = true, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MembershipType membershipType;

    private LocalDateTime subscriptionStartDate;
    private LocalDateTime subscriptionEndDate;

    @Column(name = "provider")
    private String provider;

    @Column(name = "provider_id")
    private String providerId;

    @Builder
    public Member(String userName, String displayName, String password, String email, String provider,
            String providerId) {
        this.userName = userName;
        this.displayName = displayName;
        this.password = password;
        this.email = email;
        this.membershipType = MembershipType.FREE;
        this.provider = provider;
        this.providerId = providerId;
    }

    public void changePassword(String newPassword) {
        this.password = newPassword;
    }

    public void changeDisplayName(String newDisplayName) {
        this.displayName = newDisplayName;
    }

    public void changeEmail(String newEmail) {
        this.email = newEmail;
    }

    public void upgradeMembership(MembershipType membershipType) {
        this.membershipType = membershipType;
        this.subscriptionStartDate = LocalDateTime.now();
        this.subscriptionEndDate = LocalDateTime.now().plusMonths(1);
    }

    public Member update(String displayName, String email) {
        this.displayName = displayName;
        this.email = email;
        return this;
    }
}
