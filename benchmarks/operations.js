import http from 'k6/http';
import {check} from 'k6';

const workload = __ENV.WORKLOAD || 'posts';
if (!['posts', 'retries', 'graph', 'likes', 'pages'].includes(workload)) throw new Error('Unknown WORKLOAD');
const fixture = JSON.parse(open(__ENV.FIXTURE || 'results/fixture.json'));
if (!fixture.tokens.length || !fixture.posts.length) throw new Error('Seed a fixture first');
export const options = {
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    scenarios: {
        operation: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.RPS || 20),
            timeUnit: '1s',
            duration: __ENV.DURATION || '30s',
            preAllocatedVUs: Number(__ENV.VUS || 20),
            maxVUs: Number(__ENV.MAX_VUS || 200)
        }
    },
    thresholds: {http_req_failed: ['rate<0.001'], checks: ['rate==1'], dropped_iterations: ['count==0']},
};
const base = (__ENV.BASE_URL || 'http://localhost:8080') + '/api/v1';
let cursor = null;
export default function () {
    const token = fixture.tokens[(__VU - 1) % fixture.tokens.length];
    const params = {headers: {Authorization: `Bearer ${token}`, 'Content-Type': 'application/json'}, tags: {workload}};
    let response;
    if (workload === 'posts' || workload === 'retries') {
        // The retry workload deliberately replays one identical request per VU.
        params.headers['Idempotency-Key'] = `load-${__ENV.RUN_ID || 'local'}-${__VU}-${workload === 'retries' ? 0 : __ITER}`;
        response = http.post(`${base}/posts`, JSON.stringify({
            caption: 'load test',
            visibility: 'PUBLIC',
            state: 'PUBLISHED',
            media: []
        }), params);
        check(response, {'created or replayed': r => r.status === 201});
    } else if (workload === 'graph') {
        const post = fixture.posts[(__VU - 1) % fixture.posts.length];
        response = http.get(`${base}/posts/${post}`, params);
        check(response, {'post available': r => r.status === 200});
        if (response.status === 200) {
            const author = response.json().author;
            response = http.get(`${base}/users/${author}/followers?limit=50`, params);
            check(response, {'graph available': r => r.status === 200});
        }
    } else if (workload === 'likes') {
        // Shared hot post; alternate idempotent PUT and DELETE.
        const url = `${base}/posts/${fixture.posts[0]}/likes/me`;
        response = __ITER % 2 === 0 ? http.put(url, null, params) : http.del(url, null, params);
        check(response, {'engagement accepted': r => r.status === 204});
    } else {
        response = http.get(`${base}/feed/home?limit=1&mode=${__ENV.MODE || 'chronological'}${cursor ? '&cursor=' + encodeURIComponent(cursor) : ''}`, params);
        check(response, {'page available': r => r.status === 200});
        cursor = response.status === 200 ? response.json().nextCursor : null;
    }
}
