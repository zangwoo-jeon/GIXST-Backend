package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.analysis.service.GeminiService;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.enums.Industry;
import com.AISA.AISA.kisStock.enums.SubIndustry;
import com.AISA.AISA.kisStock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Arrays;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndustryCategorizationService {

    private final StockRepository stockRepository;
    private final GeminiService geminiService;

    @Transactional
    public void categorizeStock(String stockCode) {
        Stock stock = stockRepository.findByStockCode(stockCode)
                .orElseThrow(() -> new IllegalArgumentException("Stock not found: " + stockCode));

        // Lazy Loading: Skip if already categorized
        if (stock.getIndustry() != null && stock.getIndustry() != Industry.UNKNOWN) {
            log.info("Stock {} already categorized. Skipping AI call.", stockCode);
            return;
        }

        try {
            // 1. Construct Prompt
            String prompt = String.format("""
                    Classify the company '%s' (Stock Code: %s) into one of the following Industries and SubIndustries.

                    **Industry List**:
                    %s

                    **SubIndustry List**:
                    %s

                    **Format**:
                    INDUSTRY: [Industry Enum Name]
                    SUB_INDUSTRY: [SubIndustry Enum Name]

                    **Constraint**:
                    - Use EXACT Enum names provided above.
                    - If unclear, use UNKNOWN.
                    - Do not add any explanation, just the two lines.
                    """,
                    stock.getStockName(),
                    stockCode,
                    getIndustryListString(),
                    getSubIndustryListString());

            // 2. Call AI
            String response = geminiService.generateAdvice(prompt);

            // 3. Parse Result
            Industry industry = Industry.UNKNOWN;
            SubIndustry subIndustry = SubIndustry.UNKNOWN;

            String[] lines = response.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("INDUSTRY:")) {
                    String value = line.substring("INDUSTRY:".length()).trim();
                    try {
                        industry = Industry.valueOf(value);
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid Industry returned by AI: {}", value);
                    }
                } else if (line.startsWith("SUB_INDUSTRY:")) {
                    String value = line.substring("SUB_INDUSTRY:".length()).trim();
                    try {
                        subIndustry = SubIndustry.valueOf(value);
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid SubIndustry returned by AI: {}", value);
                    }
                }
            }

            // 4. Update Stock Entity
            stock.updateIndustry(industry, subIndustry);
            log.info("Categorized {} -> {} / {}", stock.getStockName(), industry, subIndustry);

        } catch (Exception e) {
            log.error("Failed to categorize stock {}: {}", stock.getStockName(), e.getMessage());
        }
    }

    private String getIndustryListString() {
        return Arrays.stream(Industry.values())
                .map(Enum::name)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    private String getSubIndustryListString() {
        return Arrays.stream(SubIndustry.values())
                .map(Enum::name)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }
}
