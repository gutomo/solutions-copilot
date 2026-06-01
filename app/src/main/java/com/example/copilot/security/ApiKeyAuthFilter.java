package com.example.copilot.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the {@code X-API-Key} header and, on a match, sets an authenticated
 * principal whose name is the key's logical name (so per-key rate limiting and
 * logs reference the name, never the secret).
 *
 * <p>A missing, blank, whitespace-only, or unknown key sets NO authentication:
 * the request stays anonymous, and Spring Security's authorization layer
 * rejects it with 401 on any protected path (via
 * {@link ApiKeyAuthenticationEntryPoint}). This filter therefore never writes a
 * response or logs the supplied credential -- rejection (and its path-only log)
 * is centralised in the entry point.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    private static final List<GrantedAuthority> AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT"));

    private final ApiKeyValidator validator;

    public ApiKeyAuthFilter(ApiKeyValidator validator) {
        this.validator = validator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // nameFor(...) returns null for a blank/whitespace-only/unknown key, so
        // a blank credential can never authenticate (no empty-slot bypass).
        String name = validator.nameFor(request.getHeader(HEADER));
        if (name != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            var auth = new UsernamePasswordAuthenticationToken(name, null, AUTHORITIES);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        filterChain.doFilter(request, response);
    }
}
