package com.achpayments.common;

import static com.achpayments.common.AppLogRepository.ACH_ORIG;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Simulated multi-factor authentication for members (USE-CASES T7). A real bank texts or emails the code; this
 * demo returns it to the screen instead. Codes are 6 random digits, expire after 5 minutes and allow 5 tries.
 */
@Service
public class MfaService {

    static final String VERIFIED = "mfa.verified";
    private static final String CODE = "mfa.code";
    private static final String EXPIRES = "mfa.expires";
    private static final String TRIES = "mfa.tries";
    private static final int MAX_TRIES = 5;

    private final SecureRandom random = new SecureRandom();
    private final AppLogRepository appLog;
    private final Clock clock;

    public MfaService(AppLogRepository appLog, Clock clock) {
        this.appLog = appLog;
        this.clock = clock;
    }

    public static boolean isVerified(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && Boolean.TRUE.equals(session.getAttribute(VERIFIED));
    }

    /** Starts a new challenge and returns the code (simulated delivery). */
    public String send(HttpServletRequest request, String user) {
        HttpSession session = request.getSession(true);
        String code = "%06d".formatted(random.nextInt(1_000_000));
        session.setAttribute(CODE, code);
        session.setAttribute(EXPIRES, Instant.now(clock).plusSeconds(300));
        session.setAttribute(TRIES, 0);
        session.removeAttribute(VERIFIED);
        appLog.log(ACH_ORIG, "INFO", "MFA code sent to member " + user + " (simulated)");
        return code;
    }

    /** True if the code matches; then the session counts as MFA-verified and gets a new id. */
    public boolean verify(HttpServletRequest request, String user, String code) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(CODE) == null) {
            return false;
        }
        int tries = (Integer) session.getAttribute(TRIES) + 1;
        session.setAttribute(TRIES, tries);
        boolean expired = Instant.now(clock).isAfter((Instant) session.getAttribute(EXPIRES));
        if (expired || tries > MAX_TRIES || !session.getAttribute(CODE).equals(code)) {
            if (expired || tries >= MAX_TRIES) {
                session.removeAttribute(CODE); // must request a new code
            }
            appLog.log(ACH_ORIG, "WARN", "MFA verification failed for member " + user);
            return false;
        }
        session.removeAttribute(CODE);
        session.setAttribute(VERIFIED, true);
        request.changeSessionId(); // session fixation protection
        appLog.log(ACH_ORIG, "INFO", "MFA verified for member " + user);
        return true;
    }
}
