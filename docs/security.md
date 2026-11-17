# Security model and launch gates

Authentication supports development HS256 tokens and configured production OIDC/JWK RS256 access tokens. Both validate
issuer, audience, expiration and canonical lowercase UUID subject with30s timestamp tolerance. Production mode has no
HMAC fallback, and the production profile rejects development-mode overrides or a configured JWT_SECRET. See the
[identity configuration and key-rotation guide](identity.md). Public-key caching has an explicit5min revocation window;
uncached/expired keys fail closed on issuer outages. Invalid credentials return401; unavailable key verification
returns503 with Retry-After. Identity comes exclusively from a validated token; no actor/owner fields are trusted.
Profiles bootstrap trusted subjects via PATCH /users/me. Metrics requires scope=metrics; health is unauthenticated.
Provider-specific role claims do not grant administrative privileges. Login/registration is owned by the trusted IdP;
opaque provider subjects require an explicit ownership-preserving mapping before use.

All post and media-read paths use current visibility. Blocks bidirectional; mutes are feed preference only. Private S3
buckets must have public access disabled and IAM prefix restrictions. Presigned PUT expires5min; exact content
type/length signed, completion HEAD validates declared metadata and conditional copy freezes bytes. Completion now
validates the staged object using an ETag-conditional GET and freezes it with the same conditional copy. PNG/JPEG must
match the declared format and decode within 10 MiB and 20 megapixels. At most two synchronous validations run per app
instance; saturation returns503 immediately and completion can be retried. MP4 is limited to 100 MiB and must have a
plausible initial ftyp box; this is only header admission, not full video validation. Reused staging URLs cannot replace
completed media. Failed content validation returns400 and leaves the upload incomplete/unattachable. Existing completed
objects are not retroactively scanned; revalidate or quarantine legacy media before launch. Malware scanning,
moderation, image sanitization, full MP4 validation and video transcoding remain launch gates. Download URLs expire30s
and use attachment disposition; capability revocation has that explicit residual window. S3 staging lifecycle should
delete uploads/ after1day; unattached objects/ after a DB-aware orphan reconciliation (never blanket-delete active
objects). Production media encryption, audit access, KMS rotation, CDN private-origin config and multipart support need
environment implementation.

Redis per-subject quota100requests/sec; mutations fail closed503 on Redis failure, GET reads bypass quota to preserve
availability. Add edge IP/device quotas, bounded registration, bot detection and WAF limits before exposing the service.
Tomcat connections/thread backlog and Hikari/SQL timeouts bound resources. The application enforces a 1 MiB API body
limit (413), including missing Content-Length/chunked requests, and buffers at most that limit plus one byte per active
request. The limit is inclusive and applies before JSON deserialization; binary media bypasses the API. Keep an ingress
limit and slow-client timeouts as additional protections. Text is returned as JSON and must be escaped by clients; do
not accept HTML rendering. No multi-tenancy is claimed. Logs must exclude tokens, captions, media URLs and raw user IDs;
traces should use generated correlations and no identity labels. Retain security audit events separately with access
controls. Full audit export, spam classifier, administrative moderation, privacy-aware analytics consent, data subject
erasure and automated retention are launch gates, not completed functionality.
