package com.achpayments.param;

import org.springframework.stereotype.Service;

import com.achpayments.common.AppLogRepository;

@Service
public class ParamService {

    /** Thrown when a save request breaks a rule; the API turns it into HTTP 400. */
    public static class InvalidParamException extends RuntimeException {
        public InvalidParamException(String message) {
            super(message);
        }
    }

    private final ParamRepository repo;
    private final ParamCache cache;
    private final AppLogRepository appLog;

    public ParamService(ParamRepository repo, ParamCache cache, AppLogRepository appLog) {
        this.repo = repo;
        this.cache = cache;
        this.appLog = appLog;
    }

    /**
     * Validates and upserts one parameter (UC-SYS-PARAM-01 step 5), then clears the runtime cache (step 6).
     * {@code user} comes from the login session, never the request body.
     */
    public AppParam save(String systemName, String paramName, String paramDesc, String type, String value, String user) {
        String error = ParamValidator.validate(systemName, paramName, paramDesc, type, value);
        if (error != null) throw new InvalidParamException(error);

        repo.upsert(systemName, paramName, paramDesc, type, value, user);
        appLog.log(systemName, "INFO", "User '%s' saved parameter %s/%s = %s".formatted(user, systemName, paramName, value));
        cache.clear();
        return repo.find(systemName, paramName).orElseThrow();
    }
}
