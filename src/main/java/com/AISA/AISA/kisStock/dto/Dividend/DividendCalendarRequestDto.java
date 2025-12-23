package com.AISA.AISA.kisStock.dto.Dividend;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
public class DividendCalendarRequestDto {
    private int year;
    private int month;
    private UUID portId;
}
