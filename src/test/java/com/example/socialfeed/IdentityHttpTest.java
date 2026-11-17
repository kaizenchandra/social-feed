package com.example.socialfeed;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Actual RSA bearer tokens traverse the production filter chain; quota storage is isolated.
 */
@WebMvcTest(controllers = IdentityHttpTest.Probe.class, properties = {"feed.jwt-secret=local-test-secret-at-least-32-bytes", "feed.auth.mode=development"})
@Import({Security.class, IdentityHttpTest.Keys.class, IdentityHttpTest.Probe.class})
class IdentityHttpTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    RSAKey key;
    @Autowired
    MockRestServiceServer jwks;
    @MockitoBean
    StringRedisTemplate redis;

    @Test
    void realBearerTokensEnforceIssuerAlgorithmAndMetricsScope() throws Exception {
        jwks.expect(requestTo(IdentityTokensTest.JWKS)).andRespond(withSuccess(IdentityTokensTest.jwks(key), MediaType.APPLICATION_JSON));
        jwks.expect(requestTo(IdentityTokensTest.JWKS)).andRespond(withServerError());
        String ordinary = IdentityTokensTest.sign(key, IdentityTokensTest.claims(c -> {
        }));
        mvc.perform(get("/api/v1/identity-probe")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/identity-probe").header("Authorization", "Bearer " + ordinary))
                .andExpect(status().isOk()).andExpect(content().string("authenticated"));
        String wrongIssuer = IdentityTokensTest.sign(key, IdentityTokensTest.claims(c -> c.issuer("https://wrong.example.test")));
        mvc.perform(get("/api/v1/identity-probe").header("Authorization", "Bearer " + wrongIssuer)).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + ordinary)).andExpect(status().isForbidden());
        String metrics = IdentityTokensTest.sign(key, IdentityTokensTest.claims(c -> c.claim("scope", "metrics")));
        mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + metrics)).andExpect(status().isOk());
        var development = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), IdentityTokensTest.claims(c -> c.issuer("social-feed")));
        development.sign(new MACSigner(IdentityTokensTest.SECRET));
        mvc.perform(get("/api/v1/identity-probe").header("Authorization", "Bearer " + development.serialize())).andExpect(status().isUnauthorized());
        var rotated = new RSAKeyGenerator(2048).keyID("uncached-http-key").generate();
        String unavailable = IdentityTokensTest.sign(rotated, IdentityTokensTest.claims(c -> {
        }));
        mvc.perform(get("/api/v1/identity-probe").header("Authorization", "Bearer " + unavailable))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "1"));
        jwks.verify();
    }

    @RestController
    static class Probe {
        @GetMapping("/api/v1/identity-probe")
        String identity() {
            return "authenticated";
        }

        @GetMapping("/actuator/prometheus")
        String metrics() {
            return "metrics";
        }
    }

    @TestConfiguration
    static class Keys {
        @Bean
        RSAKey rsaKey() throws Exception {
            return new RSAKeyGenerator(2048).keyID("http-key").generate();
        }

        @Bean
        RestTemplate jwksTransport() {
            return new RestTemplate();
        }

        @Bean
        MockRestServiceServer jwksServer(RestTemplate transport) {
            return MockRestServiceServer.bindTo(transport).build();
        }

        @Bean
        @Primary
        JwtDecoder oidcDecoder(RestTemplate transport) {
            return IdentityTokens.oidc(IdentityTokensTest.OIDC, transport, IdentityTokens.KEY_TTL, IdentityTokens.REFRESH_INTERVAL);
        }
    }
}
