package com.AISA.AISA.kisStock.kisController;

import com.AISA.AISA.global.response.SuccessResponse;
import com.AISA.AISA.kisStock.Entity.stock.Stock;
import com.AISA.AISA.kisStock.dto.FinancialRank.BalanceSheetDto;
import com.AISA.AISA.kisStock.dto.FinancialRank.FinancialRatioRankDto;
import com.AISA.AISA.kisStock.dto.FinancialRank.FinancialStatementDto;
import com.AISA.AISA.kisStock.dto.FinancialRank.InvestmentMetricDto;
import com.AISA.AISA.kisStock.dto.IndustryResponseDto;
import com.AISA.AISA.kisStock.dto.InvestorTrend.InvestorTrendDto;
import com.AISA.AISA.kisStock.dto.InvestorTrend.StockInvestorDailyDto;
import com.AISA.AISA.kisStock.dto.StockPrice.StockPriceDto;
import com.AISA.AISA.kisStock.dto.StockSearchResponseDto;
import com.AISA.AISA.kisStock.dto.TaxonomyDto;
import com.AISA.AISA.kisStock.kisService.CompetitorAnalysisService;
import com.AISA.AISA.kisStock.kisService.IndustryCategorizationService;
import com.AISA.AISA.kisStock.kisService.KisInformationService;
import com.AISA.AISA.kisStock.kisService.KisStockService;
import com.AISA.AISA.kisStock.repository.StockRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/stocks/financial")
@RequiredArgsConstructor
@Tag(name = "주식 정보 API", description = "주식 재무/정보 관련 API")
public class KisInformationController {

        private final KisInformationService kisInformationService;
        private final KisStockService kisStockService;
        private final StockRepository stockRepository;
        private final IndustryCategorizationService industryCategorizationService; // Inject
        private final CompetitorAnalysisService competitorAnalysisService; // Inject

        @GetMapping("/balance-sheet/{stockCode}")
        @Operation(summary = "특정 종목 대차대조표 조회 (DB)", description = "특정 종목의 자산/부채/자본 정보를 DB에서 조회합니다. (0:년, 1:분기)")
        public ResponseEntity<SuccessResponse<List<BalanceSheetDto>>> getBalanceSheet(
                        @PathVariable String stockCode,
                        @RequestParam(required = false, defaultValue = "0") String divCode) {
                return ResponseEntity
                                .ok(new SuccessResponse<>(true, "대차대조표 조회 성공",
                                                kisInformationService.getBalanceSheet(stockCode, divCode)));
        }

        @PostMapping("/balance-sheet/refresh/{stockCode}")
        @Operation(summary = "특정 종목 대차대조표 갱신 (API -> DB)", description = "KIS API에서 최신 대차대조표 정보를 가져와 DB에 저장 후 반환합니다. (0:년, 1:분기)")
        public ResponseEntity<SuccessResponse<List<BalanceSheetDto>>> refreshBalanceSheet(
                        @PathVariable String stockCode,
                        @RequestParam(required = false, defaultValue = "0") String divCode) {
                return ResponseEntity
                                .ok(new SuccessResponse<>(true, "대차대조표 갱신 성공",
                                                kisInformationService.refreshBalanceSheet(stockCode, divCode)));
        }

        @PostMapping("/balance-sheet/init-all")
        @Operation(summary = "전체 주식 대차대조표 DB 초기화/갱신", description = "모든 종목의 대차대조표 정보를 KIS API에서 가져와 DB에 저장합니다. (비동기, 0:년, 1:분기)")
        public ResponseEntity<SuccessResponse<String>> initAllBalanceSheets(
                        @RequestParam(required = false, defaultValue = "0") String divCode) {
                new Thread(() -> kisInformationService.refreshAllBalanceSheets(divCode)).start();
                return ResponseEntity
                                .ok(new SuccessResponse<>(true, "전체 종목 대차대조표 갱신 시작 (백그라운드)",
                                                "Started background task for divCode=" + divCode));
        }

        @GetMapping("/{stockCode}")
        @Operation(summary = "재무제표(손익계산서) 조회", description = "특정 주식의 매출액, 영업이익, 당기순이익 등 재무 정보를 조회합니다. (0:년, 1:분기)")
        public ResponseEntity<SuccessResponse<List<FinancialStatementDto>>> getIncomeStatement(
                        @PathVariable String stockCode,
                        @RequestParam(required = false, defaultValue = "0") String divCode) {
                return ResponseEntity.ok(new SuccessResponse<>(true, "손익계산서 조회 성공",
                                kisInformationService.getIncomeStatement(stockCode, divCode)));
        }

