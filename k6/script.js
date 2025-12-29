
import http from 'k6/http';
import { check, sleep } from 'k6';

// 1. 테스트 설정 (옵션)
export const options = {
    // 가상 유저(VU) 및 지속 시간 설정
    stages: [
        { duration: '30s', target: 20 }, // 처음 30초 동안 유저를 0 -> 200명으로 증가 (Ramp-up)
        { duration: '1m', target: 20 },  // 1분 동안 200명 유지 (Steady State)
        { duration: '10s', target: 0 },   // 10초 동안 유저를 200 -> 0명으로 감소 (Ramp-down)
    ],

    // 임계값 설정 (Thresholds) - 테스트 성공/실패 기준
    thresholds: {
        http_req_duration: ['p(95)<500'], // 95%의 요청이 500ms 이내에 완료되어야 함
        http_req_failed: ['rate<0.01'],   // 에러율이 1% 미만이어야 함
    },
};


// 2. 가상 유저가 수행할 시나리오
export default function () {
    // 테스트할 대상 URL (기본값 변경)
    const BASE_URL = __ENV.TARGET_URL || 'http://34.50.11.164:8080';

    const stockCode = '005930'; // 삼성전자

    // 여러 API를 그룹으로 묶어서 동시 요청 (실제 브라우저 동작 시뮬레이션)
    // 또는 순차적으로 호출하여 각 API의 성능 측정 중 선택.
    // 여기서는 개별 성능 측정을 위해 순차 호출 + Check 사용

    const responses = http.batch([
        ['GET', `${BASE_URL}/api/stocks/${stockCode}/price`],
        ['GET', `${BASE_URL}/api/stocks/${stockCode}/chart?startDate=20000101&endDate=20251229&dateType=D`],
        ['GET', `${BASE_URL}/api/stocks/financial/metrics/${stockCode}`],
        ['GET', `${BASE_URL}/api/dividend/${stockCode}/dividend?startDate=20230101&endDate=20251229`],
        ['GET', `${BASE_URL}/api/dividend/${stockCode}/detail`],
        ['GET', `${BASE_URL}/api/stocks/financial/${stockCode}?divCode=0`],
    ]);

    check(responses[0], { 'Price status is 200': (r) => r.status === 200 });
    check(responses[1], { 'Chart status is 200': (r) => r.status === 200 });
    check(responses[2], { 'Metrics status is 200': (r) => r.status === 200 });
    check(responses[3], { 'Dividend status is 200': (r) => r.status === 200 });
    check(responses[4], { 'Dividend Detail status is 200': (r) => r.status === 200 });
    check(responses[5], { 'Income Statement status is 200': (r) => r.status === 200 });

    sleep(1);
}

