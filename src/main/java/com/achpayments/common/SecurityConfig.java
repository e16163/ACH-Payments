package com.achpayments.common;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.ldap.DefaultSpringSecurityContextSource;
import org.springframework.security.ldap.SpringSecurityLdapTemplate;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.security.ldap.userdetails.InetOrgPersonContextMapper;
import org.springframework.security.ldap.userdetails.LdapAuthoritiesPopulator;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.csrf.CsrfException;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Sign-in (docs/LDAP-PLAN.md): users, roles and permissions come from the LDAP server, with the env-var demo logins
 * (docs/USE-CASES.md P1, T7) as a fallback. Each URL is checked against one of Ba's permissions, not a role.
 * The app's own sign-in page posts to /api/login and /api/logout. HTTP Basic still works for scripts and tests,
 * but a 401 never sends the browser's login popup. Members also pass a simulated MFA step.
 */
@Configuration
public class SecurityConfig {

    /** Ba's roles and their permissions, as in ldap/seed.ldif, for the fallback logins. LdapLoginTest checks they match. */
    static final Map<String, List<String>> PERMISSIONS = Map.of(
            "MEMBER", List.of("ACH_MEMBER_UI"),
            "ACH_ORIG", List.of("ACH_ORIG_Reversal_UI", "ACH_ORIG_Return_UI", "ACH_ORIG_Resubmision_UI", "ACH_ORIG_PROCESS"),
            "ACH_RECV", List.of("ACH_RECV_Reversal_UI", "ACH_RECV_Return_UI", "ACH_RECV_Resubmision_UI", "ACH_RECV_PROCESS"),
            "ADMIN", List.of("ACH_BUSINESS_RULES", "SYSTEM_LOGS", "SYSTEM_CONFIG", "OPEN_BANKING_API", "ACH_PAYMENTS_API"),
            "COMPLIANCE", List.of("OFAC_REVIEW_UI"));

