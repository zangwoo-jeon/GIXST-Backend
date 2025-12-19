package com.AISA.AISA.portfolio.PortfolioGroup;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.AISA.AISA.portfolio.PortfolioStock.PortStock;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "portfolio")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Portfolio {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID portId;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Column(name = "port_name", nullable = false)
    private String portName;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "main_portfolio")
    private boolean mainPort = false;

    @OneToMany(mappedBy = "portfolio", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PortStock> portStocks = new ArrayList<>();

    public Portfolio(UUID memberId, String portName) {
        this.memberId = memberId;
        this.portName = portName;
    }

    public void changeName(String newPortName) {
        this.portName = newPortName;
    }

    public void designateAsMain() {
        this.mainPort = true;
    }

    public void unDesignateAsMain() {
        this.mainPort = false;
    }
}
