package com.achpayments.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

/**
 * Demo login (docs/USE-CASES.md P1): browser HTTP Basic with two users whose passwords come from
 * environment variables. Real users and roles come in roadmap step 16.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AccessDeniedHandler deniedHandler) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health").permitAll()
                        .requestMatchers("/api/params/**").hasRole("SYS_ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll()) // the React app's static files
                .httpBasic(Customizer.withDefaults())
                // The browser resends Basic credentials automatically, so writes need a CSRF token:
                // the XSRF-TOKEN cookie, echoed back by the React app in the X-XSRF-TOKEN header.
                .csrf(csrf -> csrf.spa())
                .exceptionHandling(ex -> ex.accessDeniedHandler(deniedHandler));
        return http.build();
    }

    /**
     * UC-SYS-PARAM-01 EX-02: 403 plus a security warning in AppLog. A bad CSRF token is rejected before
     * login is checked, so it gets its own message instead of a misleading "user 'anonymous'".
     */
    @Bean
    AccessDeniedHandler accessDeniedHandler(AppLogRepository appLog) {
        return (request, response, e) -> {
            String target = request.getMethod() + " " + request.getRequestURI();
            if (e instanceof CsrfException) {
                appLog.log("WARN", "CSRF check failed: missing or invalid token on " + target, null);
            } else {
                String user = request.getUserPrincipal() == null ? "anonymous" : request.getUserPrincipal().getName();
                appLog.log("WARN", "Access denied: user '%s' attempted %s".formatted(user, target), null);
            }
            response.setStatus(403);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"You don't have permission to do this.\"}");
        };
    }

    @Bean
    UserDetailsService users(@Value("${app.admin-password}") String adminPassword,
            @Value("${app.operator-password}") String operatorPassword) {
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return new InMemoryUserDetailsManager(
                User.withUsername("admin").password(encoder.encode(adminPassword)).roles("SYS_ADMIN").build(),
                User.withUsername("operator").password(encoder.encode(operatorPassword)).roles("OPERATOR").build());
    }
}
