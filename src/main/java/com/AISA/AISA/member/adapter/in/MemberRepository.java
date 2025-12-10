package com.AISA.AISA.member.adapter.in;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MemberRepository extends JpaRepository<Member, UUID> {

    Optional<Member> findByUserName(String userName);

    Optional<Member> findByDisplayName(String displayName);

    boolean existsByUserName(String userName);

    boolean existsByDisplayName(String displayName);
}