        @PostMapping("/init-all")
        @Operation(summary = "전체 주식 재무제표(손익계산서+랭킹) 초기 데이터 구축", description = "모든 주식에 대해 최근 5년치 재무제표 데이터를 수집하여 DB에 저장합니다. (비동기 실행)")
        public ResponseEntity<SuccessResponse<String>> initFinancialStatementsAll() {
                new Thread(() -> kisInformationService.fetchAndSaveAllFinancialStatements()).start();
                return ResponseEntity
                                .ok(new SuccessResponse<>(true, "전체 주식 재무제표 데이터 구축 시작 (백그라운드 실행)",
                                                "Started background task"));
        }

        @PostMapping("/ratio/init-all")
        @Operation(summary = "전체 주식 재무비율(ROE/EPS 등) 갱신", description = "모든 주식에 대해 KIS API를 통해 재무비율을 조회하고 DB에 저장합니다. (분기/연간 선택 가능)")
        public ResponseEntity<SuccessResponse<String>> initAllFinancialRatios(
                        @RequestParam(required = false, defaultValue = "0") String divCode) {
                new Thread(() -> kisInformationService.refreshAllFinancialRatios(divCode)).start();
                return ResponseEntity
                                .ok(new SuccessResponse<>(true, "전체 주식 재무비율 갱신 시작 (백그라운드 실행)",
                                                "Started background task for divCode=" + divCode));
        }

        @GetMapping("/rank/ratio")
        @Operation(summary = "재무비율 기반 랭킹 조회", description = "DB에 저장된 재무비율을 기준으로 랭킹을 조회합니다. (eps, debt, roe, per, pbr, psr). direction 파라미터로 정렬 방향(asc, desc)을 지정할 수 있습니다.")
        public ResponseEntity<SuccessResponse<FinancialRatioRankDto>> getFinancialRatioRank(
                        @RequestParam(defaultValue = "roe") String sort,
                        @RequestParam(required = false, defaultValue = "0") String divCode,
                        @RequestParam(required = false) String direction) {
                return ResponseEntity
                                .ok(new SuccessResponse<>(true, "재무비율 랭킹 조회 성공 (" + sort + ")",
                                                kisInformationService.getFinancialRatioRank(sort, divCode, direction)));
        }

        @GetMapping("/metrics/{stockCode}")
        @Operation(summary = "특정 종목 투자 지표 조회", description = "특정 종목의 PER, PBR, PSR, EPS, ROE, BPS 정보를 조회합니다.")
        public ResponseEntity<SuccessResponse<InvestmentMetricDto>> getInvestmentMetrics(
                        @PathVariable String stockCode) {
                return ResponseEntity
                                .ok(new SuccessResponse<>(true, "투자 지표 조회 성공",
                                                kisInformationService.getInvestmentMetrics(stockCode)));
        }

        @GetMapping("/rank/market-cap")
        @Operation(summary = "시가총액 순위 조회 (범위 지정 가능)", description = "DB에 저장된 시가총액 정보를 기준으로 순위를 조회합니다. (start, end 파라미터로 범위 지정, 기본값 1~100)")
        public ResponseEntity<SuccessResponse<List<StockSearchResponseDto>>> getMarketCapRank(
                        @RequestParam(defaultValue = "1") int start,
                        @RequestParam(defaultValue = "100") int end) {
                return ResponseEntity.ok(new SuccessResponse<>(true, "시가총액 순위 조회 성공 (" + start + "~" + end + ")",
                                kisStockService.getMarketCapRanking(start, end)));
        }

        @PostMapping("/rank/market-cap/init-all")
        @Operation(summary = "전체 종목 시가총액 초기화/갱신", description = "모든 종목의 현재가를 조회하여 시가총액 정보를 갱신합니다. (비동기, 시간 소요됨)")
        public ResponseEntity<SuccessResponse<String>> initAllMarketCaps() {
                new Thread(() -> kisStockService.initAllStocksMarketCap()).start();
                return ResponseEntity.ok(new SuccessResponse<>(true, "전체 종목 시가총액 갱신 작업 시작 (백그라운드)",
                                "Started background task."));
        }

        @GetMapping("/ev-ebitda/{stockCode}")
        @Operation(summary = "특정 종목 EV/EBITDA 조회 (DB)", description = "특정 종목의 EV/EBITDA를 DB에서 조회합니다.")
        public ResponseEntity<SuccessResponse<String>> getEvEbitda(@PathVariable String stockCode) {
                String result = kisInformationService.getEvEbitdaFromDb(stockCode);
                return ResponseEntity.ok(new SuccessResponse<>(true, "EV/EBITDA 조회 성공 (미보유시 Null)", result));
        }

