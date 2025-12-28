
import http from 'k6/http';
import { check, sleep } from 'k6';

// 1. 테스트 설정 (옵션)
export const options = {
    // 가상 유저(VU) 및 지속 시간 설정
    stages: [
        { duration: '30s', target: 20 }, // 처음 30초 동안 유저를 0 -> 20명으로 증가 (Ramp-up)
        { duration: '1m', target: 20 },  // 1분 동안 20명 유지 (Steady State)
        { duration: '10s', target: 0 },  // 10초 동안 유저를 20 -> 0명으로 감소 (Ramp-down)
    ],

    // 임계값 설정 (Thresholds) - 테스트 성공/실패 기준
    thresholds: {
        http_req_duration: ['p(95)<500'], // 95%의 요청이 500ms 이내에 완료되어야 함
        http_req_failed: ['rate<0.01'],   // 에러율이 1% 미만이어야 함
    },
};

// 2. 가상 유저가 수행할 시나리오
export default function () {
    // 테스트할 대상 URL
    // 환경 변수(TARGET_URL)가 있으면 그걸 쓰고, 없으면 아래 IP를 기본값으로 사용
    const BASE_URL = __ENV.TARGET_URL || 'http://34.50.11.164:8080';

    // GET 요청 테스트
    const res = http.get(`${BASE_URL}/actuator/health`); // 헬스 체크 엔드포인트 공략

    // 응답 확인 (검증)
    const isStatus200 = check(res, {
        'status is 200': (r) => r.status === 200,
    });

    // 부하 조절을 위한 대기 시간 (0.5초 ~ 1.5초 랜덤)
    sleep(1);
}
