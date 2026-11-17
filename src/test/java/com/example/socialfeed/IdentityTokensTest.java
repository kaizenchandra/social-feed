package com.example.socialfeed;

import java.time.*;
import java.util.*;
import java.util.function.Consumer;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class IdentityTokensTest {
    static final String ISSUER = "https://identity.example.test/realm/feed";
    static final String JWKS = "https://identity.example.test/realm/feed/keys";
    static final IdentityTokens.Settings OIDC = new IdentityTokens.Settings(IdentityTokens.Mode.OIDC, ISSUER, JWKS, "social-feed-api");
    static final IdentityTokens.Settings DEV = new IdentityTokens.Settings(IdentityTokens.Mode.DEVELOPMENT, "", "", "social-feed-api");
    static final String SECRET = "local-test-secret-at-least-32-bytes";
    static RSAKey first, second, attacker;

    @BeforeAll
    static void keys() throws Exception {
        first = new RSAKeyGenerator(2048).keyID("first").generate();
        second = new RSAKeyGenerator(2048).keyID("second").generate();
        attacker = new RSAKeyGenerator(2048).keyID("first").generate();
    }

    static JWTClaimsSet claims(Consumer<JWTClaimsSet.Builder> customize) {
        Instant now = Instant.now();
        var builder = new JWTClaimsSet.Builder().issuer(ISSUER).audience("social-feed-api").subject(UUID.randomUUID().toString())
                .issueTime(Date.from(now)).notBeforeTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(now.plusSeconds(300)));
        customize.accept(builder);
        return builder.build();
    }

    static String sign(RSAKey key, JWTClaimsSet claims) throws Exception {
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), claims);
        token.sign(new RSASSASigner(key));
        return token.serialize();
    }

    static String jwks(RSAKey... keys) {
        return new JWKSet(Arrays.stream(keys).map(k -> (JWK) k.toPublicJWK()).toList()).toString();
    }

    @Test
    void acceptsValidRsaTokenAndCachesKnownKey() throws Exception {
        var transport = new RestTemplate();
        var server = MockRestServiceServer.bindTo(transport).build();
        server.expect(requestTo(JWKS)).andRespond(withSuccess(jwks(first), MediaType.APPLICATION_JSON));
        try (var decoder = IdentityTokens.oidc(OIDC, transport, Duration.ofMinutes(5), Duration.ofSeconds(1))) {
            var claims = claims(c -> c.claim("scope", "metrics"));
            assertThat(decoder.decode(sign(first, claims)).getSubject()).isEqualTo(claims.getSubject());
            assertThat(decoder.decode(sign(first, claims)).getClaimAsString("scope")).isEqualTo("metrics");
        }
        server.verify();
    }

    @Test
    void issuerAudienceExpiryAndCanonicalSubjectAreMandatory() throws Exception {
        var transport = new RestTemplate();
        var server = MockRestServiceServer.bindTo(transport).build();
        server.expect(requestTo(JWKS)).andRespond(withSuccess(jwks(first), MediaType.APPLICATION_JSON));
        var invalid = List.<Consumer<JWTClaimsSet.Builder>>of(c -> c.issuer("https://other.example.test"), c -> c.audience("other-api"),
                c -> c.expirationTime(null), c -> c.expirationTime(Date.from(Instant.now().minusSeconds(120))),
                c -> c.notBeforeTime(Date.from(Instant.now().plusSeconds(120))), c -> c.subject(null), c -> c.subject("1-1-1-1-1"),
                c -> c.subject("unmapped-provider-subject"), c -> c.subject("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"),
                c -> c.claim("cnf", Map.of("x5t#S256", "unmatched-certificate")));
        try (var decoder = IdentityTokens.oidc(OIDC, transport, Duration.ofMinutes(5), Duration.ofSeconds(1))) {
            for (var mutation : invalid)
                assertThatThrownBy(() -> decoder.decode(sign(first, claims(mutation)))).isInstanceOf(JwtException.class);
        }
        server.verify();
    }

    @Test
    void rejectsForgedSignaturesHmacAndUnsignedTokens() throws Exception {
        var transport = new RestTemplate();
        var server = MockRestServiceServer.bindTo(transport).build();
        server.expect(requestTo(JWKS)).andRespond(withSuccess(jwks(first), MediaType.APPLICATION_JSON));
        var claims = claims(c -> {
        });
        var hmac = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        hmac.sign(new MACSigner(SECRET));
        var otherRsaAlgorithm = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS512).keyID(first.getKeyID()).build(), claims);
        otherRsaAlgorithm.sign(new RSASSASigner(first));
        try (var decoder = IdentityTokens.oidc(OIDC, transport, Duration.ofMinutes(5), Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> decoder.decode(sign(attacker, claims))).isInstanceOf(JwtException.class);
            assertThatThrownBy(() -> decoder.decode(hmac.serialize())).isInstanceOf(JwtException.class);
            assertThatThrownBy(() -> decoder.decode(otherRsaAlgorithm.serialize())).isInstanceOf(JwtException.class);
            assertThatThrownBy(() -> decoder.decode(new PlainJWT(claims).serialize())).isInstanceOf(JwtException.class);
        }
        server.verify();
    }

    @Test
    void newKeyIdRefreshesJwkSetWithoutRestart() throws Exception {
        var transport = new RestTemplate();
        var server = MockRestServiceServer.bindTo(transport).build();
        server.expect(requestTo(JWKS)).andRespond(withSuccess(jwks(first), MediaType.APPLICATION_JSON));
        server.expect(requestTo(JWKS)).andRespond(withSuccess(jwks(first, second), MediaType.APPLICATION_JSON));
        try (var decoder = IdentityTokens.oidc(OIDC, transport, Duration.ofMinutes(5), Duration.ofMillis(1))) {
            decoder.decode(sign(first, claims(c -> {
            })));
            assertThat(decoder.decode(sign(second, claims(c -> {
            }))).getSubject()).isNotBlank();
        }
        server.verify();
    }

    @Test
    void unknownKeyFloodHasBoundedRefreshes() throws Exception {
        var transport = new RestTemplate();
        var server = MockRestServiceServer.bindTo(transport).build();
        // Nimbus permits two refreshes per interval, including the initial load.
        server.expect(org.springframework.test.web.client.ExpectedCount.twice(), requestTo(JWKS))
                .andRespond(withSuccess(jwks(first), MediaType.APPLICATION_JSON));
        try (var decoder = IdentityTokens.oidc(OIDC, transport, Duration.ofMinutes(5), Duration.ofMinutes(1))) {
            decoder.decode(sign(first, claims(c -> {
            })));
            String unknown = sign(second, claims(c -> {
            }));
            for (int i = 0; i < 10; i++)
                assertThatThrownBy(() -> decoder.decode(unknown)).isInstanceOf(JwtException.class);
        }
        server.verify();
    }

    @Test
    void keyOutageRejectsUncachedKeysButPreservesFreshCachedKeys() throws Exception {
        var transport = new RestTemplate();
        var server = MockRestServiceServer.bindTo(transport).build();
        server.expect(requestTo(JWKS)).andRespond(withSuccess(jwks(first), MediaType.APPLICATION_JSON));
        server.expect(requestTo(JWKS)).andRespond(withServerError());
        try (var decoder = IdentityTokens.oidc(OIDC, transport, Duration.ofMinutes(5), Duration.ofMillis(1))) {
            String known = sign(first, claims(c -> {
            }));
            decoder.decode(known);
            assertThatThrownBy(() -> decoder.decode(sign(second, claims(c -> {
            })))).isInstanceOf(JwtException.class);
            assertThat(decoder.decode(known).getSubject()).isNotBlank();
        }
        server.verify();
    }

    @Test
    void expiredKeyCacheDoesNotUseStaleKeysDuringOutage() throws Exception {
        var transport = new RestTemplate();
        var server = MockRestServiceServer.bindTo(transport).build();
        server.expect(requestTo(JWKS)).andRespond(withSuccess(jwks(first), MediaType.APPLICATION_JSON));
        server.expect(requestTo(JWKS)).andRespond(withServerError());
        try (var decoder = IdentityTokens.oidc(OIDC, transport, Duration.ofMillis(20), Duration.ofMillis(1))) {
            String token = sign(first, claims(c -> {
            }));
            decoder.decode(token);
            Thread.sleep(50);
            assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
        }
        server.verify();
    }

    @Test
    void malformedAndOversizedKeyResponsesFailClosed() throws Exception {
        for (String body : List.of("not-json", " ".repeat(IdentityTokens.MAX_JWK_BYTES + 1))) {
            var transport = new RestTemplate();
            var server = MockRestServiceServer.bindTo(transport).build();
            server.expect(requestTo(JWKS)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            try (var decoder = IdentityTokens.oidc(OIDC, transport, Duration.ofMinutes(5), Duration.ofSeconds(1))) {
                assertThatThrownBy(() -> decoder.decode(sign(first, claims(c -> {
                })))).isInstanceOf(JwtException.class);
            }
            server.verify();
        }
    }

    @Test
    void insecureOrMixedProductionConfigurationCannotStart() {
        assertThatThrownBy(() -> IdentityTokens.create(DEV, SECRET, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdentityTokens.create(OIDC, SECRET, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdentityTokens.create(DEV, "short", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdentityTokens.create(new IdentityTokens.Settings(IdentityTokens.Mode.DEVELOPMENT, ISSUER, JWKS, "social-feed-api"), SECRET, false)).isInstanceOf(IllegalArgumentException.class);
        for (String uri : List.of("", "http://identity.example.test/keys", "https://name:password@identity.example.test/keys", "https://identity.example.test/keys#fragment")) {
            var bad = new IdentityTokens.Settings(IdentityTokens.Mode.OIDC, ISSUER, uri, "social-feed-api");
            assertThatThrownBy(() -> IdentityTokens.create(bad, "", true)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> IdentityTokens.create(new IdentityTokens.Settings(IdentityTokens.Mode.OIDC, ISSUER, JWKS, " "), "", true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdentityTokens.create(new IdentityTokens.Settings(IdentityTokens.Mode.OIDC, ISSUER + "?query=bad", JWKS, "social-feed-api"), "", true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void signingKeyErrorBodiesAreNotBufferedOrDecoded() throws Exception {
        var transport = new RestTemplate();
        var server = MockRestServiceServer.bindTo(transport).build();
        server.expect(requestTo(JWKS)).andRespond(request -> new org.springframework.mock.http.client.MockClientHttpResponse(
                new byte[0], org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE) {
            @Override
            public java.io.InputStream getBody() {
                throw new AssertionError("Remote error body must not be buffered");
            }

            @Override
            public void close() {
            }
        });
        try (var decoder = IdentityTokens.oidc(OIDC, transport, Duration.ofMinutes(5), Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> decoder.decode(sign(first, claims(c -> {
            })))).isInstanceOf(JwtException.class);
        }
        server.verify();
    }

    @Test
    void productionDecoderDoesNotFetchKeysAtStartup() throws Exception {
        try (var decoder = (IdentityTokens.JwkDecoder) IdentityTokens.create(OIDC, "", true)) {
            assertThat(decoder).isNotNull(); // example.test is intentionally not contacted here.
        }
    }

    @Test
    void developmentTokensStillRequireTrustedClaimsAndSecret() throws Exception {
        var decoder = IdentityTokens.create(DEV, SECRET, false);
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims(c -> c.issuer("social-feed")));
        token.sign(new MACSigner(SECRET));
        assertThat(decoder.decode(token.serialize()).getSubject()).isNotBlank();
        var bad = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims(c -> {
        }));
        bad.sign(new MACSigner(SECRET));
        assertThatThrownBy(() -> decoder.decode(bad.serialize())).isInstanceOf(JwtException.class);
    }
}
