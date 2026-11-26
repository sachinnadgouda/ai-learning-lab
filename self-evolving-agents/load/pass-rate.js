// Customer-level pass-rate test. Each iteration is one customer who keeps trying
// (honouring Retry-After and waiting-room tickets) until served or MAX_WAIT_S passes.
// PASS = customer eventually received HTTP 200. Env: RATE (customers/s), DURATION, MAX_WAIT_S, LABEL.
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const RATE = Number(__ENV.RATE || 70);
const MAX_WAIT_S = Number(__ENV.MAX_WAIT_S || 20);
const LABEL = __ENV.LABEL || 'run';
const base = __ENV.BASE_URL || 'http://app:8080';

const served = new Counter('customers_served');
const abandoned = new Counter('customers_abandoned');
const expired = new Counter('customers_ticket_expired');
const direct = new Counter('customers_served_direct');
const viaQueue = new Counter('customers_served_via_queue');
const rejects = new Counter('http_429_responses');
const timeToServe = new Trend('time_to_serve_ms', true);

export const options = {
  scenarios: {
    customers: {
      executor: 'constant-arrival-rate',
      rate: RATE, timeUnit: '1s', duration: __ENV.DURATION || '120s',
      preAllocatedVUs: RATE * 5, maxVUs: RATE * MAX_WAIT_S * 2,
    },
  },
};

export default function () {
  const start = Date.now();
  let ticket = null;
  let queued = false;
  while (Date.now() - start < MAX_WAIT_S * 1000) {
    const url = `${base}/api/v1/shop` + (ticket ? `?ticket=${encodeURIComponent(ticket)}` : '');
    const r = http.get(url, { tags: { name: 'shop' } });
    if (r.status === 200) {
      served.add(1); (queued ? viaQueue : direct).add(1);
      timeToServe.add(Date.now() - start);
      return;
    }
    if (r.status === 202) { queued = true; ticket = r.json('ticket') || ticket; sleep(0.5); continue; }
    if (r.status === 429) { rejects.add(1); sleep(1); continue; }
    if (r.status === 410) { expired.add(1); ticket = null; queued = false; sleep(0.5); continue; }
    sleep(1);
  }
  abandoned.add(1);
}

export function handleSummary(data) {
  const v = (m, k) => (data.metrics[m] && data.metrics[m].values[k]) || 0;
  const s = v('customers_served', 'count'), a = v('customers_abandoned', 'count');
  const out = {
    label: LABEL, rate: RATE, maxWaitSeconds: MAX_WAIT_S,
    customersServed: s, customersAbandoned: a,
    passRatePercent: s + a ? +(100 * s / (s + a)).toFixed(2) : null,
    servedDirect: v('customers_served_direct', 'count'), servedViaQueue: v('customers_served_via_queue', 'count'),
    http429: v('http_429_responses', 'count'), ticketsExpired: v('customers_ticket_expired', 'count'),
    timeToServeP50ms: v('time_to_serve_ms', 'med'), timeToServeP95ms: v('time_to_serve_ms', 'p(95)'),
    httpReqP95ms: v('http_req_duration', 'p(95)'), totalHttpRequests: v('http_reqs', 'count'),
  };
  return { [`/results/${LABEL}.json`]: JSON.stringify(out, null, 2), stdout: JSON.stringify(out, null, 2) + '\n' };
}
