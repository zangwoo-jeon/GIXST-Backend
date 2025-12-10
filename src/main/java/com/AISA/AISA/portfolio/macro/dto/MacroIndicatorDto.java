package com.AISA.AISA.portfolio.macro.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MacroIndicatorDto {
    private String date;
    private String value;
}
