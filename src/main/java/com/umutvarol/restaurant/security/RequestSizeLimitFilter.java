package com.umutvarol.restaurant.security;

import com.umutvarol.restaurant.common.error.PayloadTooLargeException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Caps API request bodies (OWASP REST Security: "define an appropriate request size limit and reject requests
 * exceeding the limit with 413"). A declared {@code Content-Length} over the limit is refused before anything is
 * read; a body without one (chunked) is counted while it is read and cut off at the limit.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final HandlerExceptionResolver resolver;

    RequestSizeLimitFilter(AbuseProtectionProperties properties,
                           @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.maxBytes = properties.maxRequestBody().toBytes();
        this.resolver = resolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            resolver.resolveException(request, response, null, new PayloadTooLargeException(maxBytes));
            return;
        }
        chain.doFilter(new LimitedRequest(request, maxBytes), response);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long maxBytes;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedInputStream(super.getInputStream(), maxBytes);
            }
            return stream;
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long read;

        LimitedInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = delegate.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws PayloadTooLargeException {
            read += n;
            if (read > maxBytes) {
                throw new PayloadTooLargeException(maxBytes);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
