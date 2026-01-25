package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.analysis.service.GeminiService;
import com.AISA.AISA.kisStock.Entity.stock.Industry;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.Entity.stock.StockIndustry;
import com.AISA.AISA.kisStock.Entity.stock.SubIndustry;
import com.AISA.AISA.kisStock.dto.TaxonomyDto;
import com.AISA.AISA.kisStock.repository.IndustryRepository;
import com.AISA.AISA.kisStock.repository.StockRepository;
import com.AISA.AISA.kisStock.repository.SubIndustryRepository;
import com.AISA.AISA.global.exception.BusinessException;
import com.AISA.AISA.kisStock.exception.KisApiErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndustryCategorizationService {

        private final StockRepository stockRepository;
        private final IndustryRepository industryRepository;
        private final SubIndustryRepository subIndustryRepository;
        private final GeminiService geminiService;

        // Seed Data Map: Industry Code -> List of SubIndustry Map (Code, Name)
        // This is a temporary hardcoded map for initialization. Ideally could be in a
        // config file.
        private static final Map<String, List<String[]>> SEED_DATA = new LinkedHashMap<>();

        static {
                // Industry: Code, Name
                // SubIndustry: Code, Name
                // Structure for filler:
                // SEED_DATA.put("INDUSTRY_CODE|Industry Name", List.of(new String[]{"SUB_CODE",
                // "Sub Name"}, ...));

                // 1. IT (Expanded)
                SEED_DATA.put("IT|IT", Arrays.<String[]>asList(
                                new String[] { "SEMICONDUCTOR", "반도체 (소자/설계)" }, // Name Refined
                                new String[] { "SEMICONDUCTOR_EQUIP_MAT", "반도체 장비/소재" },
                                new String[] { "DISPLAY", "디스플레이" },
                                new String[] { "TECH_HARDWARE", "IT 하드웨어 / 장비" }, // Name Refined
                                new String[] { "SOFTWARE_PLATFORM", "소프트웨어 / 플랫폼" },
                                new String[] { "AI_ROBOTICS", "AI / 로봇" },
                                new String[] { "IT_SERVICE_SI", "IT서비스 / SI" },
                                new String[] { "GAME", "게임" }));

                // 2. COMMUNICATION SERVICES (Merged Telecom here as per Global Standard)
                SEED_DATA.put("COMMUNICATION_SERVICES|커뮤니케이션 서비스", Arrays.<String[]>asList(
                                new String[] { "TELECOM", "통신 서비스" },
                                new String[] { "ENTERTAINMENT", "엔터테인먼트 (연예/기획)" },
                                new String[] { "MEDIA_CONTENT", "미디어 / 콘텐츠 제작" },
                                new String[] { "ADVERTISING", "광고" }));

                // 3. UTILITIES / INFRA (Reduced)
                SEED_DATA.put("UTILITIES_INFRA|유틸리티 / 인프라", Arrays.<String[]>asList(
                                new String[] { "ELECTRICITY_GAS", "전력 / 가스" },
                                new String[] { "INFRA_OPS", "인프라 운영" }));

                // 4. HEALTHCARE
                SEED_DATA.put("HEALTHCARE|헬스케어", Arrays.<String[]>asList(
                                new String[] { "PHARMA_BIO", "제약 / 바이오" },
                                new String[] { "MEDICAL_DEVICE", "의료기기" },
                                new String[] { "HEALTHCARE_SERVICE", "헬스케어 서비스" }));

                // 5. CONSUMER DISCRETIONARY (Added Retail here)
                SEED_DATA.put("CONSUMER_DISCRETIONARY|경기소비재", Arrays.<String[]>asList(
                                new String[] { "AUTO", "자동차 (완성차)" },
                                new String[] { "AUTO_COMPONENT", "자동차 부품" },
                                new String[] { "RETAIL_DEPARTMENT", "유통 (백화점/대형마트)" },
                                new String[] { "FASHION_APPAREL", "패션 / 의류" },
                                new String[] { "HOME_APPLIANCE", "가전" },
                                new String[] { "LEISURE_TRAVEL", "레저 / 여행" },
                                new String[] { "EDUCATION", "교육" }));

                // 6. CONSUMER STAPLES
                SEED_DATA.put("CONSUMER_STAPLES|필수소비재", Arrays.<String[]>asList(
                                new String[] { "FOOD_BEVERAGE", "음식료" },
                                new String[] { "HOUSEHOLD_PRODUCT", "생활용품" },
                                new String[] { "COSMETICS_BEAUTY", "화장품 / 뷰티" }, // Renamed and Expanded
                                new String[] { "CONVENIENCE_STORE", "편의점 / 식자재유통" }));

                // 7. MATERIALS (Expanded)
                SEED_DATA.put("MATERIALS|소재", Arrays.<String[]>asList(
                                new String[] { "SECONDARY_BATTERY_MAT", "2차전지 소재 (양극재 등)" },
                                new String[] { "CHEMICAL", "화학" },
                                new String[] { "STEEL", "철강" },
                                new String[] { "NON_FERROUS_METAL", "비철금속" },
                                new String[] { "PAPER_WOOD", "제지 / 목재" },
                                new String[] { "GLASS_CEMENT", "건자재 (유리/시멘트)" }));

                // 8. INDUSTRIALS (Expanded & Power Grid Added)
                SEED_DATA.put("INDUSTRIALS|산업재", Arrays.<String[]>asList(
                                new String[] { "SECONDARY_BATTERY_CELL", "2차전지 (배터리 셀)" },
                                new String[] { "SHIPBUILDING", "조선" },
                                new String[] { "DEFENSE_AEROSPACE", "방산 / 우주항공" },
                                new String[] { "ELECTRICAL_EQUIP", "전력기기 / 전선" }, // Added
                                new String[] { "MACHINERY", "기계 / 장비" }, // Name Refined
                                new String[] { "CONSTRUCTION", "건설" },
                                new String[] { "LOGISTICS_TRANSPORT", "물류 / 운송" }));

                // 9. ENERGY (Added Renewable)
                SEED_DATA.put("ENERGY|에너지", Arrays.<String[]>asList(
                                new String[] { "ENERGY_REFINERY", "정유 / 석유화학" },
                                new String[] { "RENEWABLE_ENERGY", "신재생에너지 (태양광/풍력/수소)" }));

                // 10. FINANCIALS (Added Fintech)
                SEED_DATA.put("FINANCIALS|금융", Arrays.<String[]>asList(
                                new String[] { "BANK", "은행" },
                                new String[] { "SECURITIES", "증권" },
                                new String[] { "INSURANCE", "보험" },
                                new String[] { "FINTECH_PAYMENT", "핀테크 / 결제" }));

                // 11. ETC (Added SPAC)
                SEED_DATA.put("ETC|기타", Arrays.<String[]>asList(
                                new String[] { "HOLDING", "지주회사" },
                                new String[] { "SPAC", "기업인수목적회사 (SPAC)" }));

                // 12. REITs / ETFs
                SEED_DATA.put("REITS|리츠", Arrays.<String[]>asList(
                                new String[] { "REITS", "리츠" }));

                SEED_DATA.put("ETF|ETF", Arrays.<String[]>asList(
                                new String[] { "ETF", "ETF (상장지수펀드)" },
                                new String[] { "ETN", "ETN (상장지수증권)" }));
        }

        @PostConstruct
        @Transactional
        public void initData() {
                if (industryRepository.count() > 0)
                        return;

                log.info("Initializing Industry and SubIndustry Data...");

                for (Map.Entry<String, List<String[]>> entry : SEED_DATA.entrySet()) {
                        String[] indParts = entry.getKey().split("\\|");
                        String indCode = indParts[0];
                        String indName = indParts[1];

                        Industry industry = new Industry(indCode, indName, indName); // Desc = Name slightly redundant
                                                                                     // but fine
                        industryRepository.save(industry);

                        for (String[] subParts : entry.getValue()) {
                                String subCode = subParts[0];
                                String subName = subParts[1];

                                SubIndustry subIndustry = new SubIndustry(subCode, subName, industry);
                                subIndustryRepository.save(subIndustry);
                        }
                }
                log.info("Industry Initialization Completed.");
        }

        @Transactional
        public void categorizeStock(String stockCode) {
                Stock stock = stockRepository.findByStockCode(stockCode)
                                .orElseThrow(() -> new BusinessException(KisApiErrorCode.STOCK_NOT_FOUND));

                if (stock.getStockType() != Stock.StockType.DOMESTIC) {
                        throw new BusinessException(KisApiErrorCode.INVALID_STOCK_TYPE);
                }

                // Skip if already has industries
                if (!stock.getStockIndustries().isEmpty()) {
                        // Check if unknown? For now just skip if any exist.
                        return;
                }

                try {
                        // 1. Construct Prompt
                        String prompt = buildPrompt(stock.getStockName(), stockCode);

                        // 2. Call AI
                        String response = geminiService.generateAdvice(prompt);

                        // 3. Parse and Save
                        parseAndSaveCategories(stock, response);

                } catch (Exception e) {
                        log.error("Failed to categorize stock {}: {}", stock.getStockName(), e.getMessage());
                }
        }

        private String buildPrompt(String stockName, String stockCode) {
                // Collect all available SubIndustries for the prompt
                // Ideally cache this string
                String subIndustryList = subIndustryRepository.findAll().stream()
                                .map(s -> String.format("- %s (%s) [Parent: %s]", s.getCode(), s.getName(),
                                                s.getIndustry().getCode()))
                                .collect(Collectors.joining("\n"));

                return String.format("""
                                Classify the company '%s' (Stock Code: %s) into relevant SubIndustries.
                                A company can have multiple SubIndustries if it operates in multiple sectors.
                                Select strictly from the following list:

                                %s

                                **Format**:
                                SUB_INDUSTRY: [SubIndustry Code]
                                SUB_INDUSTRY: [SubIndustry Code]
                                ...

                                **Constraint**:
                                - Use EXACT codes.
                                - List strictly 1 to 3 most relevant sub-industries.
                                - Order by relevance (Primary first).
                                - Minimal output, no explanation.
                                """, stockName, stockCode, subIndustryList);
        }

        private void parseAndSaveCategories(Stock stock, String response) {
                String[] lines = response.split("\n");
                boolean isPrimary = true;

                Set<String> processedCodes = new HashSet<>();

                for (String line : lines) {
                        line = line.trim();
                        if (line.startsWith("SUB_INDUSTRY:")) {
                                String code = line.substring("SUB_INDUSTRY:".length()).trim();

                                if (processedCodes.contains(code)) {
                                        continue; // Skip duplicates
                                }
                                processedCodes.add(code);

                                Optional<SubIndustry> subOpt = subIndustryRepository.findByCode(code);

                                if (subOpt.isPresent()) {
                                        SubIndustry sub = subOpt.get();
                                        StockIndustry link = new StockIndustry(stock, sub, isPrimary);
                                        stock.addIndustry(link);
                                        stockRepository.save(stock); // Cascade saves the link
                                        log.info("Linked {} -> {}", stock.getStockName(), sub.getName());
                                        isPrimary = false; // Subsequent are secondary
                                } else {
                                        log.warn("AI returned invalid SubIndustry Code: {}", code);
                                }
                        }
                }
        }

        @Transactional(readOnly = true)
        public List<TaxonomyDto> getTaxonomy() {
                return industryRepository.findAll().stream()
                                .map(ind -> TaxonomyDto.builder()
                                                .code(ind.getCode())
                                                .name(ind.getName())
                                                .subIndustries(ind.getSubIndustries().stream()
                                                                .map(sub -> TaxonomyDto.SubIndustryDto.builder()
                                                                                .code(sub.getCode())
                                                                                .name(sub.getName())
                                                                                .build())
                                                                .collect(Collectors.toList()))
                                                .build())
                                .collect(Collectors.toList());
        }
}
