package com.AISA.AISA.kisStock.dto.Index;

import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class BreadthHistoryDto {
    private LocalDate date;
    private long risingCount;
    private long fallingCount;
    private long totalCount;

    public double getBreadthIndex() {
        if (totalCount == 0)
            return 0.0;
        return (double) (risingCount - fallingCount) / totalCount * 100.0;
    }
}
