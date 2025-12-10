package com.AISA.AISA.global.jwt;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "refresh_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @Column(name = "user_name", nullable = false)
    private String userName;

    @Column(name = "token", nullable = false)
    private String token;

    @Builder
    public RefreshToken(String userName, String token) {
        this.userName = userName;
        this.token = token;
    }

    public void updateToken(String token) {
        this.token = token;
    }
}
