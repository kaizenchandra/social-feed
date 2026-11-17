#!/usr/bin/env python3
import json
import os
import subprocess
import time
import urllib.request
import uuid

base = os.environ.get('BASE_URL', 'http://localhost:8080')


def token(u): return subprocess.check_output(['python3', 'scripts/token.py', str(u)], text=True).strip()


def call(method, path, t, body=None, key=None):
    headers = {'Authorization': 'Bearer ' + t, 'Content-Type': 'application/json'}
    if key: headers['Idempotency-Key'] = key
    req = urllib.request.Request(base + '/api/v1' + path, data=None if body is None else json.dumps(body).encode(),
                                 headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=10) as r:
        data = r.read();
        return json.loads(data) if data else None


for i in range(60):
    try:
        urllib.request.urlopen(base + '/actuator/health/readiness', timeout=2);
        break
    except Exception:
        time.sleep(1)
else:
    raise SystemExit('Readiness did not succeed within 60 seconds; inspect app startup and migration logs.')
a, b = uuid.uuid4(), uuid.uuid4();
ta, tb = token(a), token(b)
for t in [ta, tb]: call('PATCH', '/users/me', t, {'displayName': 'Smoke', 'privateAccount': False, 'active': True})
call('POST', f'/users/{b}/follow', ta)
p = call('POST', '/posts', tb, {'caption': 'end-to-end', 'visibility': 'PUBLIC', 'state': 'PUBLISHED', 'media': []},
         'smoke')
assert call('POST', '/posts', tb, {'caption': 'end-to-end', 'visibility': 'PUBLIC', 'state': 'PUBLISHED', 'media': []},
            'smoke')['id'] == p['id']
feed = call('GET', '/feed/home', ta);
assert p['id'] in [x['id'] for x in feed['items']]
call('PUT', f"/posts/{p['id']}/likes/me", ta)
time.sleep(2)  # Let the asynchronous pipeline materialize before testing revocation.
call('POST', f'/users/{b}/block', ta)
assert not call('GET', '/feed/home', ta)['items']
print('PASS: authenticated profiles, follow, idempotent post, eligible feed, like and block filtering')

# Exercise browser-style presigned media upload and private authorized retrieval.
import struct, zlib


def png_chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))


image = (b'\x89PNG\r\n\x1a\n' + png_chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 2, 0, 0, 0))
         + png_chunk(b'IDAT', zlib.compress(b'\x00\xff\x00\x00')) + png_chunk(b'IEND', b''))
u = call('POST', '/media/uploads', tb, {'contentType': 'image/png', 'bytes': len(image)})
headers = {k: ','.join(v) for k, v in u['headers'].items()}
with urllib.request.urlopen(urllib.request.Request(u['url'], data=image, headers=headers, method='PUT'),
                            timeout=10) as r: assert r.status == 200
call('POST', f"/media/uploads/{u['uploadId']}/complete", tb)
p = call('POST', '/posts', tb,
         {'caption': 'media', 'visibility': 'SELF', 'state': 'PUBLISHED', 'media': [u['uploadId']]}, 'media-smoke')
assert p['media'][0]['id'] == u['uploadId']
link = call('GET', f"/media/{u['uploadId']}/download", tb)['url']
with urllib.request.urlopen(link, timeout=10) as r: assert r.read() == image
print('PASS: signed S3 PUT, verified completion, media attachment and authorized signed GET')

# Uploaded MIME metadata cannot make arbitrary bytes eligible for publication.
import urllib.error


def expect_status(status, operation):
    try:
        operation()
    except urllib.error.HTTPError as error:
        assert error.code == status, (status, error.code)
    else:
        raise AssertionError(f'Expected HTTP {status}')


forged = b'<html>not an image</html>'
u = call('POST', '/media/uploads', tb, {'contentType': 'image/png', 'bytes': len(forged)})
headers = {k: ','.join(v) for k, v in u['headers'].items()}
with urllib.request.urlopen(urllib.request.Request(u['url'], data=forged, headers=headers, method='PUT'),
                            timeout=10) as r: assert r.status == 200
expect_status(400, lambda: call('POST', f"/media/uploads/{u['uploadId']}/complete", tb))
expect_status(404, lambda: call('GET', f"/media/{u['uploadId']}/download", tb))
expect_status(409, lambda: call('POST', '/posts', tb,
                                {'caption': 'forged', 'visibility': 'PUBLIC', 'state': 'PUBLISHED',
                                 'media': [u['uploadId']]}, 'forged-media'))


def oversized(chunked):
    data = (b' ' * 65536 for _ in range(17)) if chunked else b' ' * (1024 * 1024 + 1)
    request = urllib.request.Request(base + '/api/v1/users/me', data=data, method='PATCH',
                                     headers={'Authorization': 'Bearer ' + tb, 'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=10): pass


expect_status(413, lambda: oversized(False))
expect_status(413, lambda: oversized(True))
print('PASS: forged-media completion/download/publication rejected; declared and chunked oversized JSON rejected')
