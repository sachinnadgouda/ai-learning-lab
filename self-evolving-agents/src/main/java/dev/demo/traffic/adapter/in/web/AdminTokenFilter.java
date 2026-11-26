package dev.demo.traffic.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates the single administrator from {@code X-Demo-Admin} or {@code Authorization: Bearer}. */
public class AdminTokenFilter extends OncePerRequestFilter {
    private final byte[] expected;

    public AdminTokenFilter(String adminToken) {
        this.expected = adminToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader("X-Demo-Admin");
        if (token == null) {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) token = auth.substring(7).trim();
        }
        if (token != null && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), expected)) {
            var authentication = UsernamePasswordAuthenticationToken.authenticated("admin", null,
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request, response);
    }
}
