package dev.demo.traffic.adapter.in.web;

import dev.demo.traffic.config.DemoProperties;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfiguration {
    private static final int MIN_TOKEN_LENGTH = 12;

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, DemoProperties props) {
        String token = props.adminToken();
        if (token == null || token.length() < MIN_TOKEN_LENGTH)
            throw new IllegalStateException("DEMO_ADMIN_TOKEN must be set (at least " + MIN_TOKEN_LENGTH + " characters)");
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .addFilterBefore(new AdminTokenFilter(token), BasicAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/v1/shop", "/api/v1/system/health").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/health", "/actuator/prometheus").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> problem(res, 401, "Unauthorized", "Admin token required"))
                        .accessDeniedHandler((req, res, ex) -> problem(res, 403, "Forbidden", "Access denied")));
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(DemoProperties props) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.cors().allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "Authorization", "X-Demo-Admin", "X-Request-Id"));
        cors.setExposedHeaders(List.of("X-Request-Id"));
        cors.setMaxAge(600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    @Bean
    ShopBackend shopBackend(dev.demo.traffic.application.port.out.TrafficMetricsPort traffic) {
        return new ShopBackend(traffic);
    }

    private static void problem(jakarta.servlet.http.HttpServletResponse res, int status, String title, String detail)
            throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"detail\":\"" + detail + "\"}");
    }
}
