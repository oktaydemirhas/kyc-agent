package dev.oktay.kyc.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses oversized or length-less POST bodies before any JSON is parsed. Four documents of 4000 characters
 * fit comfortably under the cap even with every character escaped.
 */
@Component
public class RequestSizeFilter extends OncePerRequestFilter {
    static final long MAX_BYTES = 128 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        long length = req.getContentLengthLong();
        if (length < 0) {
            reject(res, 411, "length_required");
        } else if (length > MAX_BYTES) {
            reject(res, 413, "payload_too_large");
        } else {
            chain.doFilter(req, res);
        }
    }

    private static void reject(HttpServletResponse res, int status, String code) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getOutputStream().write(("{\"error\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8));
    }
}
