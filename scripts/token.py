#!/usr/bin/env python3
"""Local development token minting only. Production uses a trusted issuer."""
import base64
import hashlib
import hmac
import json
import os
import sys
import time
import uuid

if os.environ.get('AUTH_MODE', 'development').strip().lower() != 'development' or 'production' in [p.strip() for p in
                                                                                                   os.environ.get(
                                                                                                           'SPRING_PROFILES_ACTIVE',
                                                                                                           '').split(
                                                                                                           ',')]:
    raise SystemExit(
        'Development token minting is disabled for OIDC/production. Obtain an access token from your identity provider.')
if len(os.environ.get('JWT_SECRET', '').encode()) < 32:
    raise SystemExit('Set a development JWT_SECRET containing at least 32 bytes.')
try:
    valid_subject = len(sys.argv) == 2 and str(uuid.UUID(sys.argv[1])) == sys.argv[1]
except ValueError:
    valid_subject = False
if not valid_subject:
    raise SystemExit('Usage: token.py <canonical-lowercase-user-uuid>')

enc = lambda v: base64.urlsafe_b64encode(json.dumps(v, separators=(',', ':')).encode()).rstrip(b'=')
now = int(time.time());
raw = enc({'alg': 'HS256', 'typ': 'JWT'}) + b'.' + enc(
    {'sub': sys.argv[1], 'iss': 'social-feed', 'aud': 'social-feed-api', 'iat': now, 'exp': now + 3600})
print((raw + b'.' + base64.urlsafe_b64encode(
    hmac.new(os.environ['JWT_SECRET'].encode(), raw, hashlib.sha256).digest()).rstrip(b'=')).decode())
