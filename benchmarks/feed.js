import http from 'k6/http';
import {check} from 'k6';

export const options = {
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    scenarios: {
        feed: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.RPS || 1000),
            timeUnit: '1s',
            duration: __ENV.DURATION || '60s',
            preAllocatedVUs: Number(__ENV.VUS || 200),
            maxVUs: Number(__ENV.MAX_VUS || 2000)
        }
    },
    thresholds: {
        checks: ['rate==1'],
        http_req_failed: ['rate<0.001'],
        http_req_duration: ['p(99)<200'],
        dropped_iterations: ['count==0']
    }
};
const tokens = __ENV.FIXTURE ? JSON.parse(open(__ENV.FIXTURE)).tokens : JSON.parse(__ENV.TOKENS || '[]');
if (!tokens.length) throw new Error('Supply TOKENS or FIXTURE with seeded authenticated users');
export default function () {
    if (!tokens.length) throw new Error('TOKENS must contain independently authenticated, seeded users');
    const token = tokens[(__VU - 1) % tokens.length];
    const r = http.get(`${__ENV.BASE_URL || 'http://localhost:8080'}/api/v1/feed/home?mode=${__ENV.MODE || 'chronological'}&limit=20`, {headers: {Authorization: `Bearer ${token}`}});
    check(r, {'success': r => r.status === 200});
}
