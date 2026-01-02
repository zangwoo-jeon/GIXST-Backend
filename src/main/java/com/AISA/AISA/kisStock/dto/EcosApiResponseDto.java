package com.AISA.AISA.kisStock.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

@Getter
@Setter
@ToString
public class EcosApiResponseDto {
    @JsonProperty("StatisticSearch")
    private StatisticSearch statisticSearch;

    @Getter
    @Setter
    @ToString
    public static class StatisticSearch {
        @JsonProperty("list_total_count")
        private int listTotalCount;
        @JsonProperty("row")
        private List<Row> row;
    }

    @Getter
    @Setter
    @ToString
    public static class Row {
        @JsonProperty("STAT_CODE")
        private String statCode;
        @JsonProperty("STAT_NAME")
        private String statName;
        @JsonProperty("ITEM_CODE1")
        private String itemCode1;
        @JsonProperty("ITEM_NAME1")
        private String itemName1;
        @JsonProperty("TIME")
        private String time;
        @JsonProperty("DATA_VALUE")
        private String dataValue;
    }
}
