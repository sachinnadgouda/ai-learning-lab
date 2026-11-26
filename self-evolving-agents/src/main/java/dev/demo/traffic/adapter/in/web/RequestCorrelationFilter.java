package dev.demo.traffic.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Puts requestId and traceId into the MDC for every request. Incoming ids are accepted only if well-formed. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID = "requestId";
    public static final String TRACE_ID = "traceId";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{8,64}");
    private static final Pattern TRACEPARENT = Pattern.compile("00-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || !SAFE.matcher(requestId).matches()) requestId = UUID.randomUUID().toString();
        String traceId = requestId;
        String traceparent = request.getHeader("traceparent");
        if (traceparent != null) {
            var m = TRACEPARENT.matcher(traceparent);
            if (m.matches()) traceId = m.group(1);
        }
        MDC.put(REQUEST_ID, requestId);
        MDC.put(TRACE_ID, traceId);
        response.setHeader("X-Request-Id", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(REQUEST_ID);
            MDC.remove(TRACE_ID);
        }
    }
}
