package com.AISA.AISA.analysis.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
@Table(name = "stock_static_analysis", indexes = {
        @Index(name = "idx_static_stock_code", columnList = "stockCode")
})
public class StockStaticAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String stockCode;

    @Column(columnDefinition = "TEXT")
    private String content; // 기업 개요, 미래 성장 동력, 리스크 요인 등 (정적 데이터)

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdDate;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime lastModifiedDate;

    // 만료 체크 (기본 1년 = 8760시간)
    public boolean isExpired(int hours) {
        return lastModifiedDate.plusHours(hours).isBefore(LocalDateTime.now());
    }

    public void updateContent(String content) {
        this.content = content;
        this.lastModifiedDate = LocalDateTime.now();
    }
}
