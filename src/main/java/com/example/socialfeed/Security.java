package com.example.socialfeed;

import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

@Configuration
@EnableConfigurationProperties(IdentityTokens.Settings.class)
class Security {
    @Bean
    JwtDecoder decoder(@Value("${feed.jwt-secret:}") String secret, IdentityTokens.Settings settings, Environment environment) {
        return IdentityTokens.create(settings, secret, environment.acceptsProfiles(Profiles.of("production")));
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity http, org.springframework.data.redis.core.StringRedisTemplate redis) throws Exception {
        var entryPoint = new BearerTokenAuthenticationEntryPoint();
        return http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health/**").permitAll().requestMatchers("/actuator/prometheus").hasAuthority("SCOPE_metrics").anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> {
                        }).authenticationEntryPoint(entryPoint)
                        .withObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                            @Override
                            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                                filter.setAuthenticationFailureHandler((request, response, failure) -> {
                                    if (failure instanceof AuthenticationServiceException) {
                                        response.setStatus(503);
                                        response.setHeader("Retry-After", "1");
                                        response.setContentType("application/problem+json");
                                        response.getWriter().write("{\"status\":503,\"title\":\"Identity verification temporarily unavailable\"}");
                                    } else entryPoint.commence(request, response, failure);
                                });
                                return filter;
                            }
                        }))
                .addFilterAfter(new RequestBodyLimits(), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new RequestLimits(redis), RequestBodyLimits.class).build();
    }
}
