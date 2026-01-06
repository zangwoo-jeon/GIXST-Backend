package com.AISA.AISA.kisStock.Entity.stock;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Industry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code; // e.g., IT, HEALTHCARE (Enum name style)

    @Column(nullable = false)
    private String name; // e.g., IT, 헬스케어

    private String description;

    @OneToMany(mappedBy = "industry", cascade = CascadeType.ALL)
    private List<SubIndustry> subIndustries = new ArrayList<>();

    public Industry(String code, String name, String description) {
        this.code = code;
        this.name = name;
        this.description = description;
    }
}
