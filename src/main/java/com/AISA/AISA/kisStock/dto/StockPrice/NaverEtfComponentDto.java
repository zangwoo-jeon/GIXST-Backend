package com.AISA.AISA.kisStock.dto.StockPrice;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NaverEtfComponentDto {
    private String itemCode;
    private String componentIsinCode;
    private String componentItemCode;
    private String componentReutersCode;
    private String componentName;
    private String weight;
    private String referenceDate;
}
