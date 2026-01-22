package com.AISA.AISA.kisOverseasStock.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

@Component
@RequiredArgsConstructor
@Slf4j
public class OverseasStockFinancialScheduler {

    /**
     * 매월 2일 오전 6시에 해외 주식 손익계산서 및 재무상태표 데이터를 업데이트합니다.
     * 리소스 경합 및 API 속도 제한을 방지하기 위해 순차적으로 실행합니다.
     */
    @Scheduled(cron = "0 0 6 2 * *")
    public void scheduledUpdateOverseasFinancials() {
        log.info("Starting scheduled update for overseas stock financial data...");

        // 1. 손익계산서 업데이트 (fetch_us_financials.py)
        runPythonScript("fetch_us_financials.py", "Income Statement");

        // 2. 재무상태표 업데이트 (fetch_us_balance_sheets.py)
        runPythonScript("fetch_us_balance_sheets.py", "Balance Sheet");

        log.info("Completed all scheduled overseas financial data updates.");
    }

    private void runPythonScript(String scriptName, String taskLabel) {
        log.info("Starting {} update using {}...", taskLabel, scriptName);
        Path tempDir = null;
        try {
            // 임시 디렉토리 생성
            tempDir = Files.createTempDirectory("overseas_financial_" + scriptName.replace(".py", ""));
            File scriptFile = tempDir.resolve(scriptName).toFile();

            // 클래스패스에서 파이썬 스크립트 복사
            try (InputStream is = new ClassPathResource("scripts/" + scriptName).getInputStream()) {
                Files.copy(is, scriptFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            // 파이썬 프로세스 실행 (시스템 PATH의 python 사용)
            ProcessBuilder pb = new ProcessBuilder("python", scriptFile.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 파이썬 출력 로그 캡처
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[Python-{}] {}", taskLabel, line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("Successfully completed {} update.", taskLabel);
            } else {
                log.error("{} update failed with exit code: {}", taskLabel, exitCode);
            }

        } catch (Exception e) {
            log.error("Failed to run {} script: {}", scriptName, e.getMessage(), e);
        } finally {
            // 임시 디렉토리 정리
            if (tempDir != null) {
                try {
                    Files.walk(tempDir)
                            .sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
