package com.example.socialfeed;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.*;
import com.nimbusds.jose.jwk.source.*;
import com.nimbusds.jose.proc.*;
import com.nimbusds.jose.util.Resource;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.http.client.ClientHttpResponse;

final class IdentityTokens {
    static final Duration KEY_TTL = Duration.ofMinutes(5);
    static final Duration REFRESH_INTERVAL = Duration.ofSeconds(1);
    static final int MAX_JWK_BYTES = 64 * 1024;
    private IdentityTokens() {
    }

    static JwtDecoder create(Settings settings, String secret, boolean production) {
        validateSettings(settings, secret, production);
        if (settings.mode() == Mode.DEVELOPMENT) {
            var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                    .macAlgorithm(MacAlgorithm.HS256).build();
            decoder.setJwtValidator(validator("social-feed", "social-feed-api"));
            return decoder;
        }
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(2));
        return oidc(settings, new RestTemplate(factory), KEY_TTL, REFRESH_INTERVAL, client);
    }

    static void validateSettings(Settings settings, String secret, boolean production) {
        if (settings.mode() == null) throw new IllegalArgumentException("Authentication mode is required");
        if (production && settings.mode() != Mode.OIDC)
            throw new IllegalArgumentException("The production profile requires OIDC authentication");
        if (settings.mode() == Mode.DEVELOPMENT) {
            if (secret.getBytes(StandardCharsets.UTF_8).length < 32)
                throw new IllegalArgumentException("JWT_SECRET must contain at least 32 bytes in development mode");
            if (hasText(settings.issuer()) || hasText(settings.jwkSetUri()))
                throw new IllegalArgumentException("OIDC settings cannot be combined with development authentication");
            if (!"social-feed-api".equals(settings.audience()))
                throw new IllegalArgumentException("Development audience is fixed to social-feed-api");
        } else {
            if (hasText(secret)) throw new IllegalArgumentException("Remove JWT_SECRET when using OIDC authentication");
            requireHttps(settings.issuer(), true);
            requireHttps(settings.jwkSetUri(), false);
            if (!hasText(settings.audience()) || !settings.audience().equals(settings.audience().trim()))
                throw new IllegalArgumentException("A nonblank OIDC audience is required");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void requireHttps(String raw, boolean issuer) {
        if (!hasText(raw)) throw new IllegalArgumentException("OIDC issuer and JWK set URI are required");
        URI uri = URI.create(raw);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null || (issuer && uri.getRawQuery() != null))
            throw new IllegalArgumentException("OIDC endpoints must use HTTPS without credentials or fragments");
    }

    static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        return JwtValidators.createDefaultWithValidators(new JwtTimestampValidator(Duration.ofSeconds(30)),
                new JwtIssuerValidator(issuer), jwt -> {
                    try {
                        String subject = jwt.getSubject();
                        if (!UUID.fromString(subject).toString().equals(subject) || jwt.getExpiresAt() == null
                                || !jwt.getAudience().contains(audience)) throw new IllegalArgumentException();
                        return OAuth2TokenValidatorResult.success();
                    } catch (RuntimeException e) {
                        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid identity claims", null));
                    }
                });
    }

    // Package-visible transport/TTL seam permits deterministic tests without a public HTTP bypass.
    static JwkDecoder oidc(Settings settings, RestTemplate transport, Duration ttl, Duration interval) {
        validateSettings(settings, "", false);
        return oidc(settings, transport, ttl, interval, null);
    }

    private static JwkDecoder oidc(Settings settings, RestTemplate transport, Duration ttl, Duration interval, HttpClient client) {
        // The default error handler buffers remote error bodies before our size cap can run.
        // Check status inside the bounded extractor instead, without consuming error bodies.
        transport.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }

            @Override
            public void handleError(ClientHttpResponse response) {
            }
        });
        try {
            var source = JWKSourceBuilder.<SecurityContext>create(URI.create(settings.jwkSetUri()).toURL(), url -> {
                        try {
                            return transport.execute(URI.create(url.toExternalForm()), HttpMethod.GET,
                                    request -> request.getHeaders().setAccept(List.of(MediaType.APPLICATION_JSON)), response -> {
                                        if (!response.getStatusCode().is2xxSuccessful())
                                            throw new IOException("Signing key endpoint rejected request");
                                        byte[] bytes = response.getBody().readNBytes(MAX_JWK_BYTES + 1);
                                        if (bytes.length > MAX_JWK_BYTES)
                                            throw new IOException("Signing key response exceeds size limit");
                                        return new Resource(new String(bytes, StandardCharsets.UTF_8), "application/json");
                                    });
                        } catch (RestClientException e) {
                            throw new IOException("Unable to retrieve signing keys", e);
                        }
                    }).cache(ttl.toMillis(), 2000).refreshAheadCache(false).rateLimited(interval.toMillis())
                    .retrying(false).outageTolerant(false).build();
            var processor = new DefaultJWTProcessor<SecurityContext>();
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, source));
            processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT, new JOSEObjectType("at+jwt"), null));
            // Timestamp/issuer/audience/subject policy is enforced by Spring below.
            processor.setJWTClaimsSetVerifier((claims, context) -> {
            });
            var decoder = new NimbusJwtDecoder(processor);
            decoder.setJwtValidator(validator(settings.issuer(), settings.audience()));
            return new JwkDecoder(decoder, source, client);
        } catch (java.net.MalformedURLException e) {
            throw new IllegalArgumentException("Invalid JWK endpoint", e);
        }
    }

    enum Mode {DEVELOPMENT, OIDC}

    @ConfigurationProperties("feed.auth")
    record Settings(Mode mode, String issuer, String jwkSetUri, String audience) {
    }

    static final class JwkDecoder implements JwtDecoder, Closeable {
        private final JwtDecoder delegate;
        private final JWKSource<SecurityContext> keys;
        private final HttpClient client;

        JwkDecoder(JwtDecoder delegate, JWKSource<SecurityContext> keys, HttpClient client) {
            this.delegate = delegate;
            this.keys = keys;
            this.client = client;
        }

        @Override
        public Jwt decode(String token) {
            return delegate.decode(token);
        }

        @Override
        public void close() throws IOException {
            try {
                if (keys instanceof Closeable closeable) closeable.close();
            } finally {
                if (client != null) client.close();
            }
        }
    }
}
