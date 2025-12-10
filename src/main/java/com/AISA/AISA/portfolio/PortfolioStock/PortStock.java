package com.AISA.AISA.portfolio.PortfolioStock;

import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.portfolio.PortfolioGroup.Portfolio;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Getter
@Table(name = "PortStock")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PortStock {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private java.util.UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "port_id", nullable = false)
    private Portfolio portfolio;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id", nullable = false)
    private Stock stock;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "average_price", nullable = false)
    private BigDecimal averagePrice;

    @Column(nullable = false)
    private Integer sequence;

    public PortStock(Portfolio portfolio, Stock stock, Integer quantity, BigDecimal averagePrice, Integer sequence) {
        this.portfolio = portfolio;
        this.stock = stock;
        this.quantity = quantity;
        this.averagePrice = averagePrice;
        this.sequence = sequence;
    }

    public void updateQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public void updateAveragePrice(BigDecimal averagePrice) {
        this.averagePrice = averagePrice;
    }

    public void updateSequence(Integer sequence) {
        this.sequence = sequence;
    }
}