    private static final String ORIG_BO = "/api/v1/ach/back-office/origination/";
    private static final String RECV_BO = "/api/v1/ach/back-office/receiving/";

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AccessDeniedHandler deniedHandler, AppLogRepository appLog,
            AuthenticationManager authentication, InMemoryUserDetailsManager users, LdapContextSource ldap,
            FailedSignInLog failedSignIns) throws Exception {
        http
                .authenticationManager(authentication)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health").permitAll()
                        .requestMatchers("/api/params/**").hasAuthority("SYSTEM_CONFIG")
                        .requestMatchers("/api/ach-business-rules/**").hasAuthority("ACH_BUSINESS_RULES")
                        .requestMatchers("/api/v1/admin/logs/**").hasAuthority("SYSTEM_LOGS")
                        .requestMatchers("/api/eod/**").hasAuthority("ACH_ORIG_PROCESS")
                        .requestMatchers(ORIG_BO + "returns", ORIG_BO + "return-actions").hasAuthority("ACH_ORIG_Return_UI")
                        .requestMatchers(ORIG_BO + "reversals").hasAuthority("ACH_ORIG_Reversal_UI")
                        .requestMatchers(ORIG_BO + "resubmissions").hasAuthority("ACH_ORIG_Resubmision_UI")
                        .requestMatchers(ORIG_BO + "**").hasAuthority("ACH_ORIG_PROCESS") // the NOC screen
                        .requestMatchers(RECV_BO + "returns", RECV_BO + "return-requests").hasAuthority("ACH_RECV_Return_UI")
                        .requestMatchers(RECV_BO + "reversals").hasAuthority("ACH_RECV_Reversal_UI")
                        .requestMatchers(RECV_BO + "**").hasAuthority("ACH_RECV_PROCESS") // the NOC screen
                        // Ba's blueprint, ACH Origination Processing: compliance resolves OFAC hits, origination ops run the rest
                        .requestMatchers("/api/v1/compliance/**").hasAuthority("OFAC_REVIEW_UI")
                        .requestMatchers("/api/v1/ach/origination/**", "/api/v1/ach/processing/**", "/api/v1/ach/limits/**",
                                "/api/v1/ach/transfers/**", "/api/v1/accounts/**").hasAuthority("ACH_ORIG_PROCESS")
                        .requestMatchers("/api/v1/ach/receiving/**").hasAuthority("ACH_RECV_PROCESS")
                        .requestMatchers("/api/member/mfa/**").hasAuthority("ACH_MEMBER_UI")
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
                            failedSignIns.record(name != null && (users.userExists(name) || ldapUserExists(ldap, name)) ? name : "(unknown)");
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
        if (roles.contains("ROLE_ACH_RECV")) return AppLogRepository.ACH_RCV;
        if (roles.contains("ROLE_ACH_ORIG") || roles.contains("ROLE_MEMBER") || roles.contains("ROLE_COMPLIANCE")) return AppLogRepository.ACH_ORIG;
        return AppLogRepository.ACH_SYS;
    }

    private static boolean ldapUserExists(LdapContextSource ldap, String name) {
        try {
            new FilterBasedLdapUserSearch("ou=people", "(uid={0})", ldap).searchForUser(name);
            return true;
        } catch (RuntimeException e) { // not found, or LDAP is down: log the attempt without the name
            return false;
        }
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
        return new InMemoryUserDetailsManager(
                fallbackUser(adminUsername, adminPassword, "ADMIN"),
                fallbackUser(origUsername, origPassword, "ACH_ORIG"),
                fallbackUser(recvUsername, recvPassword, "ACH_RECV"),
                fallbackUser(compUsername, compPassword, "COMPLIANCE"),
                fallbackUser(memberUsername, memberPassword, "MEMBER"));
    }

    private static UserDetails fallbackUser(String name, String password, String role) {
        List<String> authorities = new ArrayList<>(PERMISSIONS.get(role));
        authorities.add("ROLE_" + role);
        return User.withUsername(name).password(ENCODER.encode(password)).authorities(authorities.toArray(String[]::new)).build();
    }

    private static final PasswordEncoder ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    /** The LDAP server and base DN, e.g. ldap://localhost:1389/dc=achpayments,dc=local (ldap/setup.sh). */
    @Bean
    DefaultSpringSecurityContextSource ldap(@Value("${app.ldap.url}") String url) {
        return new DefaultSpringSecurityContextSource(url);
    }

    /**
     * The fallback logins first: a name they don't know (or a wrong password) moves on to LDAP. Checking LDAP
     * first would lock everyone out while LDAP is down, since a connection error ends the sign-in.
     */
    @Bean
    AuthenticationManager authenticationManager(InMemoryUserDetailsManager users, DefaultSpringSecurityContextSource ldap) {
        DaoAuthenticationProvider fallback = new DaoAuthenticationProvider(users);
        fallback.setPasswordEncoder(ENCODER);
        BindAuthenticator bind = new BindAuthenticator(ldap);
        bind.setUserDnPatterns(new String[] {"uid={0},ou=people"});
        LdapAuthenticationProvider directory = new LdapAuthenticationProvider(bind, rolesAndPermissions(ldap));
        directory.setUserDetailsContextMapper(new InetOrgPersonContextMapper()); // keeps employeeNumber, the member number
        return new ProviderManager(fallback, directory);
    }

    /** The user's role groups (ROLE_x), then each role's permission groups. A user may have several roles. */
    private static LdapAuthoritiesPopulator rolesAndPermissions(DefaultSpringSecurityContextSource ldap) {
        SpringSecurityLdapTemplate template = new SpringSecurityLdapTemplate(ldap);
        return (user, username) -> {
            List<GrantedAuthority> authorities = new ArrayList<>();
            for (Map<String, List<String>> role : template.searchForMultipleAttributeValues("ou=roles", "(member={0})",
                    new Object[] {user.getNameInNamespace()}, new String[] {"cn"})) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role.get("cn").get(0)));
                template.searchForSingleAttributeValues("ou=permissions", "(member={0})",
                        new Object[] {role.get(SpringSecurityLdapTemplate.DN_KEY).get(0)}, "cn")
                        .forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));
            }
            return authorities;
        };
    }

    private static final AuthorizationManager<RequestAuthorizationContext> MEMBER_WITH_MFA = (authentication, context) -> {
        boolean member = authentication.get().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ACH_MEMBER_UI"));
        return new AuthorizationDecision(member && MfaService.isVerified(context.getRequest()));
    };
}
