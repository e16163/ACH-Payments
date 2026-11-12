package com.achpayments.rules;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Ba's ACH Business Rules Management (2026-11-12). Only ROLE_SYS_ADMIN gets here; see SecurityConfig. The app list is /api/params/systems. */
@RestController
@RequestMapping("/api/ach-business-rules")
public class BusinessRuleController {

    /** lastModTm and lastModBy are absent: the backend sets them. systemName is read on a new rule only. */
    public record SaveRequest(String systemName, String ruleName, String ruleDesc, String pseudoCode, String ruleJson) {
    }

    private final BusinessRules rules;

    public BusinessRuleController(BusinessRules rules) {
        this.rules = rules;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam String systemName) {
        return rules.rules(systemName);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody SaveRequest body, Principal user) {
        return rules.save(null, body.systemName(), body.ruleName(), body.ruleDesc(), body.pseudoCode(), body.ruleJson(), user.getName());
    }

    @PutMapping("/{ruleID}")
    public Map<String, Object> update(@PathVariable long ruleID, @RequestBody SaveRequest body, Principal user) {
        return rules.save(ruleID, null, body.ruleName(), body.ruleDesc(), body.pseudoCode(), body.ruleJson(), user.getName());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
