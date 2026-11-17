# Identity provider integration

The API is an OAuth2 resource server. It verifies access tokens; it does not implement login, passwords, refresh-token
issuance or an OIDC authorization server. Configure a trusted identity provider to issue API access tokens with
canonical lowercase UUID subjects and the API's distinct audience. The issuer owns registration.
`PATCH /api/v1/users/me` creates the application profile for that authenticated subject.

## Configuration

| Variable                 | Development                      | OIDC / production                                |
|--------------------------|----------------------------------|--------------------------------------------------|
| `AUTH_MODE`              | `development` (local default)    | `oidc`                                           |
| `JWT_SECRET`             | Required, at least32 UTF-8 bytes | Must be unset/empty                              |
| `OIDC_ISSUER`            | Unset                            | Exact trusted HTTPS issuer, including realm/path |
| `OIDC_JWK_SET_URI`       | Unset                            | Explicit trusted HTTPS public JWK endpoint       |
| `OIDC_AUDIENCE`          | Fixed `social-feed-api`          | Required audience; defaults to `social-feed-api` |
| `SPRING_PROFILES_ACTIVE` | Unset or local profiles          | Include `production`                             |

The `production` profile selects OIDC and refuses an effective development-mode override. Kubernetes activates this
profile explicitly. Missing endpoints, unknown modes, insecure URLs, embedded URL credentials/fragments, blank audiences
and mixed HMAC/OIDC settings fail startup. Issuer URLs cannot contain query parameters. JWK URLs may use a different
trusted host, for example a provider CDN; only an operator configures them, never token headers or request input.

Development Compose remains compatible with the local secret/token/smoke commands in the README. The application now
validates the secret rather than Compose interpolation, so OIDC Compose needs no shared secret. For an actual
provisioned identity provider:

```sh
unset JWT_SECRET
export AUTH_MODE=oidc SPRING_PROFILES_ACTIVE=production
export OIDC_ISSUER=https://identity.example.com/realms/social-feed
export OIDC_JWK_SET_URI=https://identity.example.com/realms/social-feed/protocol/openid-connect/certs
export OIDC_AUDIENCE=social-feed-api
docker compose up --build -d
```

Replace the example URLs with your issuer's exact metadata. Remove JWT_SECRET from any Compose `.env` file or injected
Kubernetes secret as well as the shell environment. Explicit JWK configuration avoids discovery during startup; startup
does not establish issuer availability. Use an access token issued for this API in `Authorization: Bearer <token>`. The
development `scripts/token.py`, smoke script and benchmark seeder do not mint production access tokens. The token script
refuses OIDC/production environments; the server's algorithm policy is the security boundary.

## Verification policy

- OIDC accepts RS256 signatures from the configured JWK set only. HMAC, unsigned and other algorithms are rejected;
  there is no development-key fallback.
- Require exact issuer, configured audience membership, expiration and a canonical lowercase UUID `sub`. Validate
  expiration/not-before with30s clock tolerance. Identity providers with opaque subjects require a separate
  issuer/subject mapping before use; do not hash or truncate subjects into UUIDs.
- Accepted JOSE types are `JWT`, `at+jwt`, or omitted type for compatible providers. Give the API a distinct audience
  from browser/login clients to prevent ID-token substitution. This is a configured access-token policy, not full
  automatic RFC9068 profile validation.
- Standard Spring scope conversion accepts `scope`/`scp`. `/actuator/prometheus` requires `metrics`; arbitrary provider
  role claims do not grant this permission. Quota identity and application ownership derive from the validated UUID
  subject.

Changing issuer/tenant or subject mapping requires an identity migration that preserves ownership of existing UUID
users. Selecting a new issuer against an existing database without verifying subject ownership is unsafe.

## Keys, rotation and outages

JWK downloads use the JVM HTTPS trust store, no redirects, 2s connect/read timeouts and a64KiB response cap. Non-success
error bodies are not buffered or decoded. The Nimbus source coalesces refresh work and bounds waiting for cache refresh
at2s. It permits two remote refreshes per1s interval per app instance (including initial load), preventing arbitrary
`kid` values from triggering unlimited issuer traffic. An unknown key triggers refresh when budget allows; it can be
temporarily rejected if that budget is exhausted. No automatic Nimbus fetch retry or stale-key outage extension is
enabled.

Public keys are cached in process for5min. Fresh cached known keys can verify tokens during issuer outages; uncached
keys and expired caches fail closed. Token expiration/issuer/audience/subject checks still execute on each request, and
Spring's default certificate-thumbprint check is retained. Invalid credentials return401; unavailable key retrieval,
expired-key cache refresh or refresh-budget exhaustion returns503 with Retry-After rather than an unhandled server
exception. Key removal does not instantly revoke cached keys: allow up to5min cache retention, subject to token
expiration and30s clock tolerance. This is not a per-token revocation/introspection implementation.

For planned rotation, publish old/new keys together before signing with the new unique `kid`, retain old public keys
through the last old token's lifetime plus skew, and observe issuer/401 metrics. Reuse of a `kid` for different key
bytes is unsupported; use a new ID. For emergency key compromise, fence issuance and roll decoder caches (for example
restart pods); test issuer reachability first. A cold/expired cache during an outage causes authentication rejection,
not acceptance without verification. Do not expand TTL or enable stale-key fallback to hide an issuer outage.

## Executed scope and remaining operations

Tests sign real RSA/HMAC tokens, simulate HTTPS JWK responses and validate claims, rotation, cached/expired outage
behavior, oversized/malformed key sets, unknown-key refresh limits and production startup guards. Real RSA tokens also
pass through the application HTTP security filter chain, including metrics scope enforcement. These use an in-process
simulated transport, not an external issuer or a live TLS/key-rotation drill. Full mTLS/DPoP sender-constrained token
support is not claimed. Configure and verify real provider client/audience policy, TLS trust, time synchronization,
egress, key overlap and incident procedures before production deployment.
