package com.umutvarol.restaurant.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.umutvarol.restaurant.common.error.PayloadTooLargeException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;
import org.springframework.web.servlet.HandlerExceptionResolver;

class RequestSizeLimitFilterTest {

    private final HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(new AbuseProtectionProperties(
            new AbuseProtectionProperties.Login(5, Duration.ofMinutes(15), Duration.ofSeconds(30), Duration.ofMinutes(15)),
            new AbuseProtectionProperties.RateLimit(true, 600, 60),
            DataSize.ofBytes(100)), resolver);

    @Test
    void aDeclaredLengthOverTheLimitIsRefusedBeforeReading() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        request.setContent(new byte[101]);

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            throw new AssertionError("the request must not reach the application");
        });

        verify(resolver).resolveException(any(), any(), isNull(), any(PayloadTooLargeException.class));
    }

    @Test
    void aBodyWithoutALengthIsCutOffAtTheLimit() throws Exception {
        MockHttpServletRequest request = new UnknownLengthRequest(new byte[500]);

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                assertThatThrownBy(() -> req.getInputStream().readAllBytes()).isInstanceOf(PayloadTooLargeException.class));
        verifyNoInteractions(resolver);
    }

    @Test
    void bodiesWithinTheLimitPassThroughUntouched() throws Exception {
        MockHttpServletRequest request = new UnknownLengthRequest("{\"ok\":true}".getBytes());

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                assertThat(new String(req.getInputStream().readAllBytes())).isEqualTo("{\"ok\":true}"));
    }

    @Test
    void nonApiRequestsAreNotFiltered() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/index.html");
        request.setContent(new byte[500]);
        boolean[] reached = {false};

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> reached[0] = true);

        assertThat(reached[0]).isTrue();
    }

    /** Like a chunked upload: the length is not known until the body has been read. */
    private static final class UnknownLengthRequest extends MockHttpServletRequest {
        UnknownLengthRequest(byte[] body) {
            super("POST", "/api/v1/orders/quote");
            setContent(body);
        }

        @Override
        public int getContentLength() {
            return -1;
        }

        @Override
        public long getContentLengthLong() {
            return -1;
        }
    }
}
