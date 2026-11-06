package com.achpayments.common;

import java.util.Arrays;

import org.springframework.core.Ordered;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * UC-SYS-LOG-01 step 1: saves any exception no controller handled. It runs last, so errors with a normal answer (like a 404) never
 * get here. It only logs: returning null keeps the response as it was.
 */
@Component
public class UnhandledErrorLogger implements HandlerExceptionResolver, Ordered {

    private final AppLogRepository appLog;

    public UnhandledErrorLogger(AppLogRepository appLog) {
        this.appLog = appLog;
    }

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE;
    }

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler, Exception e) {
        if (!(e instanceof AccessDeniedException || e instanceof AuthenticationException)) { // Spring Security answers these
            String uri = request.getRequestURI();
            appLog.log(systemOf(uri.substring(request.getContextPath().length())), "ERROR", "Unhandled error on %s %s: %s".formatted(request.getMethod(), uri, e.getClass().getName()), e);
        }
        return null;
    }

    private static String systemOf(String path) {
        if (startsWithAny(path, "/api/v1/ach/receiving/", "/api/v1/ach/back-office/receiving/")) return AppLogRepository.ACH_RCV;
        if (startsWithAny(path, "/api/eod/", "/api/transfers", "/api/member/", "/api/routing/", "/api/v1/ach/back-office/origination/")) {
            return AppLogRepository.ACH_ORIG;
        }
        return AppLogRepository.ACH_SYS;
    }

    private static boolean startsWithAny(String path, String... prefixes) {
        return Arrays.stream(prefixes).anyMatch(path::startsWith);
    }
}
