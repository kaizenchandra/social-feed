package com.example.socialfeed;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.assertj.core.api.Assertions.*;

class IdentityConfigurationTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(DecoderConfiguration.class)
                .withPropertyValues("feed.jwt-secret=", "feed.auth.mode=oidc", "feed.auth.issuer=" + IdentityTokensTest.ISSUER,
                        "feed.auth.jwk-set-uri=" + IdentityTokensTest.JWKS, "feed.auth.audience=social-feed-api");
    }

    @Test
    void productionBindsOidcWithoutSigningSecretAndWithoutContactingIssuer() {
        runner().withInitializer(c -> c.getEnvironment().setActiveProfiles("production"))
                .run(c -> {
                    assertThat(c).hasNotFailed().hasSingleBean(JwtDecoder.class);
                    assertThat(c.getBean(JwtDecoder.class)).isInstanceOf(IdentityTokens.JwkDecoder.class);
                });
    }

    @Test
    void productionCannotOverrideModeToDevelopment() {
        runner().withInitializer(c -> c.getEnvironment().setActiveProfiles("production"))
                .withPropertyValues("feed.auth.mode=development", "feed.jwt-secret=" + IdentityTokensTest.SECRET, "feed.auth.issuer=", "feed.auth.jwk-set-uri=")
                .run(c -> assertThat(c).hasFailed().getFailure().hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .hasStackTraceContaining("production profile requires OIDC"));
    }

    @Test
    void missingOrUnknownOidcConfigurationCannotFallBack() {
        runner().withPropertyValues("feed.auth.jwk-set-uri=").run(c -> assertThat(c).hasFailed());
        runner().withPropertyValues("feed.auth.mode=misspelled").run(c -> assertThat(c).hasFailed());
        runner().withPropertyValues("feed.jwt-secret=" + IdentityTokensTest.SECRET).run(c -> assertThat(c).hasFailed());
    }

    @Test
    void explicitlyConfiguredDevelopmentModeRemainsAvailableLocally() {
        runner().withPropertyValues("feed.auth.mode=development", "feed.auth.issuer=", "feed.auth.jwk-set-uri=", "feed.jwt-secret=" + IdentityTokensTest.SECRET)
                .run(c -> assertThat(c).hasNotFailed().hasSingleBean(JwtDecoder.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IdentityTokens.Settings.class)
    static class DecoderConfiguration {
        @Bean
        JwtDecoder decoder(IdentityTokens.Settings settings, @Value("${feed.jwt-secret:}") String secret, Environment environment) {
            return new Security().decoder(secret, settings, environment);
        }
    }
}
