import http from 'k6/http';
import { sleep } from 'k6';
export const options = {
  stages: [
    { duration: '15s', target: 10 },
    { duration: '30s', target: 150 },
    { duration: '15s', target: 10 },
    { duration: '5s', target: 0 },
  ],
  thresholds: { http_req_failed: ['rate<0.99'] },
};
const base = __ENV.BASE_URL || 'http://localhost:8080';
export default function () {
  let r = http.get(`${base}/api/v1/shop`);
  if (r.status === 202) {
    const ticket = r.json('ticket');
    for (let i=0; i<10; i++) {
      sleep(0.5);
      r = http.get(`${base}/api/v1/shop?ticket=${encodeURIComponent(ticket)}`);
      if (r.status !== 202) break;
    }
  }
  sleep(0.1);
}
