package com.example.socialfeed;

import java.io.IOException;
import java.time.Duration;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Authenticated quota: Redis errors fail closed so an outage cannot disable abuse controls.
 */
public class RequestLimits extends OncePerRequestFilter {
    static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],2) end; return n", Long.class);
    final StringRedisTemplate redis;

    RequestLimits(StringRedisTemplate r) {
        redis = r;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (req.getRequestURI().startsWith("/api/") && auth instanceof JwtAuthenticationToken j) {
            try {
                Long n = redis.execute(SCRIPT, java.util.List.of("quota:" + j.getName() + ":" + (System.currentTimeMillis() / 1000)));
                if (n != null && n > 100) {
                    reject(res, 429, "Rate limit exceeded");
                    return;
                }
            } catch (org.springframework.dao.DataAccessException e) {
                // Read availability takes priority; mutations fail closed under quota-store outage.
                if (!req.getMethod().equals("GET")) {
                    reject(res, 503, "Quota store unavailable");
                    return;
                }
            }
        }
        chain.doFilter(req, res);
    }

    void reject(HttpServletResponse r, int status, String title) throws IOException {
        r.setStatus(status);
        r.setHeader("Retry-After", "1");
        r.setContentType("application/problem+json");
        r.getWriter().write("{\"status\":" + status + ",\"title\":\"" + title + "\"}");
    }
}
