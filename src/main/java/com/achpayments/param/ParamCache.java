package com.achpayments.param;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Runtime cache of parameter values for the rest of the app (e.g. the Fed cutoff time).
 * Cleared on every save so a change takes effect immediately (UC-SYS-PARAM-01 step 6).
 */
@Component
public class ParamCache {

    private final ParamRepository repo;
    private final Map<String, Optional<String>> values = new ConcurrentHashMap<>();

    public ParamCache(ParamRepository repo) {
        this.repo = repo;
    }

    public Optional<String> get(String systemName, String paramName) {
        return values.computeIfAbsent(systemName + "/" + paramName,
                k -> repo.find(systemName, paramName).map(AppParam::paramRawValue));
    }

    public void clear() {
        values.clear();
    }
}
