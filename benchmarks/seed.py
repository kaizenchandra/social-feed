#!/usr/bin/env python3
"""Seed a disposable development deployment through its authenticated API."""
import argparse
import json
import os
import random
import subprocess
import time
import urllib.request
import uuid
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--users', type=int, default=20)
parser.add_argument('--follows', type=int, default=5)
parser.add_argument('--posts', type=int, default=5)
parser.add_argument('--distribution', choices=['uniform', 'zipf'], default='uniform')
parser.add_argument('--output', type=Path, default=Path('benchmarks/results/fixture.json'))
args = parser.parse_args()
if args.users < 2 or not 0 <= args.follows < args.users or args.posts < 1:
    parser.error('users >= 2, 0 <= follows < users, posts >= 1 required')
base = os.environ.get('BASE_URL', 'http://localhost:8080')
rng = random.Random(42)
identities = []
post_ids = []


def call(method, path, token, body=None, key=None):
    headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}
    if key:
        headers['Idempotency-Key'] = key
    request = urllib.request.Request(base + '/api/v1' + path, method=method,
                                     headers=headers, data=None if body is None else json.dumps(body).encode())
    with urllib.request.urlopen(request, timeout=10) as response:
        data = response.read()
        return json.loads(data) if data else None


for i in range(args.users):
    actor = str(uuid.uuid4())
    token = subprocess.check_output(['python3', 'scripts/token.py', actor], text=True).strip()
    call('PATCH', '/users/me', token, {'displayName': f'Benchmark {i}', 'privateAccount': False, 'active': True})
    identities.append({'id': actor, 'token': token})
for i, actor in enumerate(identities):
    remaining = [j for j in range(args.users) if j != i]
    for _ in range(args.follows):
        weights = [1 / (j + 1) for j in remaining] if args.distribution == 'zipf' else None
        target = rng.choices(remaining, weights=weights)[0]
        remaining.remove(target)
        call('POST', f'/users/{identities[target]["id"]}/follow', actor['token'])
        time.sleep(.015)  # Keep setup below the per-user quota.
    for j in range(args.posts):
        post = call('POST', '/posts', actor['token'],
                    {'caption': f'Benchmark {j}', 'visibility': 'PUBLIC', 'state': 'PUBLISHED', 'media': []},
                    f'seed-{j}')
        post_ids.append(post['id'])
        time.sleep(.015)
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps({'tokens': [a['token'] for a in identities], 'posts': post_ids,
                                   'users': args.users, 'follows': args.follows, 'postsPerUser': args.posts,
                                   'distribution': args.distribution}))
args.output.chmod(0o600)
print(f'Seeded {args.users} users and {len(post_ids)} posts; local credentials saved to {args.output}')
