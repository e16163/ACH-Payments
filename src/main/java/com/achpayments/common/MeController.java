package com.achpayments.common;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/** Who is logged in (so the UI shows the right menus), and the member MFA step. */
@RestController
public class MeController {

    /**
     * {@code permissions} decide which screens the UI shows (docs/LDAP-PLAN.md). {@code memberNo} is the member's
     * number (the holderId in Ba's APIs), or null for staff logins.
     */
    public record Me(String username, List<String> roles, List<String> permissions, boolean mfaVerified, String memberNo) {
    }

    public record Code(String code) {
    }

    private final MfaService mfa;
    private final MemberNumbers memberNumbers;

    public MeController(MfaService mfa, MemberNumbers memberNumbers) {
        this.mfa = mfa;
        this.memberNumbers = memberNumbers;
    }

    @GetMapping("/api/me")
    public Me me(Authentication auth, HttpServletRequest request) {
        List<String> authorities = auth.getAuthorities().stream().map(a -> a.getAuthority()).sorted().toList();
        List<String> roles = authorities.stream().filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5)).toList();
        // FACTOR_PASSWORD and the like are Spring Security's own record of how the user signed in
        List<String> permissions = authorities.stream().filter(a -> !a.startsWith("ROLE_") && !a.startsWith("FACTOR_")).toList();
        return new Me(auth.getName(), roles, permissions, MfaService.isVerified(request), memberNumbers.of(auth).orElse(null));
    }

    /** Simulated delivery: the code comes back in the response so the demo screen can show it. */
    @PostMapping("/api/member/mfa/send")
    public Map<String, String> send(Principal user, HttpServletRequest request) {
        return Map.of("demoCode", mfa.send(request, user.getName()));
    }

    @PostMapping("/api/member/mfa/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody Code body, Principal user, HttpServletRequest request) {
        if (mfa.verify(request, user.getName(), body.code())) {
            return ResponseEntity.ok(Map.of("mfaVerified", true));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "That code didn't work. Check it and try again, or send a new code."));
    }
}
