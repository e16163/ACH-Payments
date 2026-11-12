package com.achpayments.common;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.csrf.CsrfException;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Demo login (docs/USE-CASES.md P1, T7): the app's own sign-in page (POST /api/login, POST /api/logout) for an
 * admin, the back-office logins (ROLE_ACH_ORIG_OPS, ROLE_ACH_RECV_OPS, ROLE_COMPLIANCE) and a member, whose passwords come from environment variables. HTTP Basic still works for
 * scripts and tests, but a 401 never sends the browser's login popup. Members also pass a simulated MFA step.
 * Real users come in step 16.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AccessDeniedHandler deniedHandler, AppLogRepository appLog,
            InMemoryUserDetailsManager users, FailedSignInLog failedSignIns) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health").permitAll()
                        .requestMatchers("/api/params/**", "/api/ach-business-rules/**", "/api/v1/admin/**").hasRole("SYS_ADMIN")
                        .requestMatchers("/api/eod/**").hasRole("ACH_ORIG_OPS")
                        .requestMatchers("/api/v1/ach/back-office/origination/**").hasRole("ACH_ORIG_OPS")
                        .requestMatchers("/api/v1/ach/back-office/receiving/**").hasRole("ACH_RECV_OPS")
                        // Ba's blueprint, ACH Origination Processing: compliance resolves OFAC hits, origination ops run the rest
                        .requestMatchers("/api/v1/compliance/**").hasRole("COMPLIANCE")
                        .requestMatchers("/api/v1/ach/origination/**", "/api/v1/ach/processing/**", "/api/v1/ach/limits/**",
                                "/api/v1/ach/transfers/**", "/api/v1/accounts/**").hasRole("ACH_ORIG_OPS")
                        // UC-ACH-RCV-02: ACH_RECV_OPS or SUPPORT. Only operations receive files.
                        .requestMatchers(HttpMethod.POST, "/api/v1/ach/receiving/**").hasRole("ACH_RECV_OPS")
                        .requestMatchers("/api/v1/ach/receiving/**").hasAnyRole("ACH_RECV_OPS", "SUPPORT")
                        .requestMatchers("/api/member/mfa/**").hasRole("MEMBER")
                        // UC-ACH-ORG-01 precondition: the member is logged in AND has passed MFA
                        .requestMatchers("/api/member/**", "/api/transfers/**", "/api/routing/**", "/api/v1/holders/**").access(MEMBER_WITH_MFA)
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll()) // the React app's static files
                .httpBasic(Customizer.withDefaults())
                .formLogin(form -> form.loginProcessingUrl("/api/login")
                        .successHandler((request, response, auth) -> {
                            appLog.log(systemOf(auth), "INFO", "User '%s' signed in".formatted(auth.getName()));
                            response.setStatus(204);
                        })
                        .failureHandler((request, response, e) -> {
                            // Only a real login name: someone may have typed their password in that box
                            String name = request.getParameter("username");
                            failedSignIns.record(name != null && users.userExists(name) ? name : "(unknown)");
                            json(response, 401, "Incorrect username or password.");
                        }))
                .logout(logout -> logout.logoutUrl("/api/logout")
                        .logoutSuccessHandler((request, response, auth) -> {
                            if (auth != null) { // null when there was no session
                                appLog.log(systemOf(auth), "INFO", "User '%s' signed out".formatted(auth.getName()));
                            }
                            response.setStatus(204);
                        }))
                // The browser resends Basic credentials automatically, so writes need a CSRF token:
                // the XSRF-TOKEN cookie, echoed back by the React app in the X-XSRF-TOKEN header.
                .csrf(csrf -> csrf.spa())
                // 401 as JSON without WWW-Authenticate, so the React sign-in page shows instead of a browser popup
                .exceptionHandling(ex -> ex.accessDeniedHandler(deniedHandler)
                        .authenticationEntryPoint((request, response, e) -> json(response, 401, "Please sign in.")));
        return http.build();
    }

    private static String systemOf(Authentication auth) {
        List<String> roles = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
        if (roles.contains("ROLE_ACH_RECV_OPS")) return AppLogRepository.ACH_RCV;
        if (roles.contains("ROLE_ACH_ORIG_OPS") || roles.contains("ROLE_MEMBER") || roles.contains("ROLE_COMPLIANCE")) return AppLogRepository.ACH_ORIG;
        return AppLogRepository.ACH_SYS;
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
                appLog.log(AppLogRepository.ACH_SYS, "WARN", "CSRF check failed: missing or invalid token on " + target);
            } else {
                String user = request.getUserPrincipal() == null ? "anonymous" : request.getUserPrincipal().getName();
                appLog.log(AppLogRepository.ACH_SYS, "WARN", "Access denied: user '%s' attempted %s".formatted(user, target));
            }
            json(response, 403, "You don't have permission to do this.");
        };
    }

    private static void json(HttpServletResponse response, int status, String error) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + error + "\"}");
    }

    @Bean
    FailedSignInLog failedSignInLog(AppLogRepository appLog) {
        return new FailedSignInLog(appLog, Clock.systemUTC());
    }

    @Bean
    InMemoryUserDetailsManager users(@Value("${app.admin-username}") String adminUsername,
            @Value("${app.admin-password}") String adminPassword,
            @Value("${app.orig-username}") String origUsername,
            @Value("${app.orig-password}") String origPassword,
            @Value("${app.recv-username}") String recvUsername,
            @Value("${app.recv-password}") String recvPassword,
            @Value("${app.comp-username}") String compUsername,
            @Value("${app.comp-password}") String compPassword,
            @Value("${app.member-username}") String memberUsername,
            @Value("${app.member-password}") String memberPassword) {
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return new InMemoryUserDetailsManager(
                User.withUsername(adminUsername).password(encoder.encode(adminPassword)).roles("SYS_ADMIN").build(),
                User.withUsername(origUsername).password(encoder.encode(origPassword)).roles("ACH_ORIG_OPS").build(),
                User.withUsername(recvUsername).password(encoder.encode(recvPassword)).roles("ACH_RECV_OPS").build(),
                User.withUsername(compUsername).password(encoder.encode(compPassword)).roles("COMPLIANCE").build(),
                User.withUsername(memberUsername).password(encoder.encode(memberPassword)).roles("MEMBER").build());
    }

    private static final AuthorizationManager<RequestAuthorizationContext> MEMBER_WITH_MFA = (authentication, context) -> {
        boolean member = authentication.get().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_MEMBER"));
        return new AuthorizationDecision(member && MfaService.isVerified(context.getRequest()));
    };
}