        @PostMapping("/other-major-ratios/refresh/{stockCode}")
        @Operation(summary = "특정 종목 EV/EBITDA 등 기타 지표 갱신 (API -> DB)", description = "KIS API에서 최신 기타 지표(EV/EBITDA 등)를 가져와 DB에 저장합니다.")
        public ResponseEntity<SuccessResponse<String>> refreshOtherMajorRatios(@PathVariable String stockCode) {
                kisStockService.updateOtherMajorRatios(stockCode);
                return ResponseEntity.ok(new SuccessResponse<>(true, "기타 지표(EV/EBITDA) 갱신 성공", null));
        }

        @PostMapping("/other-major-ratios/refresh/all")
        @Operation(summary = "전체 국내 주식 EV/EBITDA 등 기타 지표 일괄 갱신", description = "모든 국내 주식 종목에 대해 KIS API를 통해 기타 지표(EV/EBITDA 등)를 일괄적으로 갱신합니다. (비동기)")
        public ResponseEntity<SuccessResponse<String>> refreshAllOtherMajorRatios() {
                new Thread(() -> kisStockService.refreshAllOtherMajorRatios()).start();
                return ResponseEntity.ok(new SuccessResponse<>(true, "전체 종목 기타 지표 갱신 시작 (백그라운드 실행)",
                                "Started background task"));
        }

        @GetMapping("/investor-trend/{stockCode}")
        @Operation(summary = "종목별 투자자 수급(거래대금) 조회", description = "최근 3개월간 외국인/기관의 순매수 거래대금 추이를 조회합니다.")
        public ResponseEntity<SuccessResponse<InvestorTrendDto>> getInvestorTrend(@PathVariable String stockCode) {
                return ResponseEntity.ok(new SuccessResponse<>(true, "투자자 수급 조회 성공",
                                kisStockService.getInvestorTrend(stockCode)));
        }

        @GetMapping("/investor-trend/daily/{stockCode}")
        @Operation(summary = "종목별 일자별 수급 데이터 조회 (기간 선택)", description = "DB에 저장된 특정 종목의 일자별 외국인/기관/개인 순매수 정보를 조회합니다. (period: 1m, 3m, 1y)")
        public ResponseEntity<SuccessResponse<List<StockInvestorDailyDto>>> getDailyInvestorTrend(
                        @PathVariable String stockCode,
                        @RequestParam(defaultValue = "3m") String period) {
                return ResponseEntity.ok(new SuccessResponse<>(true, "일자별 투자자 수급 조회 성공",
                                kisStockService.getDailyInvestorTrend(stockCode, period)));
        }

        @PostMapping("/investor-trend/init-all")
        @Operation(summary = "전체 종목 투자자 수급 데이터 초기화 (DB)", description = "모든 종목에 대해 최근 1년치 일자별 수급 데이터를 가져와 DB에 저장합니다. (비동기)")
        public ResponseEntity<SuccessResponse<String>> initAllInvestorTrends() {
                new Thread(() -> {
                        kisStockService.updateAllInvestorTrends();
                }).start();
                return ResponseEntity.ok(new SuccessResponse<>(true, "전체 종목 수급 데이터 초기화 시작 (백그라운드)",
                                "Started background task."));
        }

        @PostMapping("/investor-trend/refresh/{stockCode}")
        @Operation(summary = "특정 종목 투자자 수급 데이터 갱신", description = "특정 종목에 대해 최근 1년치 일자별 수급 데이터를 가져와 DB를 갱신합니다.")
        public ResponseEntity<SuccessResponse<String>> refreshInvestorTrend(@PathVariable String stockCode) {
                kisStockService.fetchAndSaveInvestorTrend(stockCode);
                return ResponseEntity.ok(new SuccessResponse<>(true, "종목 수급 데이터 갱신 성공 (" + stockCode + ")", null));
        }

        @DeleteMapping("/investor-trend/all")
        @Operation(summary = "전체 종목 수급 데이터 초기화 (DB 데이터 전체 삭제)", description = "DB에 저장된 모든 종목의 일자별 수급 데이터를 삭제합니다. 초기화(init-all) 전 깨끗한 상태를 만들 때 사용합니다.")
        public ResponseEntity<SuccessResponse<String>> deleteAllInvestorTrendData() {
                kisStockService.deleteAllInvestorData();
                return ResponseEntity.ok(new SuccessResponse<>(true, "전체 종목 수급 데이터 삭제 성공", null));
        }

