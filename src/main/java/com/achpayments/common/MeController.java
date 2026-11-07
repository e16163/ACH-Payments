package com.achpayments.common;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
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

    /** {@code memberNo} is the member's number (the holderId in Ba's APIs), or null for staff logins. */
    public record Me(String username, List<String> roles, boolean mfaVerified, String memberNo) {
    }

    public record Code(String code) {
    }

    private final MfaService mfa;
    private final String memberUsername;
    private final String memberNumber;

    public MeController(MfaService mfa, @Value("${app.member-username}") String memberUsername,
            @Value("${app.member-number}") String memberNumber) {
        this.mfa = mfa;
        this.memberUsername = memberUsername;
        this.memberNumber = memberNumber;
    }

    @GetMapping("/api/me")
    public Me me(Authentication auth, HttpServletRequest request) {
        List<String> roles = auth.getAuthorities().stream().map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5)).toList();
        return new Me(auth.getName(), roles, MfaService.isVerified(request), auth.getName().equals(memberUsername) ? memberNumber : null);
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
