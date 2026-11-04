package com.achpayments.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.csrf.CsrfException;

/**
 * Demo login (docs/USE-CASES.md P1, T7): browser HTTP Basic with an admin, an operator and a member, whose
 * passwords come from environment variables. Members also pass a simulated MFA step. Real users come in step 16.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AccessDeniedHandler deniedHandler) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health").permitAll()
                        .requestMatchers("/api/params/**").hasRole("SYS_ADMIN")
                        .requestMatchers("/api/member/mfa/**").hasRole("MEMBER")
                        // UC-ACH-ORG-01 precondition: the member is logged in AND has passed MFA
                        .requestMatchers("/api/member/**", "/api/transfers/**", "/api/routing/**").access(MEMBER_WITH_MFA)
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
    UserDetailsService users(@Value("${app.admin-username}") String adminUsername,
            @Value("${app.admin-password}") String adminPassword,
            @Value("${app.operator-password}") String operatorPassword,
            @Value("${app.member-username}") String memberUsername,
            @Value("${app.member-password}") String memberPassword) {
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return new InMemoryUserDetailsManager(
                User.withUsername(adminUsername).password(encoder.encode(adminPassword)).roles("SYS_ADMIN").build(),
                User.withUsername("operator").password(encoder.encode(operatorPassword)).roles("OPERATOR").build(),
                User.withUsername(memberUsername).password(encoder.encode(memberPassword)).roles("MEMBER").build());
    }

    private static final AuthorizationManager<RequestAuthorizationContext> MEMBER_WITH_MFA = (authentication, context) -> {
        boolean member = authentication.get().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_MEMBER"));
        return new AuthorizationDecision(member && MfaService.isVerified(context.getRequest()));
    };
}
