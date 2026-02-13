package com.AISA.AISA.kisStock.dto.StockPrice;

import com.AISA.AISA.kisStock.Entity.stock.EtfConstituent;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class EtfConstituentResponseDto {
    private String componentName;
    private String componentSymbol;
    private BigDecimal weight;

    public static EtfConstituentResponseDto from(EtfConstituent entity) {
        return EtfConstituentResponseDto.builder()
                .componentName(entity.getConstituent() != null ? entity.getConstituent().getStockName()
                        : entity.getComponentName())
                .componentSymbol(entity.getComponentSymbol())
                .weight(entity.getWeight())
                .build();
    }
}
