package com.AISA.AISA.kisStock.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class TaxonomyDto {
    private String code;
    private String name;
    private List<SubIndustryDto> subIndustries;

    @Getter
    @Builder
    public static class SubIndustryDto {
        private String code;
        private String name;
    }
}
