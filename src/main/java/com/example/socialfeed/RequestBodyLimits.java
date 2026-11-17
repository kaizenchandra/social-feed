package com.example.socialfeed;

import java.io.*;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binary uploads go directly to S3. API JSON is bounded even for chunked requests.
 */
final class RequestBodyLimits extends OncePerRequestFilter {
    static final int MAX_BYTES = 1024 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/api/") && !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (request.getContentLengthLong() > MAX_BYTES) {
            reject(response);
            return;
        }
        byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
            reject(response);
            return;
        }
        var input = new ByteArrayInputStream(bytes);
        var stream = new ServletInputStream() {
            public int read() {
                return input.read();
            }

            public int read(byte[] b, int off, int len) {
                return input.read(b, off, len);
            }

            public boolean isFinished() {
                return input.available() == 0;
            }

            public boolean isReady() {
                return true;
            }

            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("Synchronous JSON requests only");
            }
        };
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() {
                return stream;
            }

            @Override
            public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(stream, getCharacterEncoding() == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(getCharacterEncoding())));
            }
        }, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"status\":413,\"title\":\"API request body exceeds 1 MiB\"}");
    }
}