        @PostMapping("/categorize/init")
        @Operation(summary = "전체 종목 산업 분류 초기화/갱신", description = "모든 종목에 대해 AI를 사용하여 산업 분류를 수행하고 DB에 저장합니다. (비동기, 시간 소요됨)")
        public ResponseEntity<SuccessResponse<String>> initIndustryCategorization() {
                new Thread(() -> {
                        List<Stock> stocks = stockRepository.findAll();
                        for (Stock stock : stocks) {
                                industryCategorizationService.categorizeStock(stock.getStockCode());
                                try {
                                        Thread.sleep(1000);
                                } catch (InterruptedException e) {
                                } // Rate limit for AI
                        }
                }).start();
                return ResponseEntity.ok(new SuccessResponse<>(true, "전체 종목 산업 분류 초기화 시작 (백그라운드)",
                                "Started background task."));
        }

        @GetMapping("/industry/taxonomy")
        @Operation(summary = "산업 분류 트리 조회", description = "전체 산업(Industry) 및 세부산업(SubIndustry)의 계층 구조를 조회합니다.")
        public ResponseEntity<SuccessResponse<List<TaxonomyDto>>> getIndustryTaxonomy() {
                return ResponseEntity.ok(new SuccessResponse<>(true, "산업 분류 트리 조회 성공",
                                industryCategorizationService.getTaxonomy()));
        }

        @GetMapping("/industry/{subIndustryCode}/stocks")
        @Operation(summary = "세부 산업별 종목 리스트 조회", description = "특정 세부 산업에 속한 종목들을 페이징하여 조회합니다.")
        public ResponseEntity<SuccessResponse<Page<StockSearchResponseDto>>> getStocksBySector(
                        @PathVariable String subIndustryCode,
                        @PageableDefault(size = 20, sort = "stockCode", direction = Sort.Direction.ASC) Pageable pageable) {
                return ResponseEntity.ok(new SuccessResponse<>(true, "세부 산업별 종목 리스트 조회 성공",
                                kisStockService.getStocksBySector(subIndustryCode, pageable)));
        }

        @GetMapping("/competitors/{stockCode}")
        @Operation(summary = "경쟁사 조회 (산업/시가총액 기반)", description = "해당 종목의 산업 및 시가총액을 분석하여 가장 유사한 경쟁사 TOP 5를 반환합니다.")
        public ResponseEntity<SuccessResponse<List<StockSearchResponseDto>>> getCompetitors(
                        @PathVariable String stockCode) {
                List<Stock> competitors = competitorAnalysisService.findCompetitors(stockCode);
                List<StockSearchResponseDto> response = competitors.stream()
                                .map(s -> {
                                        StockPriceDto priceDto = kisStockService
                                                        .getStockPrice(s.getStockCode());
                                        return StockSearchResponseDto.builder()
                                                        .stockCode(s.getStockCode())
                                                        .stockName(s.getStockName())
                                                        .marketName(s.getMarketName())
                                                        .marketCap(priceDto != null ? priceDto.getMarketCap() : null)
                                                        .currentPrice(priceDto != null ? priceDto.getStockPrice()
                                                                        : null)
                                                        .priceChange(priceDto != null ? priceDto.getPriceChange()
                                                                        : null)
                                                        .changeRate(priceDto != null ? priceDto.getChangeRate() : null)
                                                        .build();
                                })
                                .collect(Collectors.toList());
                return ResponseEntity.ok(new SuccessResponse<>(true, "경쟁사 조회 성공 (산업/시가총액 기반)", response));
        }

        @GetMapping("/industry/{stockCode}")
        @Operation(summary = "종목 산업 분류 조회 (AI 자동 분류)", description = "해당 종목의 산업 및 세부 산업을 조회합니다. 분류가 안 되어있을 경우 AI를 통해 자동 분류 후 반환합니다.")
        public ResponseEntity<SuccessResponse<IndustryResponseDto>> getIndustry(@PathVariable String stockCode) {
                // 1. Ensure Categorized (Lazy Loading)
                industryCategorizationService.categorizeStock(stockCode);

                // 2. Fetch Stock with Industry
                Stock stock = stockRepository.findByStockCode(stockCode)
                                .orElseThrow(() -> new IllegalArgumentException("Stock not found: " + stockCode));

                List<IndustryResponseDto.Classification> classifications = stock.getStockIndustries().stream()
                                .map(si -> IndustryResponseDto.Classification.builder()
                                                .industryCode(si.getSubIndustry().getIndustry().getCode())
                                                .industryName(si.getSubIndustry().getIndustry().getName())
                                                .subIndustryCode(si.getSubIndustry().getCode())
                                                .subIndustryName(si.getSubIndustry().getName())
                                                .isPrimary(si.isPrimary())
                                                .build())
                                .collect(Collectors.toList());

                IndustryResponseDto response = IndustryResponseDto.builder()
                                .stockCode(stock.getStockCode())
                                .stockName(stock.getStockName())
                                .classifications(classifications)
                                .build();

                return ResponseEntity.ok(new SuccessResponse<>(true, "산업 분류 조회 성공", response));
        }
}
