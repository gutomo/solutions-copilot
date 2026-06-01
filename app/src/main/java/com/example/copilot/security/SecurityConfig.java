package com.example.copilot.security;

import java.time.Duration;

import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Phase 4 slice 4: API-key authentication + per-key rate limiting.
 *
 * <p>Stateless token API: no sessions, CSRF off (no cookies), no form-login and
 * no HTTP-basic, and {@code UserDetailsServiceAutoConfiguration} is excluded
 * (see application.yml) so Boot generates no default password user.
 *
 * <p>Filter order inside the security chain:
 * {@link ApiKeyAuthFilter} (sets the principal) -> {@link AuthorizationFilter}
 * (401 for unauthenticated protected paths) -> {@link RateLimitFilter} (429 for
 * over-limit, runs only for already-authenticated requests). Both rejections
 * short-circuit BEFORE the cost filter and controller, so a 401/429 makes no
 * model call and produces no cost line -- while still being recorded as an
 * http.server.requests span by the outer observation filter.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    /**
     * One config shared by all per-principal limiters. {@code timeoutDuration=0}
     * means a request without an immediately-available permit is rejected (429)
     * rather than blocked.
     */
    @Bean
    public RateLimiterRegistry rateLimiterRegistry(SecurityProperties props) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(props.rateLimit().limitForPeriod())
                .limitRefreshPeriod(props.rateLimit().limitRefreshPeriod())
                .timeoutDuration(Duration.ZERO)
                .build();
        return RateLimiterRegistry.of(config);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   ApiKeyValidator validator,
                                                   SecurityProperties props,
                                                   RateLimiterRegistry rateLimiterRegistry) throws Exception {
        AuthenticationEntryPoint entryPoint = new ApiKeyAuthenticationEntryPoint();
        long fallbackRetryAfter = Math.max(1L, props.rateLimit().limitRefreshPeriod().toSeconds());

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // ERROR: let Boot render real error pages instead of 401'ing the forward.
                        // ASYNC: the SSE endpoint (/api/chat/stream) re-dispatches ASYNC on
                        // completion; that dispatch is an internal continuation of a request
                        // already authorized on its initial REQUEST dispatch (not attacker-
                        // reachable), so permit it -- otherwise authorization re-denies it after
                        // the 200 SSE response has committed, surfacing as a spurious /error 500.
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                        // ALB/ECS health probe is unauthenticated -- MUST stay public, ordered first.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Everything else is locked: the API and the rest of actuator
                        // (info, metrics, circuitbreakers, circuitbreakerevents).
                        .requestMatchers("/api/**").authenticated()
                        .requestMatchers("/actuator/**").authenticated()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                // Custom filters are constructed (not beans) so Boot does not also
                // auto-register them as top-level servlet filters (double execution).
                .addFilterBefore(new ApiKeyAuthFilter(validator), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new RateLimitFilter(rateLimiterRegistry, fallbackRetryAfter), AuthorizationFilter.class);

        return http.build();
    }
}
